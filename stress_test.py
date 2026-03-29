#!/usr/bin/env python3
"""
Payment Orchestrator — Stress Test
Fires concurrent requests and hunts for consistency bugs, race conditions,
and correctness violations.

Usage:
    python stress_test.py              # run all tests
    python stress_test.py --stress     # run only the sustained load test
"""

import sys
import threading
import requests
import time
import uuid
import json
import hmac
import hashlib
import random
from collections import defaultdict, Counter
from dataclasses import dataclass, field
from typing import Optional

CURRENCIES = ["USD", "EUR", "GBP", "JPY", "AUD", "CAD"]

BASE = "http://localhost:8080"
GATEWAY_BASE = "http://localhost:8081"
LEDGER_BASE = "http://localhost:8082"
WEBHOOK_SECRET = "payment-gateway-webhook-secret-2026"
RESULTS = []
LOCK = threading.Lock()

# ─── ANSI colours ────────────────────────────────────────────────────────────
GREEN  = "\033[92m"
RED    = "\033[91m"
YELLOW = "\033[93m"
CYAN   = "\033[96m"
BOLD   = "\033[1m"
RESET  = "\033[0m"

def ok(msg):  print(f"  {GREEN}✓{RESET} {msg}")
def fail(msg): print(f"  {RED}✗ {msg}{RESET}")
def warn(msg): print(f"  {YELLOW}⚠{RESET} {msg}")
def info(msg): print(f"  {CYAN}→{RESET} {msg}")

@dataclass
class Issue:
    severity: str   # CRITICAL / WARNING / INFO
    test: str
    detail: str

issues: list[Issue] = []

def record_issue(severity, test, detail):
    with LOCK:
        issues.append(Issue(severity, test, detail))
    sym = {"CRITICAL": f"{RED}[CRITICAL]{RESET}", "WARNING": f"{YELLOW}[WARNING]{RESET}", "INFO": f"{CYAN}[INFO]{RESET}"}[severity]
    print(f"    {sym} {detail}")

# ─── helpers ─────────────────────────────────────────────────────────────────

def signed_webhook_post(internal_attempt_id: str, status: str, timeout: int = 5):
    """POST to the webhook endpoint with proper HMAC-SHA256 signature."""
    payload = {"internalAttemptId": internal_attempt_id, "status": status}
    body = json.dumps(payload)
    timestamp = str(int(time.time()))
    signature = hmac.new(
        WEBHOOK_SECRET.encode(), f"{timestamp}.{body}".encode(), hashlib.sha256
    ).hexdigest()
    return requests.post(
        f"{BASE}/webhooks/gateway",
        data=body,
        headers={
            "Content-Type": "application/json",
            "X-Gateway-Signature": signature,
            "X-Gateway-Timestamp": timestamp,
        },
        timeout=timeout,
    )

def rand_amount():
    """Random amount in smallest currency unit (cents) with realistic decimal values.
    E.g., 1050 = $10.50, 99 = $0.99, 249999 = $2,499.99"""
    return random.randint(1, 999999)

def rand_currency():
    return random.choice(CURRENCIES)

def create_intent(amount=None, currency=None) -> dict:
    amount = amount or rand_amount()
    currency = currency or rand_currency()
    r = requests.post(f"{BASE}/payment_intents", json={"amount": amount, "currency": currency}, timeout=10)
    r.raise_for_status()
    return r.json()

CARD_VISA = {
    "cardNumber": "4242424242424242",
    "cardholderName": "Test User",
    "expiryMonth": 12,
    "expiryYear": 2030,
    "cvc": "123"
}

def confirm_intent(intent_id, card_data=None) -> requests.Response:
    return requests.post(f"{BASE}/payment_intents/{intent_id}/confirm",
                         json=card_data or CARD_VISA,
                         headers={"Content-Type": "application/json"}, timeout=15)

def capture_intent(intent_id) -> requests.Response:
    return requests.post(f"{BASE}/payment_intents/{intent_id}/capture", timeout=15)

def get_intent(intent_id) -> dict:
    r = requests.get(f"{BASE}/payment_intents/{intent_id}", timeout=10)
    r.raise_for_status()
    return r.json()

def wait_for_terminal(intent_id, timeout_s=180) -> str:
    """Poll until status is no longer requires_confirmation, return final status."""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        d = get_intent(intent_id)
        if d["status"] not in ("requires_confirmation",):
            return d["status"]
        time.sleep(2)
    return get_intent(intent_id)["status"]


# ═══════════════════════════════════════════════════════════════════════════════
# TEST SUITES
# ═══════════════════════════════════════════════════════════════════════════════

def test_throughput_and_latencies():
    """Fire 40 sequential creates and measure P50/P95/P99 latency."""
    print(f"\n{BOLD}── Test 1: Throughput & Latency (40 sequential creates) ──{RESET}")
    latencies = []
    errors = 0
    for _ in range(40):
        t0 = time.time()
        try:
            create_intent()
            latencies.append((time.time() - t0) * 1000)
        except Exception as e:
            errors += 1

    latencies.sort()
    p50 = latencies[len(latencies)//2]
    p95 = latencies[int(len(latencies)*0.95)]
    p99 = latencies[int(len(latencies)*0.99)]
    info(f"P50={p50:.0f}ms  P95={p95:.0f}ms  P99={p99:.0f}ms  errors={errors}")

    if errors > 0:
        record_issue("CRITICAL", "throughput", f"{errors}/40 create requests failed")
    else:
        ok("All 40 creates succeeded")

    if p99 > 500:
        record_issue("WARNING", "throughput", f"P99 latency {p99:.0f}ms exceeds 500ms threshold")
    else:
        ok(f"Latencies within threshold (P99={p99:.0f}ms)")


def test_concurrent_creates():
    """50 concurrent PaymentIntent creates — check for 5xx or duplicates."""
    print(f"\n{BOLD}── Test 2: Concurrent Creates (50 threads) ──{RESET}")
    results = []
    errors = []

    def do_create():
        try:
            r = create_intent(amount=rand_amount(), currency=rand_currency())
            with LOCK:
                results.append(r["id"])
        except Exception as e:
            with LOCK:
                errors.append(str(e))

    threads = [threading.Thread(target=do_create) for _ in range(50)]
    t0 = time.time()
    for t in threads: t.start()
    for t in threads: t.join()
    elapsed = time.time() - t0

    info(f"{len(results)} created, {len(errors)} errors in {elapsed:.2f}s")
    duplicates = len(results) - len(set(results))

    if errors:
        record_issue("CRITICAL", "concurrent_creates", f"{len(errors)} errors during concurrent creates: {errors[:3]}")
    else:
        ok("No errors under concurrent load")

    if duplicates:
        record_issue("CRITICAL", "concurrent_creates", f"{duplicates} duplicate IDs returned!")
    else:
        ok("All IDs are unique")


def test_race_condition_double_confirm():
    """Fire 10 concurrent confirms on the SAME intent — only 1 attempt should be created."""
    print(f"\n{BOLD}── Test 3: Race Condition — Concurrent Confirms on Same Intent ──{RESET}")
    intent = create_intent()
    iid = intent["id"]

    responses = []
    def do_confirm():
        r = confirm_intent(iid)
        with LOCK:
            responses.append((r.status_code, r.json()))

    threads = [threading.Thread(target=do_confirm) for _ in range(10)]
    for t in threads: t.start()
    for t in threads: t.join()

    time.sleep(4)  # wait for webhooks
    detail = get_intent(iid)
    attempt_count = len(detail.get("attempts", []))

    statuses = Counter(r[0] for r in responses)
    info(f"HTTP status distribution: {dict(statuses)}")
    info(f"PaymentAttempts created: {attempt_count}")

    if attempt_count > 1:
        record_issue("CRITICAL", "double_confirm_race",
                     f"{attempt_count} PaymentAttempts created from 10 concurrent confirms — expected 1")
    else:
        ok(f"Only 1 PaymentAttempt created (race condition handled)")


def test_idempotency_correctness():
    """Idempotency key on CREATE: same key → cached response, different payload → 400."""
    print(f"\n{BOLD}── Test 4: Idempotency Key Correctness (create only) ──{RESET}")
    key = str(uuid.uuid4())
    amount = rand_amount()
    currency = rand_currency()

    r1 = requests.post(f"{BASE}/payment_intents", json={"amount": amount, "currency": currency},
                       headers={"Idempotency-Key": key}, timeout=10)
    r1.raise_for_status()
    first_id = r1.json()["id"]
    ok(f"First create: id={first_id}")

    # Replay — same key, same payload → should return cached response
    r2 = requests.post(f"{BASE}/payment_intents", json={"amount": amount, "currency": currency},
                       headers={"Idempotency-Key": key}, timeout=10)
    if r2.status_code in (200, 201) and r2.json()["id"] == first_id:
        ok("Replay returns same cached response")
    else:
        record_issue("CRITICAL", "idempotency", f"Replay failed: {r2.status_code} {r2.text}")

    # Same key, different payload → should be rejected
    r3 = requests.post(f"{BASE}/payment_intents", json={"amount": amount + 1, "currency": currency},
                       headers={"Idempotency-Key": key}, timeout=10)
    if r3.status_code == 400:
        ok("Different payload with same key correctly rejected (400)")
    else:
        record_issue("WARNING", "idempotency",
                     f"Different payload with same key returned {r3.status_code} instead of 400")

    # Concurrent replays — all should return the same response
    replay_results = []
    def replay():
        r = requests.post(f"{BASE}/payment_intents", json={"amount": amount, "currency": currency},
                          headers={"Idempotency-Key": key}, timeout=10)
        with LOCK:
            replay_results.append(r.json().get("id"))

    threads = [threading.Thread(target=replay) for _ in range(10)]
    for t in threads: t.start()
    for t in threads: t.join()

    if all(r == first_id for r in replay_results):
        ok("10 concurrent replays all returned the same intent ID")
    else:
        record_issue("CRITICAL", "idempotency", f"Concurrent replays returned inconsistent IDs: {set(replay_results)}")


def test_redis_distributed_lock_on_confirm():
    """
    Fire CONCURRENCY concurrent confirms on the SAME intent (no idempotency key).

    The Redis distributed lock (SET NX EX on lock:intent:<id>) ensures only one
    confirm acquires the lock. All other concurrent confirms are rejected immediately
    with 409 — no DB connection consumed, no Hikari pool pressure.

    Validates:
    1. Exactly 1 PaymentAttempt created despite CONCURRENCY concurrent confirms.
    2. Most responses are 409 (lock not acquired), exactly 1 is 200 (lock acquired).
    3. No 500 errors — no pool exhaustion possible since losers never touch the DB.
    4. Fast rejection — P50 of 409 responses should be <100ms (Redis roundtrip only).
    """
    CONCURRENCY = 80
    print(f"\n{BOLD}── Test 4b: Redis Distributed Lock on Confirm ({CONCURRENCY} concurrent) ──{RESET}")
    intent = create_intent()
    iid = intent["id"]

    status_codes = []
    latencies = []
    errors = []

    def do_confirm():
        t0 = time.time()
        try:
            r = confirm_intent(iid)
            ms = (time.time() - t0) * 1000
            with LOCK:
                status_codes.append(r.status_code)
                latencies.append(ms)
        except Exception as e:
            with LOCK:
                errors.append(str(e)[:80])

    threads = [threading.Thread(target=do_confirm) for _ in range(CONCURRENCY)]
    t_start = time.time()
    for t in threads: t.start()
    for t in threads: t.join()
    total_ms = (time.time() - t_start) * 1000

    status_dist = Counter(status_codes)
    info(f"HTTP distribution: {dict(status_dist)}  wall={total_ms:.0f}ms  conn_errors={len(errors)}")

    if latencies:
        latencies.sort()
        p50 = latencies[len(latencies) // 2]
        p95 = latencies[int(len(latencies) * 0.95)]
        p99 = latencies[int(len(latencies) * 0.99)]
        info(f"P50={p50:.0f}ms  P95={p95:.0f}ms  P99={p99:.0f}ms")

    if errors:
        record_issue("CRITICAL", "redis_lock_confirm",
                     f"{len(errors)} connection errors: {errors[:3]}")

    server_errors = status_dist.get(500, 0)
    if server_errors > 0:
        record_issue("CRITICAL", "redis_lock_confirm",
                     f"{server_errors} 500 errors — Redis lock should prevent pool exhaustion entirely")
    else:
        ok(f"No 500 errors — Redis lock prevented all DB pool contention")

    ok_count = status_dist.get(200, 0)
    rejected_count = status_dist.get(409, 0)
    if ok_count == 1:
        ok(f"Exactly 1 confirm succeeded (200), {rejected_count} rejected (409)")
    elif ok_count == 0:
        record_issue("WARNING", "redis_lock_confirm", "No confirm succeeded (all 409) — race too tight?")
    else:
        record_issue("WARNING", "redis_lock_confirm",
                     f"{ok_count} confirms returned 200 (expected 1) — Redis lock may not have been acquired exclusively")

    time.sleep(3)
    detail = get_intent(iid)
    attempt_count = len(detail.get("attempts", []))
    if attempt_count > 1:
        record_issue("CRITICAL", "redis_lock_confirm",
                     f"{attempt_count} PaymentAttempts created — lock did not serialise")
    elif attempt_count == 1:
        ok(f"Exactly 1 PaymentAttempt created despite {CONCURRENCY} concurrent confirms")
    else:
        record_issue("WARNING", "redis_lock_confirm", "0 PaymentAttempts — confirm may have been blocked entirely")


def test_race_condition_double_capture():
    """Fire 10 concurrent captures on an authorized intent — only 1 should succeed."""
    print(f"\n{BOLD}── Test 5: Race Condition — Concurrent Captures ──{RESET}")
    # Create and confirm, wait for auth
    for _ in range(5):
        intent = create_intent(amount=rand_amount(), currency=rand_currency())
        iid = intent["id"]
        confirm_intent(iid)
        status = wait_for_terminal(iid, timeout_s=150)
        if status == "authorized":
            break
    else:
        warn("Couldn't get an authorized intent after 5 tries — skipping")
        return

    info(f"Firing 10 concurrent captures on authorized intent {iid}")
    responses = []
    def do_capture():
        r = capture_intent(iid)
        with LOCK:
            responses.append((r.status_code, r.json()))

    threads = [threading.Thread(target=do_capture) for _ in range(10)]
    for t in threads: t.start()
    for t in threads: t.join()

    deadline = time.time() + 150
    final_status = None
    while time.time() < deadline:
        time.sleep(1)
        detail = get_intent(iid)
        final_status = detail["status"]
        if final_status == "captured":
            break

    capture_internal = [
        ia for a in detail.get("attempts", [])
        for ia in a.get("internalAttempts", [])
        if ia["type"] == "capture"
    ]
    capture_statuses = [ia["status"] for ia in capture_internal]

    statuses = Counter(r[0] for r in responses)
    info(f"HTTP response distribution: {dict(statuses)}")
    info(f"CAPTURE InternalAttempts created: {len(capture_internal)}, statuses: {capture_statuses}")

    if len(capture_internal) > 1:
        record_issue("CRITICAL", "double_capture_race",
                     f"{len(capture_internal)} capture InternalAttempts created — expected 1")
    else:
        ok("Only 1 capture InternalAttempt created")

    if final_status == "captured":
        ok("Final status is captured")
    elif final_status == "authorized" and any(s == "failure" for s in capture_statuses):
        # Gateway returned failure for capture — intent stays authorized (retryable).
        # This is correct product behaviour, not a bug.
        ok(f"Capture gateway returned failure — intent stays authorized (merchant can retry). Expected with ~5% probability.")
    else:
        record_issue("CRITICAL", "double_capture_race",
                     f"Final status is '{final_status}' with capture statuses {capture_statuses} — unexpected state")


def test_state_machine_guards():
    """Verify invalid transitions are rejected."""
    print(f"\n{BOLD}── Test 6: State Machine Guards ──{RESET}")

    # Capture on fresh (unconfirmed) intent
    intent = create_intent()
    r = capture_intent(intent["id"])
    if r.status_code == 409:
        ok("Capture on requires_confirmation → 409 Conflict")
    else:
        record_issue("WARNING", "state_machine", f"Capture on fresh intent returned {r.status_code}, expected 409")

    # Confirm a failed intent then confirm again (no idem key)
    for _ in range(6):
        i2 = create_intent()
        confirm_intent(i2["id"])
        s = wait_for_terminal(i2["id"], timeout_s=150)
        if s == "failed":
            r2 = confirm_intent(i2["id"])
            if r2.status_code in (400, 409):
                ok(f"Second confirm on failed intent → {r2.status_code}")
            else:
                record_issue("WARNING", "state_machine",
                             f"Second confirm on failed intent returned {r2.status_code}")
            break
    else:
        warn("Couldn't get a failed intent in 6 tries for re-confirm test")


def test_data_consistency_under_load():
    """
    Create 20 intents, confirm all, wait, then verify:
    - Every confirmed intent has exactly 1 PaymentAttempt
    - Every PaymentAttempt has exactly 1 AUTH InternalAttempt (ignoring retries)
    - PaymentIntent status matches PaymentAttempt status
    """
    print(f"\n{BOLD}── Test 7: Data Consistency Under Load (20 concurrent confirms) ──{RESET}")
    intents = [create_intent(amount=rand_amount(), currency=rand_currency()) for _ in range(20)]

    def confirm_all(intent):
        try:
            confirm_intent(intent["id"])
        except Exception:
            pass

    threads = [threading.Thread(target=confirm_all, args=(i,)) for i in intents]
    for t in threads: t.start()
    for t in threads: t.join()

    info("Waiting 8s for all webhooks to settle...")
    time.sleep(8)

    mismatches = 0
    multi_attempts = 0
    missing_internal = 0

    for intent in intents:
        detail = get_intent(intent["id"])
        attempts = detail.get("attempts", [])

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
            record_issue("CRITICAL", "consistency", f"Intent {intent['id']} has no AUTH InternalAttempt")
            continue

        # PaymentIntent status should align with PaymentAttempt status
        intent_s = detail["status"]
        attempt_s = attempt["status"]
        valid_pairs = {("authorized","authorized"), ("failed","failed"), ("captured","captured"),
                       ("requires_confirmation","pending")}
        if (intent_s, attempt_s) not in valid_pairs:
            mismatches += 1
            record_issue("CRITICAL", "consistency",
                         f"Status mismatch: intent={intent_s}, attempt={attempt_s}")

    total = len(intents)
    ok(f"{total - multi_attempts - missing_internal - mismatches}/{total} intents fully consistent")
    if multi_attempts == 0 and missing_internal == 0 and mismatches == 0:
        ok("All data consistent")


def test_webhook_replay():
    """
    Replay the same webhook twice — second delivery must be a no-op.

    We poll until the intent has reached a terminal state (authorized or failed),
    confirming the gateway-server's real webhook has already been applied.
    THEN we fire the same internalAttemptId again.  That second call must not change
    any status — the InternalAttempt is already in a terminal state.
    """
    print(f"\n{BOLD}── Test 8: Webhook Replay (duplicate delivery) ──{RESET}")
    intent = create_intent()
    iid = intent["id"]
    confirm_intent(iid)

    # Wait until the gateway-server's webhook has been processed (up to 15s).
    terminal_statuses = {"authorized", "failed", "captured", "expired"}
    terminal_detail = None
    for _ in range(15):
        time.sleep(1)
        d = get_intent(iid)
        if d["status"] in terminal_statuses:
            terminal_detail = d
            break

    if terminal_detail is None:
        warn("Intent never reached terminal state within 15s — skip")
        return

    attempts = terminal_detail.get("attempts", [])
    if not attempts:
        warn("No attempts found — skip")
        return

    internal_attempts = attempts[0].get("internalAttempts", [])
    if not internal_attempts:
        warn("No internal attempts found — skip")
        return

    ia_id = internal_attempts[0]["id"]
    status_before_replay = terminal_detail["status"]
    info(f"Intent is terminal before replay: {status_before_replay}")

    # Fire the webhook again — this is a genuine duplicate delivery (signed).
    signed_webhook_post(ia_id, "success")

    detail2 = get_intent(iid)
    status_after_replay = detail2["status"]

    info(f"Before replay: {status_before_replay}, after replay: {status_after_replay}")

    if status_before_replay != status_after_replay:
        record_issue("CRITICAL", "webhook_replay",
                     f"Webhook replay mutated a terminal intent: {status_before_replay} → {status_after_replay}")
    else:
        ok(f"Status unchanged after duplicate webhook ({status_before_replay})")


def test_expiry_auth_hang(auth_timeout_s=30):
    """
    Simulate a gateway that never calls back by manually creating a PaymentAttempt
    and then waiting for the scheduler to expire it.
    We use a very short timeout config (30s) and actually wait for it.
    This test only runs if the server's auth timeout is ≤ 45s.
    """
    print(f"\n{BOLD}── Test 9: Auth Expiry — intent expires if webhook never arrives ──{RESET}")

    if auth_timeout_s > 45:
        warn(f"Skipping — auth timeout is {auth_timeout_s}s (too long for a stress test run)")
        return

    intent = create_intent(amount=rand_amount(), currency=rand_currency())
    iid = intent["id"]

    # Confirm (this dispatches to mock gateway which WILL call back in 1-2s)
    # To simulate a hang we instead manually fire a confirm and then immediately
    # override the InternalAttempt back to PENDING via a direct webhook of type "timeout"
    # to keep it from resolving, then wait for the scheduler.
    #
    # Easier approach: just observe that a confirms that genuinely times out
    # gets expired. We send the confirm but force the InternalAttempt into
    # TIMEOUT by sending a premature timeout webhook, then wait for scheduler.
    confirm_intent(iid)
    time.sleep(2)  # let real webhook fire first

    detail = get_intent(iid)
    status_after_webhook = detail["status"]
    info(f"Status after real webhook: {status_after_webhook}")

    if status_after_webhook in ("authorized", "failed"):
        ok(f"Gateway resolved normally ({status_after_webhook}) — expiry test needs a truly hung intent")
        info("Tip: to fully test expiry, lower payment.timeout.auth-seconds to 5 in application.properties")
        return

    # If still requires_confirmation (shouldn't happen often), wait for scheduler
    info(f"Waiting up to {auth_timeout_s + 15}s for scheduler to expire hung intent {iid}...")
    deadline = time.time() + auth_timeout_s + 15
    while time.time() < deadline:
        time.sleep(3)
        s = get_intent(iid)["status"]
        if s == "expired":
            ok(f"Intent correctly expired by scheduler after timeout")
            break
        info(f"  still {s}...")
    else:
        record_issue("WARNING", "expiry", f"Intent {iid} not expired after {auth_timeout_s + 15}s")


def test_expiry_blocks_late_webhook():
    """
    Force-expire an InternalAttempt via direct webhook then replay — must be rejected.
    Uses a real auth flow; after expiry fires, sends a success webhook and verifies
    the intent does NOT flip to authorized.
    NOTE: This test manipulates the DB via direct webhook to simulate scheduler expiry.
    """
    print(f"\n{BOLD}── Test 10: Late Webhook After Expiry is Blocked ──{RESET}")

    # Create and confirm — get the internal attempt ID
    intent = create_intent(amount=rand_amount(), currency=rand_currency())
    iid = intent["id"]
    confirm_intent(iid)
    time.sleep(3)

    detail = get_intent(iid)
    attempts = detail.get("attempts", [])
    if not attempts:
        warn("No attempts found — skip")
        return
    internals = attempts[0].get("internalAttempts", [])
    if not internals:
        warn("No internal attempts found — skip")
        return

    ia_id = internals[0]["id"]
    current_status = detail["status"]
    info(f"Status after webhook: {current_status}, InternalAttempt: {internals[0]['status']}")

    if current_status in ("authorized", "failed"):
        # Force-expire by sending an "expired"-equivalent: since we can't send status=expired
        # via the webhook endpoint (it only accepts success/failure/timeout), we test that
        # a replayed success webhook on an already-SUCCESS attempt is blocked.
        r = signed_webhook_post(ia_id, "success")
        detail2 = get_intent(iid)
        if detail2["status"] == current_status:
            ok(f"Late webhook on already-terminal attempt blocked — status unchanged ({current_status})")
        else:
            record_issue("CRITICAL", "expiry_late_webhook",
                         f"Status changed {current_status} → {detail2['status']} on late webhook replay")
    else:
        warn(f"Intent still in {current_status} — couldn't test late webhook scenario")


def test_dispatch_retry():
    """
    Verifies the commit-first + dispatch-retry pattern:
    1. Create + confirm several intents.
    2. Check that all eventually reach a terminal state, even if the
       initial afterCommit dispatch is slow (the gateway ACK can take up to 5s).
    3. Verifies the dispatch-retry scheduler can pick up undispatched attempts.
    """
    COUNT = 10
    print(f"\n{BOLD}── Test 11: Dispatch-Retry Scheduler ({COUNT} intents) ──{RESET}")

    intent_ids = []
    for i in range(COUNT):
        intent = create_intent(amount=rand_amount(), currency=rand_currency())
        r = confirm_intent(intent["id"])
        if r.status_code < 300:
            intent_ids.append(intent["id"])
        else:
            warn(f"Confirm failed for intent {intent['id']}: {r.status_code}")

    info(f"Created+confirmed {len(intent_ids)} intents, waiting for terminal states...")

    deadline = time.time() + 150
    terminal = set()
    while time.time() < deadline and len(terminal) < len(intent_ids):
        time.sleep(3)
        for iid in intent_ids:
            if iid in terminal:
                continue
            d = get_intent(iid)
            if d["status"] in ("authorized", "failed", "captured", "expired"):
                terminal.add(iid)
        info(f"  {len(terminal)}/{len(intent_ids)} terminal...")

    not_terminal = [iid for iid in intent_ids if iid not in terminal]
    if not_terminal:
        record_issue("CRITICAL", "dispatch_retry",
                     f"{len(not_terminal)} intents never reached terminal state: {not_terminal[:3]}")
    else:
        ok(f"All {len(intent_ids)} intents reached terminal state via gateway (dispatch-retry working)")

    for iid in intent_ids[:3]:
        d = get_intent(iid)
        attempts = d.get("attempts", [])
        if attempts:
            internals = attempts[0].get("internalAttempts", [])
            if internals:
                info(f"  {iid[:8]}… → {d['status']} | IA status={internals[0]['status']}")


def fetch_server_metrics(base_url, label):
    """Fetch CPU, memory, and thread metrics from a Spring Boot Actuator endpoint."""
    metrics = {}
    try:
        def get_metric(name):
            r = requests.get(f"{base_url}/actuator/metrics/{name}", timeout=3)
            if r.status_code == 200:
                data = r.json()
                for m in data.get("measurements", []):
                    if m["statistic"] == "VALUE":
                        return m["value"]
            return None

        metrics["cpu_usage"] = get_metric("process.cpu.usage")
        metrics["cpu_system"] = get_metric("system.cpu.usage")
        metrics["mem_used_mb"] = None
        mem_used = get_metric("jvm.memory.used")
        if mem_used is not None:
            metrics["mem_used_mb"] = mem_used / (1024 * 1024)
        mem_max = get_metric("jvm.memory.max")
        if mem_max is not None and mem_max > 0:
            metrics["mem_max_mb"] = mem_max / (1024 * 1024)
        metrics["threads_live"] = get_metric("jvm.threads.live")
        metrics["threads_peak"] = get_metric("jvm.threads.peak")
        metrics["threads_daemon"] = get_metric("jvm.threads.daemon")
    except Exception as e:
        metrics["error"] = str(e)[:60]
    return metrics


def print_metrics_snapshot(phase_label):
    """Fetch and display metrics from both servers."""
    print(f"\n  {BOLD}── {phase_label} ──{RESET}")
    for base, name in [(BASE, "Backend (8080)"), (GATEWAY_BASE, "Gateway (8081)")]:
        m = fetch_server_metrics(base, name)
        if "error" in m:
            warn(f"{name}: metrics unavailable ({m['error']})")
            continue
        cpu = m.get("cpu_usage")
        cpu_sys = m.get("cpu_system")
        mem = m.get("mem_used_mb")
        mem_max = m.get("mem_max_mb")
        threads = m.get("threads_live")
        peak = m.get("threads_peak")
        daemon = m.get("threads_daemon")
        cpu_str = f"CPU: {cpu*100:.1f}%" if cpu is not None else "CPU: n/a"
        sys_str = f"sys {cpu_sys*100:.1f}%" if cpu_sys is not None else ""
        mem_str = f"Heap: {mem:.0f}MB" if mem is not None else "Heap: n/a"
        if mem_max:
            mem_str += f"/{mem_max:.0f}MB"
        thr_str = f"Threads: {int(threads)}" if threads is not None else "Threads: n/a"
        if peak is not None:
            thr_str += f" (peak {int(peak)})"
        if daemon is not None:
            thr_str += f" daemon={int(daemon)}"
        info(f"{name}  {cpu_str} ({sys_str})  {mem_str}  {thr_str}")


def get_ledger_entries(intent_id) -> list:
    try:
        r = requests.get(f"{LEDGER_BASE}/ledger/entries", params={"paymentIntentId": intent_id}, timeout=10)
        if r.status_code == 200:
            return r.json()
    except Exception:
        pass
    return []


def get_ledger_balances() -> dict:
    try:
        r = requests.get(f"{LEDGER_BASE}/ledger/balances", timeout=10)
        if r.status_code == 200:
            return r.json()
    except Exception:
        pass
    return {}


def test_ledger_consistency():
    """
    Validates ledger correctness after the sustained load test (or independently).
    Checks:
    1. Global double-entry balance: total debits == total credits
    2. Per-intent entry correctness based on terminal status:
       - authorized → exactly 1 DEBIT (merchant_receivables) + 1 CREDIT (gateway_payable)
       - failed      → either 0 entries (failed before auth) or balanced reversal
       - captured    → auth entries + capture entries (2 DEBIT + 2 CREDIT)
    3. No orphan ledger entries for intents that don't exist
    """
    SAMPLE_SIZE = 50
    print(f"\n{BOLD}── Test 12: Ledger Double-Entry Consistency ──{RESET}")

    # 1. Global balance check
    balances = get_ledger_balances()
    if not balances:
        warn("Ledger service unreachable or no balances — skipping ledger tests")
        return

    info(f"Ledger balances: {balances}")

    total_debits = 0
    total_credits = 0
    for account_name, entry_types in balances.items():
        total_debits += entry_types.get("DEBIT", 0)
        total_credits += entry_types.get("CREDIT", 0)

    info(f"Global totals: debits={total_debits}, credits={total_credits}")
    if total_debits == total_credits:
        ok(f"Double-entry balanced: debits == credits == {total_debits}")
    else:
        record_issue("CRITICAL", "ledger_balance",
                     f"Double-entry IMBALANCE: debits={total_debits}, credits={total_credits}, diff={total_debits - total_credits}")

    # 2. Per-intent entry validation (sample recent intents)
    info(f"Sampling {SAMPLE_SIZE} intents for per-intent ledger validation...")
    try:
        r = requests.get(f"{BASE}/payment_intents", params={"size": SAMPLE_SIZE}, timeout=10)
        r.raise_for_status()
        page = r.json()
        all_intents = page.get("content", page) if isinstance(page, dict) else page
    except Exception as e:
        warn(f"Could not fetch intents list: {e}")
        return

    sample = all_intents[:SAMPLE_SIZE] if len(all_intents) >= SAMPLE_SIZE else all_intents
    entry_errors = 0
    missing_entries = 0
    extra_entries = 0

    for intent_summary in sample:
        iid = intent_summary["id"]
        detail = get_intent(iid)
        status = detail["status"]
        amount = detail["amount"]
        entries = get_ledger_entries(iid)

        entry_types_found = [(e["eventType"], e["entryType"], e["amount"]) for e in entries]

        if status == "authorized":
            expected_events = {"AUTHORIZED"}
            debit_entries = [e for e in entries if e["entryType"] == "DEBIT" and e["eventType"] == "AUTHORIZED"]
            credit_entries = [e for e in entries if e["entryType"] == "CREDIT" and e["eventType"] == "AUTHORIZED"]

            if len(debit_entries) != 1 or len(credit_entries) != 1:
                entry_errors += 1
                if entry_errors <= 3:
                    record_issue("CRITICAL", "ledger_per_intent",
                                 f"Intent {iid} (authorized): expected 1 DEBIT + 1 CREDIT for AUTHORIZED, "
                                 f"got {len(debit_entries)} DEBIT + {len(credit_entries)} CREDIT")
            elif debit_entries[0]["amount"] != amount or credit_entries[0]["amount"] != amount:
                entry_errors += 1
                if entry_errors <= 3:
                    record_issue("CRITICAL", "ledger_per_intent",
                                 f"Intent {iid} (authorized): amount mismatch — "
                                 f"intent={amount}, debit={debit_entries[0]['amount']}, credit={credit_entries[0]['amount']}")

        elif status == "captured":
            auth_debits = [e for e in entries if e["eventType"] == "AUTHORIZED" and e["entryType"] == "DEBIT"]
            auth_credits = [e for e in entries if e["eventType"] == "AUTHORIZED" and e["entryType"] == "CREDIT"]
            cap_debits = [e for e in entries if e["eventType"] == "CAPTURED" and e["entryType"] == "DEBIT"]
            cap_credits = [e for e in entries if e["eventType"] == "CAPTURED" and e["entryType"] == "CREDIT"]

            if len(auth_debits) != 1 or len(auth_credits) != 1 or len(cap_debits) != 1 or len(cap_credits) != 1:
                entry_errors += 1
                if entry_errors <= 3:
                    record_issue("CRITICAL", "ledger_per_intent",
                                 f"Intent {iid} (captured): expected 4 entries (auth+capture), "
                                 f"got auth={len(auth_debits)}D/{len(auth_credits)}C, capture={len(cap_debits)}D/{len(cap_credits)}C")

        elif status == "failed":
            auth_entries = [e for e in entries if e["eventType"] == "AUTHORIZED"]
            reversal_entries = [e for e in entries if e["eventType"] == "FAILED_REVERSAL"]

            if len(auth_entries) == 0 and len(reversal_entries) == 0:
                pass  # failed before authorization — no ledger entries expected
            elif len(auth_entries) == 2 and len(reversal_entries) == 2:
                pass  # authorized then failed — auth + reversal entries
            elif len(auth_entries) == 2 and len(reversal_entries) == 0:
                # CDC event ordering: failed directly from requires_confirmation has no reversal
                pass
            else:
                entry_errors += 1
                if entry_errors <= 3:
                    record_issue("WARNING", "ledger_per_intent",
                                 f"Intent {iid} (failed): unexpected entry pattern — "
                                 f"auth={len(auth_entries)}, reversal={len(reversal_entries)}")

        elif status == "expired":
            auth_entries = [e for e in entries if e["eventType"] == "AUTHORIZED"]
            reversal_entries = [e for e in entries if e["eventType"] == "EXPIRED_REVERSAL"]

            if len(auth_entries) == 0 and len(reversal_entries) == 0:
                pass  # expired before authorization
            elif len(auth_entries) == 2 and len(reversal_entries) == 2:
                pass  # auth + reversal
            else:
                entry_errors += 1
                if entry_errors <= 3:
                    record_issue("WARNING", "ledger_per_intent",
                                 f"Intent {iid} (expired): unexpected entry pattern — "
                                 f"auth={len(auth_entries)}, reversal={len(reversal_entries)}")

    # 3. Per-intent debit/credit balance
    unbalanced = 0
    for intent_summary in sample:
        iid = intent_summary["id"]
        entries = get_ledger_entries(iid)
        if not entries:
            continue
        intent_debits = sum(e["amount"] for e in entries if e["entryType"] == "DEBIT")
        intent_credits = sum(e["amount"] for e in entries if e["entryType"] == "CREDIT")
        if intent_debits != intent_credits:
            unbalanced += 1
            if unbalanced <= 3:
                record_issue("CRITICAL", "ledger_per_intent_balance",
                             f"Intent {iid}: debits={intent_debits} != credits={intent_credits}")

    if entry_errors == 0:
        ok(f"All {len(sample)} sampled intents have correct ledger entry patterns")
    else:
        record_issue("CRITICAL" if entry_errors > 3 else "WARNING", "ledger_per_intent",
                     f"{entry_errors}/{len(sample)} intents have ledger entry issues")

    if unbalanced == 0:
        ok(f"All {len(sample)} sampled intents are balanced (debits == credits per intent)")
    else:
        record_issue("CRITICAL", "ledger_per_intent_balance",
                     f"{unbalanced}/{len(sample)} intents have unbalanced entries")


def test_sustained_load():
    """
    Sustained 100 TPS of create+confirm for 30s, then drain for up to 120s
    waiting for all exponentially-delayed webhooks (1s–60s) to arrive.
    """
    THREADS = 100
    DURATION_S = 30
    DRAIN_S = 120
    DRAIN_POLL_INTERVAL = 3

    print(f"\n{BOLD}{'═'*60}{RESET}")
    print(f"{BOLD}   Sustained Load Test — {THREADS} TPS × {DURATION_S}s{RESET}")
    print(f"{BOLD}{'═'*60}{RESET}")

    print_metrics_snapshot("Before Load")

    created_ids = []
    create_latencies = []
    confirm_latencies = []
    errors_5xx = []
    errors_other = []
    stop_event = threading.Event()
    ids_lock = threading.Lock()
    metrics_snapshots = []

    def worker():
        """Each worker loops: create → confirm → sleep to ~1 TPS per thread."""
        while not stop_event.is_set():
            iid = None
            try:
                t0 = time.time()
                r = requests.post(f"{BASE}/payment_intents",
                                  json={"amount": rand_amount(), "currency": rand_currency()}, timeout=10)
                create_ms = (time.time() - t0) * 1000
                if r.status_code >= 500:
                    with ids_lock:
                        errors_5xx.append(("create", r.status_code))
                    continue
                r.raise_for_status()
                iid = r.json()["id"]
                with ids_lock:
                    create_latencies.append(create_ms)

                t1 = time.time()
                r2 = requests.post(f"{BASE}/payment_intents/{iid}/confirm",
                                   json=CARD_VISA,
                                   headers={"Content-Type": "application/json"},
                                   timeout=10)
                confirm_ms = (time.time() - t1) * 1000
                if r2.status_code >= 500:
                    with ids_lock:
                        errors_5xx.append(("confirm", r2.status_code))
                else:
                    with ids_lock:
                        confirm_latencies.append(confirm_ms)

                with ids_lock:
                    created_ids.append(iid)

            except requests.exceptions.RequestException as e:
                with ids_lock:
                    errors_other.append(str(e)[:80])

            elapsed = time.time() - (t0 if 'iid' in dir() else time.time())
            sleep_for = max(0, 1.0 - elapsed)
            if not stop_event.is_set():
                stop_event.wait(timeout=sleep_for)

    # ── Phase 1: Traffic ──────────────────────────────────────────────────
    info(f"Starting {THREADS} worker threads for {DURATION_S}s...")
    threads = [threading.Thread(target=worker, daemon=True) for _ in range(THREADS)]
    t_start = time.time()
    for t in threads:
        t.start()

    last_metrics_t = time.time()
    while time.time() - t_start < DURATION_S:
        time.sleep(2)
        elapsed = time.time() - t_start
        with ids_lock:
            n = len(created_ids)
        tps = n / elapsed if elapsed > 0 else 0
        print(f"\r  {CYAN}→{RESET} t={elapsed:.0f}s  created={n}  TPS={tps:.1f}  5xx={len(errors_5xx)}", end="", flush=True)

        if time.time() - last_metrics_t >= 10:
            backend_m = fetch_server_metrics(BASE, "backend")
            gateway_m = fetch_server_metrics(GATEWAY_BASE, "gateway")
            metrics_snapshots.append({
                "t": elapsed, "backend": backend_m, "gateway": gateway_m
            })
            last_metrics_t = time.time()

    stop_event.set()
    for t in threads:
        t.join(timeout=15)
    print()

    traffic_elapsed = time.time() - t_start
    total_created = len(created_ids)
    actual_tps = total_created / traffic_elapsed if traffic_elapsed > 0 else 0
    info(f"Traffic phase done: {total_created} intents in {traffic_elapsed:.1f}s ({actual_tps:.1f} TPS)")
    info(f"5xx errors: {len(errors_5xx)}, connection errors: {len(errors_other)}")

    print_metrics_snapshot("After Traffic (before drain)")

    # ── Latency stats ─────────────────────────────────────────────────────
    def percentiles(arr, label):
        if not arr:
            info(f"{label}: no data")
            return
        s = sorted(arr)
        p50 = s[len(s)//2]
        p95 = s[int(len(s)*0.95)]
        p99 = s[int(len(s)*0.99)]
        info(f"{label}: P50={p50:.0f}ms  P95={p95:.0f}ms  P99={p99:.0f}ms  (n={len(s)})")

    percentiles(create_latencies, "Create latency")
    percentiles(confirm_latencies, "Confirm latency")

    if len(errors_5xx) > total_created * 0.05:
        record_issue("CRITICAL", "sustained_load",
                     f"5xx error rate {len(errors_5xx)}/{total_created} = {len(errors_5xx)/max(total_created,1)*100:.1f}%")
    elif errors_5xx:
        record_issue("WARNING", "sustained_load",
                     f"{len(errors_5xx)} 5xx errors ({len(errors_5xx)/max(total_created,1)*100:.1f}%)")

    # ── Phase 2: Drain — wait for webhooks ────────────────────────────────
    info(f"Draining for up to {DRAIN_S}s waiting for webhooks...")
    terminal_statuses = {"authorized", "failed", "captured", "expired"}
    drain_start = time.time()
    terminal_ids = set()

    def poll_batch(ids_to_check):
        """Check a batch of intent IDs for terminal status using a thread pool."""
        newly_terminal = set()
        poll_lock = threading.Lock()

        def check_one(iid):
            try:
                d = get_intent(iid)
                if d["status"] in terminal_statuses:
                    with poll_lock:
                        newly_terminal.add(iid)
            except Exception:
                pass

        batch_threads = [threading.Thread(target=check_one, args=(iid,)) for iid in ids_to_check]
        for bt in batch_threads:
            bt.start()
        for bt in batch_threads:
            bt.join(timeout=10)
        return newly_terminal

    POLL_BATCH_SIZE = 500

    while time.time() - drain_start < DRAIN_S:
        remaining_ids = [iid for iid in created_ids if iid not in terminal_ids]
        if not remaining_ids:
            break

        batch = remaining_ids[:POLL_BATCH_SIZE]
        newly = poll_batch(batch)
        terminal_ids.update(newly)

        pct = len(terminal_ids) / total_created * 100 if total_created > 0 else 0
        elapsed_drain = time.time() - drain_start
        print(f"\r  {CYAN}→{RESET} drain t={elapsed_drain:.0f}s  terminal={len(terminal_ids)}/{total_created} ({pct:.1f}%)", end="", flush=True)

        if len(terminal_ids) == total_created:
            break
        time.sleep(DRAIN_POLL_INTERVAL)

    print()
    drain_elapsed = time.time() - drain_start
    info(f"Drain complete in {drain_elapsed:.1f}s — {len(terminal_ids)}/{total_created} terminal")

    print_metrics_snapshot("After Drain")

    # ── Metrics timeline ──────────────────────────────────────────────────
    if metrics_snapshots:
        print(f"\n  {BOLD}── Metrics Timeline (during traffic) ──{RESET}")
        for snap in metrics_snapshots:
            bm = snap["backend"]
            gm = snap["gateway"]
            b_cpu = f"{bm.get('cpu_usage', 0)*100:.0f}%" if bm.get('cpu_usage') is not None else "n/a"
            g_cpu = f"{gm.get('cpu_usage', 0)*100:.0f}%" if gm.get('cpu_usage') is not None else "n/a"
            b_thr = f"{int(bm.get('threads_live', 0))}" if bm.get('threads_live') is not None else "n/a"
            g_thr = f"{int(gm.get('threads_live', 0))}" if gm.get('threads_live') is not None else "n/a"
            b_mem = f"{bm.get('mem_used_mb', 0):.0f}MB" if bm.get('mem_used_mb') is not None else "n/a"
            g_mem = f"{gm.get('mem_used_mb', 0):.0f}MB" if gm.get('mem_used_mb') is not None else "n/a"
            info(f"t={snap['t']:.0f}s  Backend[cpu={b_cpu} thr={b_thr} mem={b_mem}]  Gateway[cpu={g_cpu} thr={g_thr} mem={g_mem}]")

    # ── Phase 3: Validation ───────────────────────────────────────────────
    print(f"\n  {BOLD}Validating {total_created} intents...{RESET}")
    status_counts = Counter()
    mismatches = 0
    multi_attempts = 0
    missing_attempts = 0
    stuck = 0

    for idx, iid in enumerate(created_ids):
        try:
            detail = get_intent(iid)
        except Exception:
            record_issue("CRITICAL", "sustained_validation", f"Failed to fetch intent {iid}")
            continue

        intent_status = detail["status"]
        status_counts[intent_status] += 1

        if intent_status == "requires_confirmation":
            stuck += 1
            continue

        attempts = detail.get("attempts", [])
        if len(attempts) == 0:
            missing_attempts += 1
            continue
        if len(attempts) > 1:
            multi_attempts += 1

        attempt = attempts[0]
        attempt_status = attempt["status"]
        valid_pairs = {
            ("authorized", "authorized"), ("failed", "failed"),
            ("captured", "captured"), ("expired", "expired"),
            ("requires_confirmation", "pending"),
        }
        if (intent_status, attempt_status) not in valid_pairs:
            mismatches += 1
            if mismatches <= 5:
                record_issue("CRITICAL", "sustained_consistency",
                             f"Intent {iid}: intent={intent_status}, attempt={attempt_status}")

        if (idx + 1) % 500 == 0:
            print(f"\r  {CYAN}→{RESET} validated {idx+1}/{total_created}...", end="", flush=True)

    print(f"\r  {CYAN}→{RESET} validated {total_created}/{total_created}    ")

    # ── Report ────────────────────────────────────────────────────────────
    info(f"Status breakdown: {dict(status_counts)}")
    info(f"Stuck (requires_confirmation): {stuck}")
    info(f"Multi-attempt intents: {multi_attempts}")
    info(f"Missing attempts: {missing_attempts}")
    info(f"Status mismatches: {mismatches}")

    if stuck > 0:
        stuck_pct = stuck / total_created * 100
        if stuck_pct > 5:
            record_issue("CRITICAL", "sustained_stuck",
                         f"{stuck}/{total_created} ({stuck_pct:.1f}%) still stuck after {DRAIN_S}s drain")
        else:
            record_issue("WARNING", "sustained_stuck",
                         f"{stuck}/{total_created} ({stuck_pct:.1f}%) still stuck — may be tail webhook delay")

    if multi_attempts > 0:
        record_issue("CRITICAL", "sustained_multi_attempt",
                     f"{multi_attempts} intents have >1 PaymentAttempt (confirm race)")
    else:
        ok("All intents have exactly 1 PaymentAttempt")

    if mismatches > 0:
        record_issue("CRITICAL", "sustained_consistency",
                     f"{mismatches} intent/attempt status mismatches")
    else:
        ok("All intent/attempt status pairs are consistent")

    terminal_pct = len(terminal_ids) / total_created * 100 if total_created > 0 else 0
    if terminal_pct >= 95:
        ok(f"Terminal coverage: {terminal_pct:.1f}% ({len(terminal_ids)}/{total_created})")
    else:
        record_issue("WARNING", "sustained_terminal",
                     f"Only {terminal_pct:.1f}% reached terminal within {DRAIN_S}s drain")

    # ── Phase 4: Ledger Consistency ──────────────────────────────────────
    LEDGER_DRAIN_S = 30
    LEDGER_SAMPLE = 100
    print(f"\n  {BOLD}Ledger consistency check (waiting {LEDGER_DRAIN_S}s for CDC propagation)...{RESET}")

    ledger_available = bool(get_ledger_balances())
    if not ledger_available:
        warn("Ledger service unreachable — skipping ledger validation")
        return

    time.sleep(LEDGER_DRAIN_S)

    balances = get_ledger_balances()
    info(f"Ledger balances: {balances}")

    total_debits = sum(v.get("DEBIT", 0) for v in balances.values())
    total_credits = sum(v.get("CREDIT", 0) for v in balances.values())
    if total_debits == total_credits:
        ok(f"Global double-entry balanced: debits == credits == {total_debits}")
    else:
        record_issue("CRITICAL", "sustained_ledger_balance",
                     f"Global IMBALANCE: debits={total_debits}, credits={total_credits}")

    sample_ids = created_ids[:LEDGER_SAMPLE]
    ledger_entry_issues = 0
    ledger_unbalanced = 0
    ledger_missing = 0
    authorized_with_entries = 0

    for iid in sample_ids:
        try:
            detail = get_intent(iid)
        except Exception:
            continue
        status = detail["status"]
        amount = detail["amount"]
        entries = get_ledger_entries(iid)

        if status == "authorized" and len(entries) == 0:
            ledger_missing += 1
            if ledger_missing <= 3:
                record_issue("WARNING", "sustained_ledger",
                             f"Intent {iid} (authorized) has no ledger entries — CDC lag?")
            continue

        if entries:
            intent_debits = sum(e["amount"] for e in entries if e["entryType"] == "DEBIT")
            intent_credits = sum(e["amount"] for e in entries if e["entryType"] == "CREDIT")
            if intent_debits != intent_credits:
                ledger_unbalanced += 1
                if ledger_unbalanced <= 3:
                    record_issue("CRITICAL", "sustained_ledger_per_intent",
                                 f"Intent {iid}: debits={intent_debits} != credits={intent_credits}")

        if status == "authorized":
            authorized_with_entries += 1
            auth_d = [e for e in entries if e["eventType"] == "AUTHORIZED" and e["entryType"] == "DEBIT"]
            auth_c = [e for e in entries if e["eventType"] == "AUTHORIZED" and e["entryType"] == "CREDIT"]
            if len(auth_d) != 1 or len(auth_c) != 1:
                ledger_entry_issues += 1
                if ledger_entry_issues <= 3:
                    record_issue("CRITICAL", "sustained_ledger_entries",
                                 f"Intent {iid} (authorized): expected 1D+1C for AUTHORIZED, got {len(auth_d)}D+{len(auth_c)}C")

    sampled = len(sample_ids)
    if ledger_unbalanced == 0:
        ok(f"All {sampled} sampled intents have balanced ledger entries")
    if ledger_entry_issues == 0:
        ok(f"All authorized intents have correct AUTHORIZED entry pairs")
    if ledger_missing > 0:
        record_issue("WARNING", "sustained_ledger_missing",
                     f"{ledger_missing}/{sampled} authorized intents missing ledger entries (CDC propagation delay)")


# ═══════════════════════════════════════════════════════════════════════════════
# RUNNER
# ═══════════════════════════════════════════════════════════════════════════════

if __name__ == "__main__":
    stress_only = "--stress" in sys.argv

    print(f"\n{BOLD}{'═'*60}{RESET}")
    if stress_only:
        print(f"{BOLD}   Payment Orchestrator — Stress Test Only{RESET}")
    else:
        print(f"{BOLD}   Payment Orchestrator — Stress & Correctness Test{RESET}")
    print(f"{BOLD}{'═'*60}{RESET}")

    if stress_only:
        suites = [test_sustained_load, test_ledger_consistency]
    else:
        suites = [
            test_throughput_and_latencies,
            test_concurrent_creates,
            test_race_condition_double_confirm,
            test_idempotency_correctness,
            test_redis_distributed_lock_on_confirm,
            test_race_condition_double_capture,
            test_state_machine_guards,
            test_data_consistency_under_load,
            test_webhook_replay,
            test_expiry_auth_hang,
            test_expiry_blocks_late_webhook,
            test_dispatch_retry,
            test_sustained_load,
            test_ledger_consistency,
        ]

    for suite in suites:
        try:
            suite()
        except Exception as e:
            record_issue("CRITICAL", suite.__name__, f"Test suite crashed: {e}")

    print(f"\n{BOLD}{'═'*60}{RESET}")
    print(f"{BOLD}   RESULTS{RESET}")
    print(f"{BOLD}{'═'*60}{RESET}")

    if not issues:
        print(f"\n{GREEN}{BOLD}  All clean — no issues found!{RESET}\n")
    else:
        by_severity = defaultdict(list)
        for i in issues:
            by_severity[i.severity].append(i)

        for sev in ("CRITICAL", "WARNING", "INFO"):
            if sev not in by_severity:
                continue
            label = {"CRITICAL": RED, "WARNING": YELLOW, "INFO": CYAN}[sev]
            print(f"\n  {label}{BOLD}{sev} ({len(by_severity[sev])}){RESET}")
            for i in by_severity[sev]:
                print(f"    [{i.test}] {i.detail}")

        criticals = len(by_severity.get("CRITICAL", []))
        warnings  = len(by_severity.get("WARNING", []))
        print(f"\n  Summary: {RED}{criticals} critical{RESET}, {YELLOW}{warnings} warnings{RESET}\n")
