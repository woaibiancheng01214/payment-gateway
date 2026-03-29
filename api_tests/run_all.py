#!/usr/bin/env python3
"""
Payment Gateway — API Test Runner

Usage:
    python -m api_tests.run_all           # run all tests
    python -m api_tests.run_all --stress  # sustained load + ledger only
    python -m api_tests.run_all --quick   # skip sustained load test
"""

import sys
import os

# Ensure the project root is on the path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from api_tests.conftest import BOLD, RESET, print_results, reset_issues, record_issue

from api_tests.test_payments import (
    test_throughput_and_latencies,
    test_state_machine_guards,
    test_data_consistency_under_load,
    test_input_validation,
    test_openapi_docs_available,
)
from api_tests.test_concurrency import (
    test_concurrent_creates,
    test_concurrent_confirm_redis_lock,
    test_idempotency_correctness,
    test_concurrent_capture,
)
from api_tests.test_security import (
    test_webhook_replay,
    test_late_webhook_after_terminal,
    test_pci_service_isolation,
    test_pci_tokenization_flow,
    test_webhook_signature_verification,
    test_webhook_missing_headers,
)
from api_tests.test_observability import (
    test_cursor_pagination,
    test_correlation_ids,
    test_business_metrics,
    test_hikari_pool_metrics,
    test_graceful_shutdown_config,
)
from api_tests.test_load import (
    test_dispatch_retry,
    test_expiry_auth_hang,
    test_sustained_load,
)
from api_tests.test_ledger import (
    test_ledger_consistency,
    test_dead_letter_api,
)


def main():
    stress_only = "--stress" in sys.argv
    quick_mode = "--quick" in sys.argv

    reset_issues()

    print(f"\n{BOLD}{'='*60}{RESET}")
    if stress_only:
        print(f"{BOLD}   Payment Gateway — Stress Test Only{RESET}")
    elif quick_mode:
        print(f"{BOLD}   Payment Gateway — Quick Test (no sustained load){RESET}")
    else:
        print(f"{BOLD}   Payment Gateway — Full API Test Suite{RESET}")
    print(f"{BOLD}{'='*60}{RESET}")

    if stress_only:
        suites = [
            test_sustained_load,
            test_ledger_consistency,
        ]
    elif quick_mode:
        suites = [
            # Payments
            test_throughput_and_latencies,
            test_input_validation,
            test_state_machine_guards,
            test_data_consistency_under_load,
            test_openapi_docs_available,
            # Concurrency
            test_concurrent_creates,
            test_concurrent_confirm_redis_lock,
            test_idempotency_correctness,
            test_concurrent_capture,
            # Security
            test_webhook_replay,
            test_late_webhook_after_terminal,
            test_pci_service_isolation,
            test_pci_tokenization_flow,
            test_webhook_signature_verification,
            test_webhook_missing_headers,
            # Observability
            test_cursor_pagination,
            test_correlation_ids,
            test_business_metrics,
            test_hikari_pool_metrics,
            test_graceful_shutdown_config,
            # Load (without sustained)
            test_dispatch_retry,
            test_expiry_auth_hang,
            # Ledger
            test_ledger_consistency,
            test_dead_letter_api,
        ]
    else:
        suites = [
            # Payments
            test_throughput_and_latencies,
            test_input_validation,
            test_state_machine_guards,
            test_data_consistency_under_load,
            test_openapi_docs_available,
            # Concurrency
            test_concurrent_creates,
            test_concurrent_confirm_redis_lock,
            test_idempotency_correctness,
            test_concurrent_capture,
            # Security
            test_webhook_replay,
            test_late_webhook_after_terminal,
            test_pci_service_isolation,
            test_pci_tokenization_flow,
            test_webhook_signature_verification,
            test_webhook_missing_headers,
            # Observability
            test_cursor_pagination,
            test_correlation_ids,
            test_business_metrics,
            test_hikari_pool_metrics,
            test_graceful_shutdown_config,
            # Load
            test_dispatch_retry,
            test_expiry_auth_hang,
            test_sustained_load,
            # Ledger
            test_ledger_consistency,
            test_dead_letter_api,
        ]

    for suite in suites:
        try:
            suite()
        except Exception as e:
            record_issue("CRITICAL", suite.__name__, f"Test suite crashed: {e}")

    criticals = print_results()
    sys.exit(1 if criticals > 0 else 0)


if __name__ == "__main__":
    main()
