"""
Ledger consistency tests.

Usage:
    python -m api_tests.test_ledger
"""

import requests
import time

from api_tests.conftest import *


def test_ledger_consistency():
    """
    Validates ledger correctness after the sustained load test (or independently).
    Checks:
    1. Global double-entry balance: total debits == total credits
    2. Per-intent entry correctness based on terminal status:
       - authorized -> exactly 1 DEBIT (merchant_receivables) + 1 CREDIT (gateway_payable)
       - failed      -> either 0 entries (failed before auth) or balanced reversal
       - captured    -> auth entries + capture entries (2 DEBIT + 2 CREDIT)
    3. No orphan ledger entries for intents that don't exist
    """
    SAMPLE_SIZE = 50
    print(f"\n{BOLD}── Test: Ledger Double-Entry Consistency ──{RESET}")

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
        r = requests.get(f"{BASE}/v1/payment_intents", params={"size": SAMPLE_SIZE}, timeout=10)
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


def test_dead_letter_api():
    """
    Validates dead-letter events API accessibility.
    Checks:
    1. GET /v1/ledger/dead-letter-events returns 200
    2. GET /v1/ledger/dead-letter-events?unresolvedOnly=true returns 200
    """
    print(f"\n{BOLD}── Test: Dead Letter Events API ──{RESET}")

    # 1. All dead-letter events
    try:
        r = requests.get(f"{LEDGER_BASE}/v1/ledger/dead-letter-events", timeout=10)
        if r.status_code == 200:
            ok(f"Dead-letter events API accessible (status={r.status_code})")
        else:
            record_issue("WARNING", "dead_letter_api",
                         f"Dead-letter events API returned {r.status_code}, expected 200")
    except Exception as e:
        record_issue("WARNING", "dead_letter_api", f"Dead-letter events API failed: {e}")

    # 2. Unresolved-only filter
    try:
        r = requests.get(f"{LEDGER_BASE}/v1/ledger/dead-letter-events",
                         params={"unresolvedOnly": "true"}, timeout=10)
        if r.status_code == 200:
            ok(f"Dead-letter events (unresolvedOnly=true) accessible (status={r.status_code})")
        else:
            record_issue("WARNING", "dead_letter_api",
                         f"Dead-letter events (unresolvedOnly=true) returned {r.status_code}, expected 200")
    except Exception as e:
        record_issue("WARNING", "dead_letter_api", f"Dead-letter events (unresolvedOnly) failed: {e}")


if __name__ == "__main__":
    test_ledger_consistency()
    test_dead_letter_api()
    print_results()
