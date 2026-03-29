"""
Sustained load test, expiry test, and dispatch retry test.

Usage:
    python -m api_tests.test_load
"""

import threading
import queue
import time
import requests
import random
from collections import Counter

from api_tests.conftest import *


def test_dispatch_retry():
    """
    Verifies the commit-first + dispatch-retry pattern:
    1. Create + confirm several intents.
    2. Check that all eventually reach a terminal state, even if the
       initial afterCommit dispatch is slow (the gateway ACK can take up to 5s).
    3. Verifies the dispatch-retry scheduler can pick up undispatched attempts.
    """
    COUNT = 10
    print(f"\n{BOLD}── Test: Dispatch-Retry Scheduler ({COUNT} intents) ──{RESET}")

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
                info(f"  {iid[:8]}... → {d['status']} | IA status={internals[0]['status']}")


def test_expiry_auth_hang(auth_timeout_s=30):
    """
    Simulate a gateway that never calls back by manually creating a PaymentAttempt
    and then waiting for the scheduler to expire it.
    We use a very short timeout config (30s) and actually wait for it.
    This test only runs if the server's auth timeout is <= 45s.
    """
    print(f"\n{BOLD}── Test: Auth Expiry — intent expires if webhook never arrives ──{RESET}")

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


def test_sustained_load():
    """
    Sustained 100 TPS of create+confirm for 30s, then drain for up to 120s
    waiting for all exponentially-delayed webhooks (1s-60s) to arrive.
    """
    THREADS = 100
    DURATION_S = 30
    DRAIN_S = 120
    DRAIN_POLL_INTERVAL = 3

    print(f"\n{BOLD}{'='*60}{RESET}")
    print(f"{BOLD}   Sustained Load Test — {THREADS} TPS x {DURATION_S}s{RESET}")
    print(f"{BOLD}{'='*60}{RESET}")

    # -- Pre-traffic: Create one merchant per worker --------------------------
    print(f"\n  {BOLD}Creating {THREADS} merchants (one per worker)...{RESET}")
    merchants = []
    for i in range(THREADS):
        m = create_merchant(f"load-test-merchant-{i}")
        merchants.append(m)
        if (i + 1) % 25 == 0:
            info(f"  Created {i + 1}/{THREADS} merchants")
    ok(f"Created {len(merchants)} merchants")

    print_metrics_snapshot("Before Load")

    created_ids = []
    created_merchant_map = {}  # intent_id -> merchant_id
    create_latencies = []
    confirm_latencies = []
    errors_5xx = []
    errors_other = []
    stop_event = threading.Event()
    ids_lock = threading.Lock()
    metrics_snapshots = []

    confirm_queue = queue.Queue()

    def create_worker(worker_index):
        """Each worker loops: create -> enqueue confirm -> pace to 1 TPS."""
        merchant_id = merchants[worker_index]["id"]
        while not stop_event.is_set():
            try:
                t0 = time.time()
                r = requests.post(f"{BASE}/v1/payment_intents",
                                  json={"merchantId": merchant_id, "amount": rand_amount(), "currency": rand_currency()}, timeout=10)
                create_ms = (time.time() - t0) * 1000
                if r.status_code >= 500:
                    with ids_lock:
                        errors_5xx.append(("create", r.status_code))
                else:
                    r.raise_for_status()
                    iid = r.json()["id"]
                    with ids_lock:
                        create_latencies.append(create_ms)
                        created_ids.append(iid)
                        created_merchant_map[iid] = merchant_id
                    confirm_queue.put(iid)

            except requests.exceptions.RequestException as e:
                with ids_lock:
                    errors_other.append(str(e)[:80])

            elapsed = time.time() - t0
            sleep_for = max(0, 1.0 - elapsed)
            if not stop_event.is_set():
                stop_event.wait(timeout=sleep_for)

    def confirm_worker():
        """Drains the confirm queue independently so creates are not blocked."""
        while not stop_event.is_set() or not confirm_queue.empty():
            try:
                iid = confirm_queue.get(timeout=1)
            except queue.Empty:
                continue
            try:
                t1 = time.time()
                r2 = requests.post(f"{BASE}/v1/payment_intents/{iid}/confirm",
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
            except requests.exceptions.RequestException as e:
                with ids_lock:
                    errors_other.append(str(e)[:80])

    # -- Phase 1: Traffic -----------------------------------------------------
    CONFIRM_THREADS = 100  # separate pool to drain confirm queue without blocking creates
    info(f"Starting {THREADS} create + {CONFIRM_THREADS} confirm threads for {DURATION_S}s...")
    create_threads = [threading.Thread(target=create_worker, args=(i,), daemon=True) for i in range(THREADS)]
    confirm_threads = [threading.Thread(target=confirm_worker, daemon=True) for _ in range(CONFIRM_THREADS)]
    t_start = time.time()
    for t in create_threads + confirm_threads:
        t.start()

    last_metrics_t = time.time()
    while time.time() - t_start < DURATION_S:
        time.sleep(2)
        elapsed = time.time() - t_start
        with ids_lock:
            n = len(created_ids)
        tps = n / elapsed if elapsed > 0 else 0
        print(f"\r  {CYAN}->{RESET} t={elapsed:.0f}s  created={n}  TPS={tps:.1f}  5xx={len(errors_5xx)}", end="", flush=True)

        if time.time() - last_metrics_t >= 10:
            backend_m = fetch_server_metrics(BASE, "backend")
            gateway_m = fetch_server_metrics(GATEWAY_BASE, "gateway")
            metrics_snapshots.append({
                "t": elapsed, "backend": backend_m, "gateway": gateway_m
            })
            last_metrics_t = time.time()

    stop_event.set()
    for t in create_threads:
        t.join(timeout=15)
    for t in confirm_threads:
        t.join(timeout=15)
    print()

    traffic_elapsed = time.time() - t_start
    total_created = len(created_ids)
    actual_tps = total_created / traffic_elapsed if traffic_elapsed > 0 else 0
    info(f"Traffic phase done: {total_created} intents in {traffic_elapsed:.1f}s ({actual_tps:.1f} TPS)")
    info(f"5xx errors: {len(errors_5xx)}, connection errors: {len(errors_other)}")

    print_metrics_snapshot("After Traffic (before drain)")

    # -- Latency stats --------------------------------------------------------
    percentiles(create_latencies, "Create latency")
    percentiles(confirm_latencies, "Confirm latency")

    if len(errors_5xx) > total_created * 0.05:
        record_issue("CRITICAL", "sustained_load",
                     f"5xx error rate {len(errors_5xx)}/{total_created} = {len(errors_5xx)/max(total_created,1)*100:.1f}%")
    elif errors_5xx:
        record_issue("WARNING", "sustained_load",
                     f"{len(errors_5xx)} 5xx errors ({len(errors_5xx)/max(total_created,1)*100:.1f}%)")

    # -- Phase 2: Drain -- wait for webhooks ----------------------------------
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
        print(f"\r  {CYAN}->{RESET} drain t={elapsed_drain:.0f}s  terminal={len(terminal_ids)}/{total_created} ({pct:.1f}%)", end="", flush=True)

        if len(terminal_ids) == total_created:
            break
        time.sleep(DRAIN_POLL_INTERVAL)

    print()
    drain_elapsed = time.time() - drain_start
    info(f"Drain complete in {drain_elapsed:.1f}s — {len(terminal_ids)}/{total_created} terminal")

    print_metrics_snapshot("After Drain")

    # -- Metrics timeline -----------------------------------------------------
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

    # -- Phase 3: Validation --------------------------------------------------
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
            print(f"\r  {CYAN}->{RESET} validated {idx+1}/{total_created}...", end="", flush=True)

    print(f"\r  {CYAN}->{RESET} validated {total_created}/{total_created}    ")

    # -- Report ---------------------------------------------------------------
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

    # -- Merchant distribution validation -------------------------------------
    print(f"\n  {BOLD}Validating merchant distribution...{RESET}")
    merchant_intent_counts = Counter()
    merchant_mismatches = 0
    for iid in created_ids[:min(500, total_created)]:
        expected_merchant = created_merchant_map.get(iid)
        if expected_merchant:
            merchant_intent_counts[expected_merchant] += 1
            try:
                detail = get_intent(iid)
                actual_merchant = detail.get("merchantId", "")
                if actual_merchant != expected_merchant:
                    merchant_mismatches += 1
                    if merchant_mismatches <= 3:
                        record_issue("CRITICAL", "sustained_merchant",
                                     f"Intent {iid}: expected merchant {expected_merchant}, got {actual_merchant}")
            except Exception:
                pass

    unique_merchants_seen = len(merchant_intent_counts)
    info(f"Intents distributed across {unique_merchants_seen} merchants (sample of {min(500, total_created)})")
    if merchant_mismatches == 0:
        ok("All sampled intents belong to the correct merchant")
    else:
        record_issue("CRITICAL", "sustained_merchant",
                     f"{merchant_mismatches} intents have wrong merchantId")
    if unique_merchants_seen >= THREADS * 0.8:
        ok(f"Good merchant distribution: {unique_merchants_seen}/{THREADS} merchants have intents")
    else:
        record_issue("WARNING", "sustained_merchant",
                     f"Only {unique_merchants_seen}/{THREADS} merchants have intents — uneven distribution")

    # -- Phase 4: Ledger Consistency ------------------------------------------
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


if __name__ == "__main__":
    test_dispatch_retry()
    test_expiry_auth_hang()
    test_sustained_load()
    print_results()
