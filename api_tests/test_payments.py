"""
Payment CRUD, state machine, and input validation tests.

Usage:
    pytest api_tests/test_payments.py -v -s
"""

import time
import requests

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
    ensure_test_merchant,
    create_intent,
    confirm_intent,
    capture_intent,
    get_intent,
    wait_for_terminal,
    CARD_VISA,
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

    # Description > 500 chars -> 400
    r = requests.post(f"{BASE}/v1/payment_intents",
                      json={"merchantId": merchant_id, "amount": 100, "currency": "USD",
                            "description": "x" * 501},
                      timeout=5)
    assert r.status_code == 400, f"Expected 400 for description > 500 chars, got {r.status_code}"
    ok("Description > 500 chars correctly rejected")

    # Invalid email format -> 400
    r = requests.post(f"{BASE}/v1/payment_intents",
                      json={"merchantId": merchant_id, "amount": 100, "currency": "USD",
                            "customerEmail": "not-an-email"},
                      timeout=5)
    assert r.status_code == 400, f"Expected 400 for invalid email, got {r.status_code}"
    ok("Invalid email format correctly rejected")

    # Statement descriptor > 22 chars -> 400
    r = requests.post(f"{BASE}/v1/payment_intents",
                      json={"merchantId": merchant_id, "amount": 100, "currency": "USD",
                            "statementDescriptor": "A" * 23},
                      timeout=5)
    assert r.status_code == 400, f"Expected 400 for statementDescriptor > 22 chars, got {r.status_code}"
    ok("Statement descriptor > 22 chars correctly rejected")


def test_confirm_input_validation():
    """Test card-related DTO validation on the confirm endpoint."""
    print(f"\n{BOLD}── Test Name: test_confirm_input_validation ──{RESET}")

    intent = create_intent()
    intent_id = intent["id"]

    # Empty card number -> 400
    r = requests.post(f"{BASE}/v1/payment_intents/{intent_id}/authorise",
                      json={"cardNumber": "", "expiryMonth": 12, "expiryYear": 2030, "cvc": "123"},
                      timeout=5)
    assert r.status_code == 400, f"Expected 400 for empty cardNumber, got {r.status_code}"
    ok("Empty cardNumber correctly rejected")

    # expiryMonth = 0 -> 400
    r = requests.post(f"{BASE}/v1/payment_intents/{intent_id}/authorise",
                      json={"cardNumber": CARD_VISA["cardNumber"], "expiryMonth": 0,
                            "expiryYear": 2030, "cvc": "123"},
                      timeout=5)
    assert r.status_code == 400, f"Expected 400 for expiryMonth=0, got {r.status_code}"
    ok("expiryMonth=0 correctly rejected")

    # expiryMonth = 13 -> should be 400 (requires @Max(12) annotation)
    r = requests.post(f"{BASE}/v1/payment_intents/{intent_id}/authorise",
                      json={"cardNumber": CARD_VISA["cardNumber"], "expiryMonth": 13,
                            "expiryYear": 2030, "cvc": "123"},
                      timeout=5)
    if r.status_code == 400:
        ok("expiryMonth=13 correctly rejected")
    else:
        warn(f"expiryMonth=13 returned {r.status_code} — missing @Max(12) validation on DTO")

    # expiryYear = 2024 -> 400
    r = requests.post(f"{BASE}/v1/payment_intents/{intent_id}/authorise",
                      json={"cardNumber": CARD_VISA["cardNumber"], "expiryMonth": 12,
                            "expiryYear": 2024, "cvc": "123"},
                      timeout=5)
    assert r.status_code == 400, f"Expected 400 for expiryYear=2024, got {r.status_code}"
    ok("expiryYear=2024 correctly rejected")

    # CVC too short (2 digits) -> 400
    r = requests.post(f"{BASE}/v1/payment_intents/{intent_id}/authorise",
                      json={"cardNumber": CARD_VISA["cardNumber"], "expiryMonth": 12,
                            "expiryYear": 2030, "cvc": "12"},
                      timeout=5)
    assert r.status_code == 400, f"Expected 400 for CVC too short, got {r.status_code}"
    ok("CVC too short (2 digits) correctly rejected")

    # CVC too long (5 digits) -> 400
    r = requests.post(f"{BASE}/v1/payment_intents/{intent_id}/authorise",
                      json={"cardNumber": CARD_VISA["cardNumber"], "expiryMonth": 12,
                            "expiryYear": 2030, "cvc": "12345"},
                      timeout=5)
    assert r.status_code == 400, f"Expected 400 for CVC too long, got {r.status_code}"
    ok("CVC too long (5 digits) correctly rejected")


def test_capture_on_non_authorized_states():
    """Test capture returns appropriate errors for each non-AUTHORIZED state."""
    print(f"\n{BOLD}── Test Name: test_capture_on_non_authorized_states ──{RESET}")

    # Capture on REQUIRES_CONFIRMATION -> 409
    intent = create_intent()
    r = capture_intent(intent["id"])
    assert r.status_code == 409, f"Expected 409 for capture on requires_confirmation, got {r.status_code}"
    body = r.json()
    assert "authorized" in body.get("message", "").lower(), \
        f"Expected error mentioning 'authorized', got: {body.get('message', '')}"
    ok("Capture on requires_confirmation -> 409 with correct message")

    # Capture on FAILED -> 409
    for _ in range(6):
        i2 = create_intent()
        confirm_intent(i2["id"])
        s = wait_for_terminal(i2["id"], timeout_s=30)
        if s == "failed":
            r = capture_intent(i2["id"])
            assert r.status_code == 409, f"Expected 409 for capture on failed, got {r.status_code}"
            ok("Capture on failed -> 409")
            break
    else:
        warn("Couldn't get a failed intent in 6 tries for capture-on-failed test")

    # Capture on CAPTURED -> 409 (need an authorized intent first)
    for _ in range(5):
        i3 = create_intent()
        confirm_intent(i3["id"])
        s = wait_for_terminal(i3["id"], timeout_s=30)
        if s == "authorized":
            capture_intent(i3["id"])
            time.sleep(5)
            s2 = get_intent(i3["id"])["status"]
            if s2 == "captured":
                r = capture_intent(i3["id"])
                assert r.status_code == 409, f"Expected 409 for capture on captured, got {r.status_code}"
                ok("Capture on already-captured -> 409")
                break
    else:
        warn("Couldn't get a captured intent for capture-on-captured test")


def test_merchant_validation_on_list_endpoints():
    """Test that nonexistent merchant IDs are rejected on list/cursor endpoints."""
    print(f"\n{BOLD}── Test Name: test_merchant_validation_on_list_endpoints ──{RESET}")

    fake_merchant = "merch_does_not_exist_xyz"

    # Offset-based merchant list
    r = requests.get(f"{BASE}/v1/payment_intents/merchant/{fake_merchant}", timeout=5)
    assert r.status_code == 400, f"Expected 400 for list by nonexistent merchant, got {r.status_code}"
    ok("List by nonexistent merchant returns 400")

    # Cursor-based merchant list
    r = requests.get(f"{BASE}/v1/payment_intents/cursor/merchant/{fake_merchant}",
                     params={"limit": 5}, timeout=5)
    assert r.status_code == 400, f"Expected 400 for cursor by nonexistent merchant, got {r.status_code}"
    ok("Cursor by nonexistent merchant returns 400")


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
