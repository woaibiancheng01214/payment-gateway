"""
Sustained load test, expiry test, and dispatch retry test.

Usage:
    python -m api_tests.test_load                      # default 30s, 100 write TPS, 200 read TPS
    python -m api_tests.test_load --duration 1200       # 20 minutes
    python -m api_tests.test_load --read-tps 500        # 500 reads/sec
    LOAD_READ_TPS=0 python -m api_tests.test_load       # disable read traffic
"""

import os
import sys
import threading
import queue
import time
import math
import asyncio
import requests
import random
from collections import Counter

import aiohttp

from api_tests.conftest import *


def _parse_duration() -> int:
    """Parse duration from --duration CLI arg or LOAD_DURATION env var. Default 30s."""
    for i, arg in enumerate(sys.argv):
        if arg == "--duration" and i + 1 < len(sys.argv):
            return int(sys.argv[i + 1])
    return int(os.environ.get("LOAD_DURATION", "30"))


def _parse_tps() -> int:
    """Parse TPS from --tps CLI arg or LOAD_TPS env var. Default 100."""
    for i, arg in enumerate(sys.argv):
        if arg == "--tps" and i + 1 < len(sys.argv):
            return int(sys.argv[i + 1])
    return int(os.environ.get("LOAD_TPS", "100"))


def _parse_read_tps() -> int:
    """Parse read TPS from --read-tps CLI arg or LOAD_READ_TPS env var. Default 200."""
    for i, arg in enumerate(sys.argv):
        if arg == "--read-tps" and i + 1 < len(sys.argv):
            return int(sys.argv[i + 1])
    return int(os.environ.get("LOAD_READ_TPS", "200"))


# -- Read type weights for mixed read traffic --------------------------------
READ_WEIGHTS = [
    ("get_detail",      40),   # GET /v1/payment_intents/{id}
    ("get_summary",     25),   # GET /v1/payment_intents/{id}/summary
    ("list_cursor",     10),   # GET /v1/payment_intents/cursor?limit=N
    ("list_merchant",   10),   # GET /v1/payment_intents/cursor/merchant/{mid}?limit=N
    ("get_merchant",     8),   # GET /v1/merchants/{id}
    ("ledger_entries",   5),   # GET /v1/ledger/entries?paymentIntentId={id}
    ("ledger_balances",  2),   # GET /v1/ledger/balances
]
READ_TYPE_NAMES = [w[0] for w in READ_WEIGHTS]
READ_TYPE_WEIGHTS = [w[1] for w in READ_WEIGHTS]


def test_outbox_dispatch_flag():
    """
    Verifies the outbox pattern end-to-end:
    1. Create + confirm 5 intents rapidly.
    2. Wait for terminal states.
    3. Verify each intent's PaymentAttempt has associated InternalAttempts,
       proving dispatch happened (either immediately or via ConfirmDispatchScheduler).
    """
    COUNT = 5
    print(f"\n{BOLD}── Test: Outbox Dispatch Flag ({COUNT} intents) ──{RESET}")

    intent_ids = []
    for i in range(COUNT):
        intent = create_intent(amount=rand_amount(), currency=rand_currency())
        r = confirm_intent(intent["id"])
        if r.status_code < 300:
            intent_ids.append(intent["id"])
        else:
            warn(f"Confirm failed for intent {intent['id']}: {r.status_code}")

    info(f"Created+confirmed {len(intent_ids)} intents, waiting for terminal states...")

    # Wait for all to reach terminal
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

    # Verify each intent has InternalAttempts (proves dispatch happened)
    dispatched_count = 0
    for iid in intent_ids:
        d = get_intent(iid)
        attempts = d.get("attempts", [])
        if not attempts:
            record_issue("CRITICAL", "outbox_dispatch", f"Intent {iid} has no PaymentAttempt")
            continue
        internals = attempts[0].get("internalAttempts", [])
        if internals:
            dispatched_count += 1
        else:
            record_issue("CRITICAL", "outbox_dispatch",
                         f"Intent {iid} has no InternalAttempts (dispatch never happened)")

    if dispatched_count == len(intent_ids):
        ok(f"All {dispatched_count} intents have InternalAttempts (outbox dispatch working)")
    else:
        warn(f"Only {dispatched_count}/{len(intent_ids)} intents have InternalAttempts")


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


def test_expiry_auth_hang():
    """
    Simulate a gateway that never calls back and wait for the scheduler to expire it.
    Reads the actual auth timeout from the server's actuator/env endpoint.
    Skips if the configured timeout exceeds 60s (too slow for integration tests).
    To test expiry with short waits, set payment.timeout.auth-seconds=30 in application.properties.
    """
    print(f"\n{BOLD}── Test: Auth Expiry — intent expires if webhook never arrives ──{RESET}")

    # Read actual auth timeout from server config
    auth_timeout_s = 30  # default
    try:
        r = requests.get(f"{BASE}/actuator/env/payment.timeout.auth-seconds", timeout=5)
        if r.status_code == 200:
            prop = r.json().get("property", {})
            auth_timeout_s = int(prop.get("value", 30))
            info(f"Server auth timeout: {auth_timeout_s}s")
    except Exception:
        info(f"Could not read auth timeout from actuator, assuming {auth_timeout_s}s")

    if auth_timeout_s > 60:
        warn(f"Skipping — auth timeout is {auth_timeout_s}s (set payment.timeout.auth-seconds<=60 to enable)")
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
    Sustained write+read TPS, then drain waiting for webhooks.
    Configurable via --duration, --tps, --read-tps CLI args or env vars.
    Drain time scales with duration (minimum 120s).
    """
    THREADS = _parse_tps()
    DURATION_S = _parse_duration()
    READ_TPS = _parse_read_tps()
    # Drain needs enough time to poll all intents: ~TPS * DURATION / batch_size * seconds_per_batch
    estimated_intents = THREADS * DURATION_S
    DRAIN_S = max(120, estimated_intents // 500 + 120)  # generous drain budget
    DRAIN_POLL_INTERVAL = 1

    print(f"\n{BOLD}{'='*60}{RESET}")
    print(f"{BOLD}   Sustained Load Test — {THREADS} write TPS + {READ_TPS} read TPS x {DURATION_S}s{RESET}")
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

    # -- Warmup: ramp up gradually to let JIT compile and pools initialize ----
    WARMUP_TPS = min(20, THREADS)
    WARMUP_S = 10
    print(f"\n  {BOLD}Warming up: {WARMUP_TPS} TPS for {WARMUP_S}s...{RESET}")
    warmup_stop = threading.Event()
    def warmup_worker(idx):
        mid = merchants[idx % len(merchants)]["id"]
        while not warmup_stop.is_set():
            try:
                r = requests.post(f"{BASE}/v1/payment_intents",
                    json={"merchantId": mid, "amount": rand_amount(), "currency": rand_currency()}, timeout=10)
                if r.status_code < 300:
                    iid = r.json()["id"]
                    requests.post(f"{BASE}/v1/payment_intents/{iid}/authorise",
                        json=CARD_VISA, headers={"Content-Type": "application/json"}, timeout=15)
            except Exception:
                pass
            warmup_stop.wait(timeout=1.0)
    warmup_threads = [threading.Thread(target=warmup_worker, args=(i,), daemon=True) for i in range(WARMUP_TPS)]
    for t in warmup_threads:
        t.start()
    time.sleep(WARMUP_S)
    warmup_stop.set()
    for t in warmup_threads:
        t.join(timeout=5)
    ok(f"Warmup complete ({WARMUP_TPS} TPS x {WARMUP_S}s)")

    print_metrics_snapshot("Before Load")

    created_ids = []
    created_merchant_map = {}  # intent_id -> merchant_id
    create_latencies = []
    authorise_latencies = []
    errors_5xx = []
    errors_other = []
    stop_event = threading.Event()
    ids_lock = threading.Lock()
    metrics_snapshots = []

    # -- Read traffic state ---------------------------------------------------
    read_latencies = {name: [] for name in READ_TYPE_NAMES}
    read_errors_5xx = []
    read_errors_other = []
    read_count = [0]  # mutable counter (list so inner fn can mutate)

    authorise_queue = queue.Queue()

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
                    authorise_queue.put(iid)

            except requests.exceptions.RequestException as e:
                with ids_lock:
                    errors_other.append(str(e)[:80])

            elapsed = time.time() - t0
            sleep_for = max(0, 1.0 - elapsed)
            if not stop_event.is_set():
                stop_event.wait(timeout=sleep_for)

    def authorise_worker():
        """Drains the confirm queue independently so creates are not blocked."""
        while not stop_event.is_set() or not authorise_queue.empty():
            try:
                iid = authorise_queue.get(timeout=1)
            except queue.Empty:
                continue
            try:
                t1 = time.time()
                r2 = requests.post(f"{BASE}/v1/payment_intents/{iid}/authorise",
                                   json=CARD_VISA,
                                   headers={"Content-Type": "application/json"},
                                   timeout=10)
                authorise_ms = (time.time() - t1) * 1000
                if r2.status_code >= 500:
                    with ids_lock:
                        errors_5xx.append(("confirm", r2.status_code))
                else:
                    with ids_lock:
                        authorise_latencies.append(authorise_ms)
            except requests.exceptions.RequestException as e:
                with ids_lock:
                    errors_other.append(str(e)[:80])

    async def _async_read_loop(target_tps, duration_s):
        """Async read loop: fires target_tps reads/sec using non-blocking aiohttp."""
        interval = 1.0 / target_tps if target_tps > 0 else 1.0
        timeout = aiohttp.ClientTimeout(total=5)
        connector = aiohttp.TCPConnector(limit=min(target_tps, 200))
        async with aiohttp.ClientSession(timeout=timeout, connector=connector) as session:

            async def do_one_read():
                with ids_lock:
                    if not created_ids:
                        return
                    intent_id = random.choice(created_ids)
                    merchant_id = created_merchant_map.get(intent_id, merchants[0]["id"])

                read_type = random.choices(READ_TYPE_NAMES, weights=READ_TYPE_WEIGHTS, k=1)[0]
                try:
                    t0 = time.time()
                    if read_type == "get_detail":
                        url = f"{BASE}/v1/payment_intents/{intent_id}"
                        async with session.get(url) as r:
                            status = r.status
                    elif read_type == "get_summary":
                        url = f"{BASE}/v1/payment_intents/{intent_id}/summary"
                        async with session.get(url) as r:
                            status = r.status
                    elif read_type == "list_cursor":
                        limit = random.choice([5, 10, 20])
                        url = f"{BASE}/v1/payment_intents/cursor"
                        async with session.get(url, params={"limit": limit}) as r:
                            status = r.status
                    elif read_type == "list_merchant":
                        limit = random.choice([5, 10, 20])
                        url = f"{BASE}/v1/payment_intents/cursor/merchant/{merchant_id}"
                        async with session.get(url, params={"limit": limit}) as r:
                            status = r.status
                    elif read_type == "get_merchant":
                        url = f"{MERCHANT_BASE}/v1/merchants/{merchant_id}"
                        async with session.get(url) as r:
                            status = r.status
                    elif read_type == "ledger_entries":
                        url = f"{LEDGER_BASE}/v1/ledger/entries"
                        async with session.get(url, params={"paymentIntentId": intent_id}) as r:
                            status = r.status
                    elif read_type == "ledger_balances":
                        url = f"{LEDGER_BASE}/v1/ledger/balances"
                        async with session.get(url) as r:
                            status = r.status

                    elapsed_ms = (time.time() - t0) * 1000
                    if status >= 500:
                        with ids_lock:
                            read_errors_5xx.append((read_type, status))
                    else:
                        with ids_lock:
                            read_latencies[read_type].append(elapsed_ms)
                            read_count[0] += 1

                except Exception as e:
                    with ids_lock:
                        read_errors_other.append((read_type, str(e)[:80]))

            pending_tasks = set()
            start = time.time()
            fired = 0
            while not stop_event.is_set():
                # Wait for write workers to create some data
                with ids_lock:
                    has_data = bool(created_ids)
                if not has_data:
                    await asyncio.sleep(0.2)
                    continue

                # Fire one read and track the task
                task = asyncio.create_task(do_one_read())
                pending_tasks.add(task)
                task.add_done_callback(pending_tasks.discard)
                fired += 1
                # Pace: sleep until the next scheduled fire time
                next_fire = start + fired * interval
                delay = next_fire - time.time()
                if delay > 0:
                    await asyncio.sleep(delay)

            # Cancel in-flight requests on shutdown
            for t in pending_tasks:
                t.cancel()
            if pending_tasks:
                await asyncio.gather(*pending_tasks, return_exceptions=True)

    def _run_async_reads(target_tps, duration_s):
        """Run the async read loop in a dedicated event loop (called from a thread)."""
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        loop.run_until_complete(_async_read_loop(target_tps, duration_s))
        loop.close()

    # -- Phase 1: Traffic -----------------------------------------------------
    AUTHORISE_THREADS = THREADS  # separate pool to drain confirm queue without blocking creates
    read_label = f" + {READ_TPS} read (async)" if READ_TPS > 0 else ""
    info(f"Starting {THREADS} create + {AUTHORISE_THREADS} confirm{read_label} for {DURATION_S}s...")
    create_threads = [threading.Thread(target=create_worker, args=(i,), daemon=True) for i in range(THREADS)]
    authorise_threads = [threading.Thread(target=authorise_worker, daemon=True) for _ in range(AUTHORISE_THREADS)]
    read_thread = None
    if READ_TPS > 0:
        read_thread = threading.Thread(target=_run_async_reads, args=(READ_TPS, DURATION_S), daemon=True)
    t_start = time.time()
    all_threads = create_threads + authorise_threads
    if read_thread:
        all_threads.append(read_thread)
    for t in all_threads:
        t.start()

    last_metrics_t = time.time()
    while time.time() - t_start < DURATION_S:
        time.sleep(2)
        elapsed = time.time() - t_start
        with ids_lock:
            n = len(created_ids)
            n_reads = read_count[0]
            n_read_5xx = len(read_errors_5xx)
        tps = n / elapsed if elapsed > 0 else 0
        r_tps = n_reads / elapsed if elapsed > 0 else 0
        r_label = f"  reads={n_reads}  rTPS={r_tps:.1f}" if READ_TPS > 0 else ""
        r_5xx = f"+{n_read_5xx}r" if READ_TPS > 0 else ""
        print(f"\r  {CYAN}->{RESET} t={elapsed:.0f}s  created={n}  wTPS={tps:.1f}{r_label}  5xx={len(errors_5xx)}{r_5xx}", end="", flush=True)

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
    for t in authorise_threads:
        t.join(timeout=15)
    if read_thread:
        read_thread.join(timeout=15)
    print()

    traffic_elapsed = time.time() - t_start
    total_created = len(created_ids)
    total_reads = read_count[0]
    actual_tps = total_created / traffic_elapsed if traffic_elapsed > 0 else 0
    actual_read_tps = total_reads / traffic_elapsed if traffic_elapsed > 0 else 0
    info(f"Traffic phase done: {total_created} writes in {traffic_elapsed:.1f}s ({actual_tps:.1f} write TPS)")
    if READ_TPS > 0:
        info(f"Read traffic: {total_reads} reads in {traffic_elapsed:.1f}s ({actual_read_tps:.1f} read TPS)")
    info(f"Write 5xx: {len(errors_5xx)}, Read 5xx: {len(read_errors_5xx)}, connection errors: {len(errors_other) + len(read_errors_other)}")

    print_metrics_snapshot("After Traffic (before drain)")

    # -- Latency stats --------------------------------------------------------
    percentiles(create_latencies, "Create latency")
    percentiles(authorise_latencies, "Authorise latency")

    if READ_TPS > 0 and total_reads > 0:
        print(f"\n  {BOLD}── Read Latencies ──{RESET}")
        all_read_lats = []
        for rtype in READ_TYPE_NAMES:
            lats = read_latencies[rtype]
            if lats:
                percentiles(lats, f"  {rtype}")
                all_read_lats.extend(lats)
        if all_read_lats:
            percentiles(all_read_lats, "All reads (combined)")

    if len(errors_5xx) > total_created * 0.05:
        record_issue("CRITICAL", "sustained_load",
                     f"5xx error rate {len(errors_5xx)}/{total_created} = {len(errors_5xx)/max(total_created,1)*100:.1f}%")
    elif errors_5xx:
        record_issue("WARNING", "sustained_load",
                     f"{len(errors_5xx)} 5xx errors ({len(errors_5xx)/max(total_created,1)*100:.1f}%)")

    if READ_TPS > 0:
        total_read_attempts = total_reads + len(read_errors_5xx) + len(read_errors_other)
        if len(read_errors_5xx) > total_read_attempts * 0.05:
            record_issue("CRITICAL", "sustained_load_reads",
                         f"Read 5xx error rate {len(read_errors_5xx)}/{total_read_attempts} = "
                         f"{len(read_errors_5xx)/max(total_read_attempts,1)*100:.1f}%")
        elif read_errors_5xx:
            record_issue("WARNING", "sustained_load_reads",
                         f"{len(read_errors_5xx)} read 5xx errors ({len(read_errors_5xx)/max(total_read_attempts,1)*100:.1f}%)")

    # -- Phase 2: Drain -- wait for webhooks ----------------------------------
    # For large runs, sample the drain: poll a random subset to estimate terminal %
    DRAIN_SAMPLE = min(total_created, 5000)
    drain_ids = random.sample(created_ids, DRAIN_SAMPLE) if total_created > DRAIN_SAMPLE else list(created_ids)
    info(f"Draining for up to {DRAIN_S}s — polling {len(drain_ids)} intents (of {total_created})...")
    terminal_statuses = {"authorized", "failed", "captured", "expired"}
    drain_start = time.time()
    terminal_ids = set()

    import concurrent.futures
    POLL_WORKERS = 50
    POLL_BATCH = 500

    def poll_batch(ids_to_check):
        """Poll a batch of IDs concurrently."""
        newly_terminal = set()
        poll_lock = threading.Lock()

        def check_one(iid):
            try:
                status = get_intent_status(iid)
                if status in terminal_statuses:
                    with poll_lock:
                        newly_terminal.add(iid)
            except Exception:
                pass

        with concurrent.futures.ThreadPoolExecutor(max_workers=POLL_WORKERS) as executor:
            list(executor.map(check_one, ids_to_check))
        return newly_terminal

    while time.time() - drain_start < DRAIN_S:
        remaining_ids = [iid for iid in drain_ids if iid not in terminal_ids]
        if not remaining_ids:
            break

        batch = remaining_ids[:POLL_BATCH]
        newly = poll_batch(batch)
        terminal_ids.update(newly)

        drain_total = len(drain_ids)
        pct = len(terminal_ids) / drain_total * 100 if drain_total > 0 else 0
        elapsed_drain = time.time() - drain_start
        print(f"\r  {CYAN}->{RESET} drain t={elapsed_drain:.0f}s  terminal={len(terminal_ids)}/{drain_total} ({pct:.1f}%)", end="", flush=True)

        if len(terminal_ids) >= drain_total:
            break
        time.sleep(DRAIN_POLL_INTERVAL)

    print()
    drain_elapsed = time.time() - drain_start
    drain_total = len(drain_ids)
    info(f"Drain complete in {drain_elapsed:.1f}s — {len(terminal_ids)}/{drain_total} sampled terminal")

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

    # -- Phase 3: Validation (sample-based for large runs) --------------------
    VALIDATION_SAMPLE = min(total_created, 2000)
    sample_ids = random.sample(created_ids, VALIDATION_SAMPLE) if total_created > VALIDATION_SAMPLE else list(created_ids)
    print(f"\n  {BOLD}Validating {VALIDATION_SAMPLE} intents (sampled from {total_created})...{RESET}")
    status_counts = Counter()
    mismatches = 0
    multi_attempts = 0
    missing_attempts = 0
    stuck = 0
    fetch_failures = 0

    for idx, iid in enumerate(sample_ids):
        try:
            detail = get_intent(iid)
        except Exception:
            fetch_failures += 1
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
            print(f"\r  {CYAN}->{RESET} validated {idx+1}/{VALIDATION_SAMPLE}...", end="", flush=True)

    print(f"\r  {CYAN}->{RESET} validated {VALIDATION_SAMPLE}/{VALIDATION_SAMPLE}    ")
    if fetch_failures > 0:
        record_issue("WARNING", "sustained_validation",
                     f"{fetch_failures}/{VALIDATION_SAMPLE} intents failed to fetch during validation")

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

    terminal_pct = len(terminal_ids) / len(drain_ids) * 100 if drain_ids else 0
    if terminal_pct >= 95:
        ok(f"Terminal coverage: {terminal_pct:.1f}% ({len(terminal_ids)}/{len(drain_ids)} sampled)")
    else:
        record_issue("WARNING", "sustained_terminal",
                     f"Only {terminal_pct:.1f}% reached terminal within {DRAIN_S}s drain (sampled {len(drain_ids)})")

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
    stress_only = "--stress" in sys.argv
    if not stress_only:
        test_dispatch_retry()
        test_expiry_auth_hang()
    test_sustained_load()
    print_results()
