"""
Payment CRUD, state machine, data consistency, and input validation tests.

Usage:
    pytest api_tests/test_payments.py -v -s
"""

import threading
import time
import requests
from collections import Counter

from api_tests.conftest import (
    BASE,
    GATEWAY_BASE,
    LEDGER_BASE,
    MERCHANT_BASE,
    BOLD,
    RESET,
    ok,
    fail,
    warn,
    info,
    record_issue,
    rand_amount,
    rand_currency,
    ensure_test_merchant,
    create_intent,
    confirm_intent,
    capture_intent,
    get_intent,
    wait_for_terminal,
    CARD_VISA,
    _lock,
)


# ═══════════════════════════════════════════════════════════════════════════════
# TESTS
# ═══════════════════════════════════════════════════════════════════════════════


def test_throughput_and_latencies():
    """Fire 40 sequential creates, measure P50/P95/P99 latency."""
    print(f"\n{BOLD}── Test Name: test_throughput_and_latencies ──{RESET}")
    latencies = []
    errors = 0

    for _ in range(40):
        t0 = time.time()
        try:
            create_intent()
            latencies.append((time.time() - t0) * 1000)
        except Exception as e:
            errors += 1

    if not latencies:
        record_issue("CRITICAL", "throughput", "All 40 create requests failed — no latency data")
        return

    latencies.sort()
    p50 = latencies[len(latencies) // 2]
    p95 = latencies[int(len(latencies) * 0.95)]
    p99 = latencies[int(len(latencies) * 0.99)]
    info(f"P50={p50:.0f}ms  P95={p95:.0f}ms  P99={p99:.0f}ms  errors={errors}")

    if errors > 0:
        record_issue("CRITICAL", "throughput", f"{errors}/40 create requests failed")
    else:
        ok("All 40 creates succeeded")

    if p99 > 500:
        record_issue("WARNING", "throughput", f"P99 latency {p99:.0f}ms exceeds 500ms threshold")
    else:
        ok(f"Latencies within threshold (P99={p99:.0f}ms)")


def test_state_machine_guards():
    """Verify invalid transitions are rejected."""
    print(f"\n{BOLD}── Test Name: test_state_machine_guards ──{RESET}")

    # Capture on fresh (unconfirmed) intent should be rejected
    intent = create_intent()
    r = capture_intent(intent["id"])
    if r.status_code == 409:
        ok("Capture on requires_confirmation -> 409 Conflict")
    else:
        record_issue("WARNING", "state_machine",
                     f"Capture on fresh intent returned {r.status_code}, expected 409")

    # Confirm an intent, wait for it to fail, then try to confirm again
    for _ in range(6):
        i2 = create_intent()
        confirm_intent(i2["id"])
        s = wait_for_terminal(i2["id"], timeout_s=150)
        if s == "failed":
            r2 = confirm_intent(i2["id"])
            if r2.status_code in (400, 409):
                ok(f"Second confirm on failed intent -> {r2.status_code}")
            else:
                record_issue("WARNING", "state_machine",
                             f"Second confirm on failed intent returned {r2.status_code}")
            break
    else:
        warn("Couldn't get a failed intent in 6 tries for re-confirm test")


def test_data_consistency_under_load():
    """
    Create 20 intents, confirm all concurrently, wait, then verify:
    - Every confirmed intent has exactly 1 PaymentAttempt
    - Every PaymentAttempt has at least 1 AUTH InternalAttempt
    - PaymentIntent status matches PaymentAttempt status
    """
    print(f"\n{BOLD}── Test Name: test_data_consistency_under_load ──{RESET}")
    intents = [create_intent(amount=rand_amount(), currency=rand_currency()) for _ in range(20)]

    def confirm_all(intent):
        try:
            confirm_intent(intent["id"])
        except Exception:
            pass

    threads = [threading.Thread(target=confirm_all, args=(i,)) for i in intents]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    info("Waiting 8s for all webhooks to settle...")
    time.sleep(8)

    mismatches = 0
    multi_attempts = 0
    missing_internal = 0
    status_distribution = Counter()

    for intent in intents:
        detail = get_intent(intent["id"])
        attempts = detail.get("attempts", [])
        status_distribution[detail["status"]] += 1

        # Each intent should have exactly 1 PaymentAttempt
        if len(attempts) != 1:
            multi_attempts += 1
            record_issue("CRITICAL", "consistency",
                         f"Intent {intent['id']} has {len(attempts)} PaymentAttempts, expected 1")
            continue

        attempt = attempts[0]
        auth_internals = [ia for ia in attempt.get("internalAttempts", []) if ia["type"] == "auth"]

        # Should have at least 1 auth internal attempt
        if len(auth_internals) == 0:
            missing_internal += 1
            record_issue("CRITICAL", "consistency",
                         f"Intent {intent['id']} has no AUTH InternalAttempt")
            continue

        # PaymentIntent status should align with PaymentAttempt status
        intent_s = detail["status"]
        attempt_s = attempt["status"]
        valid_pairs = {
            ("authorized", "authorized"),
            ("failed", "failed"),
            ("captured", "captured"),
            ("requires_confirmation", "pending"),
        }
        if (intent_s, attempt_s) not in valid_pairs:
            mismatches += 1
            record_issue("CRITICAL", "consistency",
                         f"Status mismatch: intent={intent_s}, attempt={attempt_s}")

    info(f"Status distribution: {dict(status_distribution)}")

    total = len(intents)
    consistent = total - multi_attempts - missing_internal - mismatches
    ok(f"{consistent}/{total} intents fully consistent")
    if multi_attempts == 0 and missing_internal == 0 and mismatches == 0:
        ok("All data consistent")


def test_input_validation():
    """Test that invalid inputs are rejected with structured error responses."""
    print(f"\n{BOLD}── Test Name: test_input_validation ──{RESET}")

    merchant_id = ensure_test_merchant()

    # Invalid amount + invalid currency -> 400 with structured error
    r = requests.post(f"{BASE}/v1/payment_intents",
                      json={"merchantId": merchant_id, "amount": -100, "currency": "INVALID"},
                      timeout=5)
    assert r.status_code == 400, f"Expected 400 for invalid input, got {r.status_code}"
    error_body = r.json()
    assert "type" in error_body, f"Expected structured error with 'type' field, got: {error_body}"
    assert "code" in error_body, f"Expected structured error with 'code' field, got: {error_body}"
    ok(f"Invalid amount/currency correctly rejected with structured error: type={error_body['type']}, code={error_body['code']}")

    # Invalid currency 'XYZ' -> 400
    r = requests.post(f"{BASE}/v1/payment_intents",
                      json={"merchantId": merchant_id, "amount": 100, "currency": "XYZ"},
                      timeout=5)
    assert r.status_code == 400, f"Expected 400 for invalid currency, got {r.status_code}"
    ok("Invalid currency 'XYZ' correctly rejected")

    # Missing merchantId -> 400
    r = requests.post(f"{BASE}/v1/payment_intents",
                      json={"amount": 100, "currency": "USD"},
                      timeout=5)
    assert r.status_code == 400, f"Expected 400 for missing merchantId, got {r.status_code}"
    ok("Missing merchantId correctly rejected")

    # Invalid merchantId -> 400
    r = requests.post(f"{BASE}/v1/payment_intents",
                      json={"merchantId": "merch_nonexistent", "amount": 100, "currency": "USD"},
                      timeout=5)
    assert r.status_code == 400, f"Expected 400 for invalid merchantId, got {r.status_code}"
    ok("Invalid merchantId correctly rejected")

    # Amount = 0 -> 400
    r = requests.post(f"{BASE}/v1/payment_intents",
                      json={"merchantId": merchant_id, "amount": 0, "currency": "USD"},
                      timeout=5)
    assert r.status_code == 400, f"Expected 400 for amount=0, got {r.status_code}"
    ok("Amount=0 correctly rejected")

    # Amount = -100 -> 400
    r = requests.post(f"{BASE}/v1/payment_intents",
                      json={"merchantId": merchant_id, "amount": -100, "currency": "USD"},
                      timeout=5)
    assert r.status_code == 400, f"Expected 400 for negative amount, got {r.status_code}"
    ok("Negative amount correctly rejected")


def test_openapi_docs_available():
    """Verify OpenAPI docs at /api-docs for all external services."""
    print(f"\n{BOLD}── Test Name: test_openapi_docs_available ──{RESET}")

    for port, name in [(8080, "checkout"), (8081, "gateway"), (8082, "ledger"), (8087, "merchant")]:
        try:
            r = requests.get(f"http://localhost:{port}/api-docs", timeout=5)
            if r.status_code == 200:
                ok(f"OpenAPI docs available for {name} (port {port})")
            else:
                record_issue("WARNING", "openapi_docs",
                             f"OpenAPI docs returned {r.status_code} for {name} (port {port})")
        except Exception as e:
            record_issue("WARNING", "openapi_docs",
                         f"OpenAPI docs not available for {name} (port {port}): {e}")
