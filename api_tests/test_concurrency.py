"""
Concurrency, distributed lock, and idempotency tests for the payment gateway.

Tests race conditions on create, confirm, capture, and idempotency-key replay.
"""

import threading
import time
import uuid
from collections import Counter

import requests

from api_tests.conftest import *


# =============================================================================
#  1. Concurrent Creates
# =============================================================================

def test_concurrent_creates():
    print(f"\n{BOLD}{'='*60}{RESET}")
    print(f"{BOLD}  TEST: Concurrent PaymentIntent Creates (50 threads){RESET}")
    print(f"{BOLD}{'='*60}{RESET}")

    merchant_id = ensure_test_merchant()
    results = []
    lock = threading.Lock()

    def _create():
        try:
            r = requests.post(
                f"{BASE}/v1/payment_intents",
                json={"merchantId": merchant_id, "amount": rand_amount(), "currency": rand_currency()},
                timeout=15,
            )
            with lock:
                results.append(r)
        except Exception as e:
            with lock:
                results.append(e)

    threads = [threading.Thread(target=_create) for _ in range(50)]
    t0 = time.time()
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=30)
    elapsed = time.time() - t0

    info(f"50 concurrent creates finished in {elapsed:.1f}s")

    status_counts = Counter()
    ids_seen = set()
    errors = []

    for r in results:
        if isinstance(r, Exception):
            errors.append(str(r))
            continue
        status_counts[r.status_code] += 1
        if r.status_code == 201:
            body = r.json()
            intent_id = body["id"]
            if intent_id in ids_seen:
                record_issue("CRITICAL", "concurrent_creates", f"Duplicate ID returned: {intent_id}")
            ids_seen.add(intent_id)

    info(f"Status distribution: {dict(status_counts)}")
    info(f"Unique IDs created: {len(ids_seen)}")

    # Verify: no 5xx errors
    server_errors = sum(v for k, v in status_counts.items() if k >= 500)
    if server_errors > 0:
        record_issue("CRITICAL", "concurrent_creates", f"{server_errors} responses had 5xx status")
    else:
        ok("No 5xx errors")

    # Verify: no duplicate IDs
    created_count = status_counts.get(201, 0)
    if len(ids_seen) == created_count:
        ok(f"All {created_count} created intents have unique IDs")
    else:
        record_issue("CRITICAL", "concurrent_creates", f"ID count mismatch: {len(ids_seen)} unique vs {created_count} created")

    if errors:
        record_issue("WARNING", "concurrent_creates", f"{len(errors)} requests raised exceptions")


# =============================================================================
#  2. Concurrent Confirm with Redis Distributed Lock
# =============================================================================

def test_concurrent_confirm_redis_lock():
    print(f"\n{BOLD}{'='*60}{RESET}")
    print(f"{BOLD}  TEST: Concurrent Confirm with Redis Lock (80 threads){RESET}")
    print(f"{BOLD}{'='*60}{RESET}")

    # Create a fresh intent
    intent = create_intent()
    intent_id = intent["id"]
    info(f"Created intent {intent_id} (status={intent['status']})")

    results = []
    lock = threading.Lock()

    def _confirm():
        t0 = time.time()
        try:
            r = confirm_intent(intent_id)
            elapsed_ms = (time.time() - t0) * 1000
            with lock:
                results.append((r.status_code, elapsed_ms, r))
        except Exception as e:
            elapsed_ms = (time.time() - t0) * 1000
            with lock:
                results.append((-1, elapsed_ms, e))

    threads = [threading.Thread(target=_confirm) for _ in range(80)]
    t0 = time.time()
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=60)
    total_elapsed = time.time() - t0

    info(f"80 concurrent confirms finished in {total_elapsed:.1f}s")

    # Classify responses
    status_counts = Counter()
    latencies_by_status = {}
    for status_code, elapsed_ms, resp in results:
        status_counts[status_code] += 1
        latencies_by_status.setdefault(status_code, []).append(elapsed_ms)

    info(f"Status distribution: {dict(status_counts)}")

    # Verify: no 500 errors
    count_500 = sum(v for k, v in status_counts.items() if k >= 500)
    if count_500 > 0:
        record_issue("CRITICAL", "concurrent_confirm_redis_lock", f"{count_500} responses had 5xx status")
    else:
        ok("No 500 errors")

    # Verify: exactly 1 success (200)
    count_200 = status_counts.get(200, 0)
    if count_200 == 1:
        ok("Exactly 1 confirm succeeded (200)")
    elif count_200 == 0:
        record_issue("CRITICAL", "concurrent_confirm_redis_lock", "No confirm succeeded (0 x 200)")
    else:
        record_issue("WARNING", "concurrent_confirm_redis_lock", f"{count_200} confirms returned 200 (expected 1)")

    # Verify: most responses are 409
    count_409 = status_counts.get(409, 0)
    info(f"409 Conflict responses: {count_409}")

    # Verify: P50 of 409 responses < 200ms
    if 409 in latencies_by_status:
        lat_409 = sorted(latencies_by_status[409])
        p50_409 = lat_409[len(lat_409) // 2]
        if p50_409 < 200:
            ok(f"P50 latency of 409 responses: {p50_409:.0f}ms (< 200ms)")
        else:
            record_issue("WARNING", "concurrent_confirm_redis_lock", f"P50 latency of 409 responses: {p50_409:.0f}ms (>= 200ms)")
        percentiles(lat_409, "409 latency")

    if 200 in latencies_by_status:
        percentiles(latencies_by_status[200], "200 latency")

    # Wait 3s, then verify intent detail
    info("Waiting 3s before checking intent detail...")
    time.sleep(3)

    detail = get_intent(intent_id)
    attempts = detail.get("attempts", [])
    attempt_count = len(attempts)
    info(f"Intent status: {detail['status']}, PaymentAttempts: {attempt_count}")

    if attempt_count == 1:
        ok("Exactly 1 PaymentAttempt created (lock enforced)")
    elif attempt_count == 0:
        record_issue("WARNING", "concurrent_confirm_redis_lock", "No PaymentAttempt created")
    else:
        record_issue("CRITICAL", "concurrent_confirm_redis_lock", f"{attempt_count} PaymentAttempts created (expected 1 -- lock not enforced)")


# =============================================================================
#  3. Idempotency Key Correctness
# =============================================================================

def test_idempotency_correctness():
    print(f"\n{BOLD}{'='*60}{RESET}")
    print(f"{BOLD}  TEST: Idempotency Key Correctness{RESET}")
    print(f"{BOLD}{'='*60}{RESET}")

    merchant_id = ensure_test_merchant()
    idem_key = str(uuid.uuid4())
    amount = rand_amount()
    currency = rand_currency()
    payload = {"merchantId": merchant_id, "amount": amount, "currency": currency}

    info(f"Idempotency key: {idem_key}")
    info(f"Payload: amount={amount}, currency={currency}")

    # --- First create: should succeed ---
    r1 = requests.post(
        f"{BASE}/v1/payment_intents",
        json=payload,
        headers={"Idempotency-Key": idem_key},
        timeout=10,
    )
    assert r1.status_code == 201, f"First create failed: {r1.status_code} {r1.text}"
    original_id = r1.json()["id"]
    ok(f"First create succeeded: id={original_id}")

    # --- Replay with same key + same payload: should return cached response ---
    r2 = requests.post(
        f"{BASE}/v1/payment_intents",
        json=payload,
        headers={"Idempotency-Key": idem_key},
        timeout=10,
    )
    if r2.status_code in (200, 201) and r2.json()["id"] == original_id:
        ok(f"Same key + same payload returned cached id={original_id}")
    else:
        record_issue("CRITICAL", "idempotency_correctness", f"Same key + same payload returned status={r2.status_code}, id={r2.json().get('id')}")

    # --- Different payload with same key: should return 400 ---
    different_payload = {"merchantId": merchant_id, "amount": amount + 100, "currency": currency}
    r3 = requests.post(
        f"{BASE}/v1/payment_intents",
        json=different_payload,
        headers={"Idempotency-Key": idem_key},
        timeout=10,
    )
    if r3.status_code == 400:
        ok("Different payload with same key correctly returned 400")
    else:
        record_issue("CRITICAL", "idempotency_correctness", f"Different payload with same key returned {r3.status_code} (expected 400)")

    # --- 10 concurrent replays: all should return the same ID ---
    info("Firing 10 concurrent replays with same key + same payload...")
    replay_results = []
    lock = threading.Lock()

    def _replay():
        try:
            r = requests.post(
                f"{BASE}/v1/payment_intents",
                json=payload,
                headers={"Idempotency-Key": idem_key},
                timeout=10,
            )
            with lock:
                replay_results.append(r)
        except Exception as e:
            with lock:
                replay_results.append(e)

    threads = [threading.Thread(target=_replay) for _ in range(10)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=20)

    replay_ids = set()
    replay_errors = 0
    for r in replay_results:
        if isinstance(r, Exception):
            replay_errors += 1
            continue
        if r.status_code in (200, 201):
            replay_ids.add(r.json()["id"])
        else:
            replay_errors += 1

    if len(replay_ids) == 1 and original_id in replay_ids:
        ok(f"All 10 concurrent replays returned the same id={original_id}")
    elif len(replay_ids) == 0:
        record_issue("CRITICAL", "idempotency_correctness", f"No successful replays (errors={replay_errors})")
    else:
        record_issue("CRITICAL", "idempotency_correctness", f"Concurrent replays returned {len(replay_ids)} distinct IDs: {replay_ids}")

    if replay_errors > 0:
        record_issue("WARNING", "idempotency_correctness", f"{replay_errors}/10 concurrent replays failed or returned errors")


# =============================================================================
#  4. Concurrent Capture
# =============================================================================

def test_concurrent_capture():
    print(f"\n{BOLD}{'='*60}{RESET}")
    print(f"{BOLD}  TEST: Concurrent Capture (10 threads){RESET}")
    print(f"{BOLD}{'='*60}{RESET}")

    # Create and confirm an intent, then wait for authorization
    intent = create_intent()
    intent_id = intent["id"]
    info(f"Created intent {intent_id}")

    r = confirm_intent(intent_id)
    if r.status_code != 200:
        record_issue("CRITICAL", "concurrent_capture", f"Confirm failed: {r.status_code} {r.text}")
        return
    info(f"Confirmed intent {intent_id}")

    # Wait for authorization (up to 150s, 5 retries to get an authorized intent)
    authorized = False
    authorized_intent_id = intent_id
    for attempt_num in range(5):
        info(f"Waiting for auth (attempt {attempt_num + 1}/5)...")
        status = wait_for_terminal(authorized_intent_id, timeout_s=30)
        if status == "authorized":
            authorized = True
            info(f"Intent {authorized_intent_id} reached authorized state")
            break
        elif status in ("failed", "expired"):
            # Gateway returned failure -- create a new intent and try again
            warn(f"Intent {authorized_intent_id} ended in {status}, retrying with new intent...")
            new_intent = create_intent()
            authorized_intent_id = new_intent["id"]
            r = confirm_intent(authorized_intent_id)
            if r.status_code != 200:
                warn(f"Re-confirm failed: {r.status_code}")
                continue
        else:
            warn(f"Intent {authorized_intent_id} still in {status} after 30s")

    if not authorized:
        record_issue("WARNING", "concurrent_capture", "Could not get an authorized intent after 5 attempts (150s total). Skipping capture concurrency test.")
        return

    # Fire 10 concurrent captures
    results = []
    lock = threading.Lock()

    def _capture():
        try:
            r = capture_intent(authorized_intent_id)
            with lock:
                results.append(r)
        except Exception as e:
            with lock:
                results.append(e)

    threads = [threading.Thread(target=_capture) for _ in range(10)]
    t0 = time.time()
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=30)
    elapsed = time.time() - t0

    info(f"10 concurrent captures finished in {elapsed:.1f}s")

    status_counts = Counter()
    for r in results:
        if isinstance(r, Exception):
            status_counts["exception"] += 1
        else:
            status_counts[r.status_code] += 1

    info(f"Status distribution: {dict(status_counts)}")

    # Check 5xx
    count_5xx = sum(v for k, v in status_counts.items() if isinstance(k, int) and k >= 500)
    if count_5xx > 0:
        record_issue("CRITICAL", "concurrent_capture", f"{count_5xx} responses had 5xx status")
    else:
        ok("No 5xx errors during concurrent capture")

    # Wait for capture webhook processing, then check detail
    info("Waiting 10s for capture webhook processing...")
    time.sleep(10)

    detail = get_intent(authorized_intent_id)
    attempts = detail.get("attempts", [])
    info(f"Final intent status: {detail['status']}")

    # Count CAPTURE-type InternalAttempts across all PaymentAttempts
    capture_internal_attempts = 0
    for attempt in attempts:
        for ia in attempt.get("internalAttempts", []):
            if ia.get("type", "").lower() == "capture":
                capture_internal_attempts += 1

    info(f"CAPTURE InternalAttempts: {capture_internal_attempts}")

    if capture_internal_attempts == 1:
        ok("Exactly 1 CAPTURE InternalAttempt created")
    elif capture_internal_attempts == 0:
        record_issue("WARNING", "concurrent_capture", "No CAPTURE InternalAttempt found")
    else:
        record_issue("CRITICAL", "concurrent_capture", f"{capture_internal_attempts} CAPTURE InternalAttempts created (expected 1)")

    # Final status should be captured or still authorized (if gateway failed the capture)
    if detail["status"] in ("captured", "authorized"):
        ok(f"Final status is '{detail['status']}' (acceptable)")
    else:
        record_issue("WARNING", "concurrent_capture", f"Unexpected final status: {detail['status']}")


# =============================================================================
#  Run all
# =============================================================================

if __name__ == "__main__":
    reset_issues()
    test_concurrent_creates()
    test_concurrent_confirm_redis_lock()
    test_idempotency_correctness()
    test_concurrent_capture()
    exit(print_results())
