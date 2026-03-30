"""
Security tests: webhook replay protection, PCI isolation, tokenization, and signature verification.
"""

import hmac
import hashlib
import json
import time
import requests

from api_tests.conftest import *


def test_webhook_replay():
    """Replay a webhook after terminal state and verify status doesn't change."""
    print(f"\n{BOLD}{'─'*60}{RESET}")
    print(f"{BOLD}  TEST: Webhook Replay Protection{RESET}")
    print(f"{BOLD}{'─'*60}{RESET}")

    intent = create_intent()
    intent_id = intent["id"]
    info(f"Created intent {intent_id}")

    resp = confirm_intent(intent_id)
    resp.raise_for_status()
    info("Intent confirmed, waiting for terminal state...")

    status = wait_for_terminal(intent_id, timeout_s=15)
    if status == "requires_confirmation":
        warn("Intent did not reach terminal state within 15s, skipping replay test")
        record_issue("WARNING", "webhook_replay", "Intent never reached terminal state")
        return

    detail = get_intent(intent_id)
    original_status = detail["status"]
    info(f"Terminal status: {original_status}")

    # Find an InternalAttempt ID from the detail response
    detail_resp = requests.get(f"{BASE}/v1/payment_intents/{intent_id}", timeout=10)
    detail_resp.raise_for_status()
    detail_data = detail_resp.json()

    ia_id = None
    for attempt in detail_data.get("attempts", []):
        for ia in attempt.get("internalAttempts", []):
            ia_id = ia["id"]
            break
        if ia_id:
            break

    if not ia_id:
        warn("No InternalAttempt found, cannot test replay")
        record_issue("WARNING", "webhook_replay", "No InternalAttempt in detail response")
        return

    info(f"Replaying webhook for InternalAttempt {ia_id}")
    replay_resp = signed_webhook_post(ia_id, "success")
    info(f"Replay response status: {replay_resp.status_code}")

    after = get_intent(intent_id)
    after_status = after["status"]

    if after_status == original_status:
        ok(f"Status unchanged after replay: {after_status}")
    else:
        fail(f"Status changed after replay: {original_status} -> {after_status}")
        record_issue("CRITICAL", "webhook_replay", f"Replay changed status from {original_status} to {after_status}")


def test_late_webhook_after_terminal():
    """Send a late webhook after intent reaches terminal state and verify no change."""
    print(f"\n{BOLD}{'─'*60}{RESET}")
    print(f"{BOLD}  TEST: Late Webhook After Terminal State{RESET}")
    print(f"{BOLD}{'─'*60}{RESET}")

    intent = create_intent()
    intent_id = intent["id"]
    info(f"Created intent {intent_id}")

    resp = confirm_intent(intent_id)
    resp.raise_for_status()
    info("Intent confirmed, waiting 3s...")

    time.sleep(3)

    detail_resp = requests.get(f"{BASE}/v1/payment_intents/{intent_id}", timeout=10)
    detail_resp.raise_for_status()
    detail_data = detail_resp.json()
    current_status = detail_data["status"]
    info(f"Current status after 3s: {current_status}")

    if current_status == "requires_confirmation":
        warn("Intent still not terminal after 3s, skipping late webhook test")
        record_issue("INFO", "late_webhook", "Intent not terminal after 3s wait")
        return

    ia_id = None
    for attempt in detail_data.get("attempts", []):
        for ia in attempt.get("internalAttempts", []):
            ia_id = ia["id"]
            break
        if ia_id:
            break

    if not ia_id:
        warn("No InternalAttempt found, cannot test late webhook")
        record_issue("WARNING", "late_webhook", "No InternalAttempt in detail response")
        return

    info(f"Sending late 'success' webhook for InternalAttempt {ia_id}")
    late_resp = signed_webhook_post(ia_id, "success")
    info(f"Late webhook response status: {late_resp.status_code}")

    after = get_intent(intent_id)
    after_status = after["status"]

    if after_status == current_status:
        ok(f"Status unchanged after late webhook: {after_status}")
    else:
        fail(f"Status changed after late webhook: {current_status} -> {after_status}")
        record_issue("CRITICAL", "late_webhook", f"Late webhook changed status from {current_status} to {after_status}")


def test_pci_service_isolation():
    """Verify PCI services are NOT accessible from the host."""
    print(f"\n{BOLD}{'─'*60}{RESET}")
    print(f"{BOLD}  TEST: PCI Service Isolation{RESET}")
    print(f"{BOLD}{'─'*60}{RESET}")

    pci_services = [
        ("card-vault-service", 8086),
        ("token-service", 8084),
        ("card-auth-service", 8085),
    ]

    for name, port in pci_services:
        try:
            r = requests.get(f"http://localhost:{port}/actuator/health", timeout=3)
            fail(f"{name} (port {port}) is accessible from host! Status: {r.status_code}")
            record_issue("CRITICAL", "pci_isolation", f"{name} on port {port} reachable from host")
        except (requests.exceptions.ConnectionError, requests.exceptions.ReadTimeout):
            ok(f"{name} (port {port}) is NOT accessible from host")


def test_pci_tokenization_flow():
    """Verify tokenization produces pm_ IDs and card metadata without exposing raw PAN."""
    print(f"\n{BOLD}{'─'*60}{RESET}")
    print(f"{BOLD}  TEST: PCI Tokenization Flow{RESET}")
    print(f"{BOLD}{'─'*60}{RESET}")

    intent = create_intent()
    intent_id = intent["id"]
    info(f"Created intent {intent_id}")

    resp = confirm_intent(intent_id)
    resp.raise_for_status()
    info("Intent confirmed")

    # Wait briefly for processing
    time.sleep(2)

    detail_resp = requests.get(f"{BASE}/v1/payment_intents/{intent_id}", timeout=10)
    detail_resp.raise_for_status()
    detail_data = detail_resp.json()

    response_text = json.dumps(detail_data)

    # Check that raw card number is NOT in the response
    raw_card = CARD_VISA["cardNumber"]
    if raw_card in response_text:
        fail("Raw card number found in response!")
        record_issue("CRITICAL", "tokenization", "Raw PAN present in payment intent detail response")
    else:
        ok("Raw card number NOT present in response")

    # Check payment attempt fields
    attempts = detail_data.get("attempts", [])
    if not attempts:
        warn("No payment attempts found in detail response")
        record_issue("WARNING", "tokenization", "No payment attempts in detail response")
        return

    attempt = attempts[0]
    payment_method_id = attempt.get("paymentMethodId", "")
    card_brand = attempt.get("cardBrand")
    last4 = attempt.get("last4")

    if payment_method_id.startswith("pm_"):
        ok(f"paymentMethodId starts with 'pm_': {payment_method_id}")
    else:
        fail(f"paymentMethodId does not start with 'pm_': {payment_method_id}")
        record_issue("CRITICAL", "tokenization", f"paymentMethodId format wrong: {payment_method_id}")

    if card_brand == "visa":
        ok(f"cardBrand is 'visa'")
    else:
        fail(f"cardBrand expected 'visa', got '{card_brand}'")
        record_issue("CRITICAL", "tokenization", f"cardBrand mismatch: expected visa, got {card_brand}")

    if last4 == "4242":
        ok(f"last4 is '4242'")
    else:
        fail(f"last4 expected '4242', got '{last4}'")
        record_issue("CRITICAL", "tokenization", f"last4 mismatch: expected 4242, got {last4}")


def test_webhook_signature_verification():
    """Test that correctly signed webhooks are accepted and incorrectly signed ones are rejected."""
    print(f"\n{BOLD}{'─'*60}{RESET}")
    print(f"{BOLD}  TEST: Webhook Signature Verification{RESET}")
    print(f"{BOLD}{'─'*60}{RESET}")

    # Use a dummy IA ID -- we just need to test the signature filter, not the business logic
    dummy_ia_id = "ia_test_sig_verification_dummy"

    # Test 1: Correctly signed webhook should be accepted (not 401)
    info("Testing correctly signed webhook...")
    good_resp = signed_webhook_post(dummy_ia_id, "success")
    if good_resp.status_code != 401:
        ok(f"Correctly signed webhook accepted (status {good_resp.status_code})")
    else:
        fail(f"Correctly signed webhook rejected with 401")
        record_issue("CRITICAL", "webhook_signature", "Valid signature rejected by webhook filter")

    # Test 2: Incorrectly signed webhook should be rejected with 401
    info("Testing incorrectly signed webhook...")
    wrong_secret = "wrong-secret-12345"
    payload = {"internalAttemptId": dummy_ia_id, "status": "success"}
    body = json.dumps(payload)
    timestamp = str(int(time.time()))
    bad_signature = hmac.new(
        wrong_secret.encode(), f"{timestamp}.{body}".encode(), hashlib.sha256
    ).hexdigest()

    bad_resp = requests.post(
        f"{BASE}/v1/webhooks/gateway",
        data=body,
        headers={
            "Content-Type": "application/json",
            "X-Gateway-Signature": bad_signature,
            "X-Gateway-Timestamp": timestamp,
        },
        timeout=5,
    )

    if bad_resp.status_code == 401:
        ok("Incorrectly signed webhook rejected with 401")
    else:
        fail(f"Incorrectly signed webhook returned {bad_resp.status_code}, expected 401")
        record_issue("CRITICAL", "webhook_signature", f"Bad signature returned {bad_resp.status_code} instead of 401")


def test_webhook_missing_headers():
    """Test that webhooks without signature/timestamp headers are rejected."""
    print(f"\n{BOLD}{'─'*60}{RESET}")
    print(f"{BOLD}  TEST: Webhook Missing Headers{RESET}")
    print(f"{BOLD}{'─'*60}{RESET}")

    payload = {"internalAttemptId": "ia_test_missing_headers", "status": "success"}
    body = json.dumps(payload)

    info("Sending webhook with NO signature or timestamp headers...")
    resp = requests.post(
        f"{BASE}/v1/webhooks/gateway",
        data=body,
        headers={"Content-Type": "application/json"},
        timeout=5,
    )

    if resp.status_code == 401:
        ok("Webhook with missing headers rejected with 401")
    else:
        fail(f"Webhook with missing headers returned {resp.status_code}, expected 401")
        record_issue("CRITICAL", "webhook_missing_headers", f"Missing headers returned {resp.status_code} instead of 401")


if __name__ == "__main__":
    reset_issues()

    test_webhook_replay()
    test_late_webhook_after_terminal()
    test_pci_service_isolation()
    test_pci_tokenization_flow()
    test_webhook_signature_verification()
    test_webhook_missing_headers()

    print_results()
