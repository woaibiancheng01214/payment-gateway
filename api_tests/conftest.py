"""
Shared helpers, constants, and fixtures for payment gateway API tests.
"""

import threading
import requests
import time
import json
import hmac
import hashlib
import random
from collections import defaultdict
from dataclasses import dataclass

# ─── Service URLs ───────────────────────────────────────────────────────────────

BASE = "http://localhost:8080"
GATEWAY_BASE = "http://localhost:8081"
LEDGER_BASE = "http://localhost:8082"
MERCHANT_BASE = "http://localhost:8087"

# ─── Config ─────────────────────────────────────────────────────────────────────

WEBHOOK_SECRET = "payment-gateway-webhook-secret-2026"
CURRENCIES = ["USD", "EUR", "GBP", "JPY", "AUD", "CAD"]
CARD_VISA = {
    "cardNumber": "4242424242424242",
    "cardholderName": "Test User",
    "expiryMonth": 12,
    "expiryYear": 2030,
    "cvc": "123",
}

# ─── ANSI colours ───────────────────────────────────────────────────────────────

GREEN = "\033[92m"
RED = "\033[91m"
YELLOW = "\033[93m"
CYAN = "\033[96m"
BOLD = "\033[1m"
RESET = "\033[0m"


def ok(msg):
    print(f"  {GREEN}✓{RESET} {msg}")

def fail(msg):
    print(f"  {RED}✗ {msg}{RESET}")

def warn(msg):
    print(f"  {YELLOW}⚠{RESET} {msg}")

def info(msg):
    print(f"  {CYAN}→{RESET} {msg}")


# ─── Issue tracking ─────────────────────────────────────────────────────────────

@dataclass
class Issue:
    severity: str  # CRITICAL / WARNING / INFO
    test: str
    detail: str


issues: list[Issue] = []
_lock = threading.Lock()


def record_issue(severity, test, detail):
    with _lock:
        issues.append(Issue(severity, test, detail))
    sym = {
        "CRITICAL": f"{RED}[CRITICAL]{RESET}",
        "WARNING": f"{YELLOW}[WARNING]{RESET}",
        "INFO": f"{CYAN}[INFO]{RESET}",
    }[severity]
    print(f"    {sym} {detail}")


def reset_issues():
    issues.clear()


def print_results():
    print(f"\n{BOLD}{'═'*60}{RESET}")
    print(f"{BOLD}   RESULTS{RESET}")
    print(f"{BOLD}{'═'*60}{RESET}")

    if not issues:
        print(f"\n{GREEN}{BOLD}  All clean — no issues found!{RESET}\n")
        return 0

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
    warnings = len(by_severity.get("WARNING", []))
    print(f"\n  Summary: {RED}{criticals} critical{RESET}, {YELLOW}{warnings} warnings{RESET}\n")
    return criticals


# ─── Random helpers ─────────────────────────────────────────────────────────────

def rand_amount():
    return random.randint(1, 999999)

def rand_currency():
    return random.choice(CURRENCIES)


# ─── Merchant helpers ───────────────────────────────────────────────────────────

_default_merchant = None


def create_merchant(name: str) -> dict:
    r = requests.post(f"{MERCHANT_BASE}/v1/merchants", json={"name": name}, timeout=10)
    r.raise_for_status()
    return r.json()


def get_merchant(merchant_id: str) -> dict:
    r = requests.get(f"{MERCHANT_BASE}/v1/merchants/{merchant_id}", timeout=10)
    r.raise_for_status()
    return r.json()


def ensure_test_merchant() -> str:
    global _default_merchant
    if _default_merchant is None:
        m = create_merchant("stress-test-default")
        _default_merchant = m["id"]
    return _default_merchant


# ─── Payment helpers ────────────────────────────────────────────────────────────

def create_intent(amount=None, currency=None, merchant_id=None) -> dict:
    amount = amount or rand_amount()
    currency = currency or rand_currency()
    merchant_id = merchant_id or ensure_test_merchant()
    r = requests.post(
        f"{BASE}/v1/payment_intents",
        json={"merchantId": merchant_id, "amount": amount, "currency": currency},
        timeout=10,
    )
    r.raise_for_status()
    return r.json()


def confirm_intent(intent_id, card_data=None) -> requests.Response:
    return requests.post(
        f"{BASE}/v1/payment_intents/{intent_id}/confirm",
        json=card_data or CARD_VISA,
        headers={"Content-Type": "application/json"},
        timeout=15,
    )


def capture_intent(intent_id) -> requests.Response:
    return requests.post(f"{BASE}/v1/payment_intents/{intent_id}/capture", timeout=15)


def get_intent(intent_id) -> dict:
    r = requests.get(f"{BASE}/v1/payment_intents/{intent_id}", timeout=10)
    r.raise_for_status()
    return r.json()


def get_intent_status(intent_id) -> str:
    """Lightweight status check — single DB read, no auth-service RPC."""
    r = requests.get(f"{BASE}/v1/payment_intents/{intent_id}/summary", timeout=5)
    r.raise_for_status()
    return r.json()["status"]


def wait_for_terminal(intent_id, timeout_s=180) -> str:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        d = get_intent(intent_id)
        if d["status"] not in ("requires_confirmation",):
            return d["status"]
        time.sleep(2)
    return get_intent(intent_id)["status"]


# ─── Webhook helpers ────────────────────────────────────────────────────────────

def signed_webhook_post(internal_attempt_id: str, status: str, secret: str = None, timeout: int = 5):
    secret = secret or WEBHOOK_SECRET
    payload = {"internalAttemptId": internal_attempt_id, "status": status}
    body = json.dumps(payload)
    timestamp = str(int(time.time()))
    signature = hmac.new(
        secret.encode(), f"{timestamp}.{body}".encode(), hashlib.sha256
    ).hexdigest()
    return requests.post(
        f"{BASE}/v1/webhooks/gateway",
        data=body,
        headers={
            "Content-Type": "application/json",
            "X-Gateway-Signature": signature,
            "X-Gateway-Timestamp": timestamp,
        },
        timeout=timeout,
    )


# ─── Metrics helpers ────────────────────────────────────────────────────────────

def fetch_server_metrics(base_url, label):
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
        mem_used = get_metric("jvm.memory.used")
        metrics["mem_used_mb"] = mem_used / (1024 * 1024) if mem_used else None
        mem_max = get_metric("jvm.memory.max")
        if mem_max and mem_max > 0:
            metrics["mem_max_mb"] = mem_max / (1024 * 1024)
        metrics["threads_live"] = get_metric("jvm.threads.live")
        metrics["threads_peak"] = get_metric("jvm.threads.peak")
        metrics["threads_daemon"] = get_metric("jvm.threads.daemon")
    except Exception as e:
        metrics["error"] = str(e)[:60]
    return metrics


def print_metrics_snapshot(phase_label):
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


def percentiles(arr, label):
    if not arr:
        info(f"{label}: no data")
        return
    s = sorted(arr)
    p50 = s[len(s) // 2]
    p95 = s[int(len(s) * 0.95)]
    p99 = s[int(len(s) * 0.99)]
    info(f"{label}: P50={p50:.0f}ms  P95={p95:.0f}ms  P99={p99:.0f}ms  (n={len(s)})")


# ─── Ledger helpers ─────────────────────────────────────────────────────────────

def get_ledger_entries(intent_id) -> list:
    try:
        r = requests.get(f"{LEDGER_BASE}/v1/ledger/entries", params={"paymentIntentId": intent_id}, timeout=10)
        if r.status_code == 200:
            return r.json()
    except Exception:
        pass
    return []


def get_ledger_balances() -> dict:
    try:
        r = requests.get(f"{LEDGER_BASE}/v1/ledger/balances", timeout=10)
        if r.status_code == 200:
            return r.json()
    except Exception:
        pass
    return {}
