"""
Observability tests: correlation IDs, business metrics, cursor pagination, and health/pool metrics.
"""

import uuid
import requests

from api_tests.conftest import *


def test_cursor_pagination():
    """Test cursor-based pagination endpoint for correctness and edge cases."""
    print(f"\n{BOLD}{'─'*60}{RESET}")
    print(f"{BOLD}  TEST: Cursor-Based Pagination{RESET}")
    print(f"{BOLD}{'─'*60}{RESET}")

    # Create a dedicated merchant for this test
    merchant = create_merchant(f"cursor-test-{uuid.uuid4().hex[:8]}")
    merchant_id = merchant["id"]
    info(f"Created merchant {merchant_id}")

    # Create 10 intents for this merchant
    created_ids = []
    for i in range(10):
        intent = create_intent(amount=(i + 1) * 100, currency="USD", merchant_id=merchant_id)
        created_ids.append(intent["id"])
    info(f"Created {len(created_ids)} intents")

    # ── Page 1: GET /v1/payment_intents/cursor?limit=3 ──
    resp = requests.get(f"{BASE}/v1/payment_intents/cursor", params={"limit": 3}, timeout=10)
    if resp.status_code != 200:
        fail(f"Cursor page 1 returned {resp.status_code}")
        record_issue("CRITICAL", "cursor_pagination", f"Page 1 returned {resp.status_code}")
        return

    page1 = resp.json()
    if "data" not in page1:
        fail("Response missing 'data' field")
        record_issue("CRITICAL", "cursor_pagination", "Response missing 'data' field")
        return
    if "hasMore" not in page1:
        fail("Response missing 'hasMore' field")
        record_issue("CRITICAL", "cursor_pagination", "Response missing 'hasMore' field")
        return

    ok("Response has 'data' and 'hasMore' fields")

    if len(page1["data"]) == 3:
        ok(f"Page 1 returned exactly 3 items")
    else:
        fail(f"Page 1 returned {len(page1['data'])} items, expected 3")
        record_issue("WARNING", "cursor_pagination", f"Page 1 returned {len(page1['data'])} items instead of 3")

    # ── Page 2: use last item's ID as starting_after ──
    last_id = page1["data"][-1]["id"]
    resp2 = requests.get(
        f"{BASE}/v1/payment_intents/cursor",
        params={"limit": 3, "starting_after": last_id},
        timeout=10,
    )
    if resp2.status_code != 200:
        fail(f"Cursor page 2 returned {resp2.status_code}")
        record_issue("CRITICAL", "cursor_pagination", f"Page 2 returned {resp2.status_code}")
        return

    page2 = resp2.json()
    page1_ids = {item["id"] for item in page1["data"]}
    page2_ids = {item["id"] for item in page2["data"]}
    overlap = page1_ids & page2_ids
    if not overlap:
        ok("No duplicate IDs between page 1 and page 2")
    else:
        fail(f"Found {len(overlap)} duplicate IDs between pages: {overlap}")
        record_issue("CRITICAL", "cursor_pagination", f"Duplicate IDs across pages: {overlap}")

    # ── Merchant-scoped cursor: GET /v1/payment_intents/cursor/merchant/{merchantId}?limit=5 ──
    resp_merchant = requests.get(
        f"{BASE}/v1/payment_intents/cursor/merchant/{merchant_id}",
        params={"limit": 5},
        timeout=10,
    )
    if resp_merchant.status_code != 200:
        fail(f"Merchant cursor endpoint returned {resp_merchant.status_code}")
        record_issue("CRITICAL", "cursor_pagination", f"Merchant cursor returned {resp_merchant.status_code}")
        return

    merchant_page = resp_merchant.json()
    all_belong = all(item["merchantId"] == merchant_id for item in merchant_page["data"])
    if all_belong:
        ok(f"All {len(merchant_page['data'])} results belong to merchant {merchant_id}")
    else:
        wrong = [item["id"] for item in merchant_page["data"] if item["merchantId"] != merchant_id]
        fail(f"Some results belong to wrong merchant: {wrong}")
        record_issue("CRITICAL", "cursor_pagination", f"Merchant-scoped results contain wrong merchant IDs")

    # ── Invalid cursor: starting_after=nonexistent-id ──
    resp_bad = requests.get(
        f"{BASE}/v1/payment_intents/cursor",
        params={"starting_after": "nonexistent-id"},
        timeout=10,
    )
    if resp_bad.status_code == 400:
        ok("Nonexistent cursor ID correctly returns 400")
    else:
        warn(f"Nonexistent cursor ID returned {resp_bad.status_code}, expected 400")
        record_issue("WARNING", "cursor_pagination", f"Nonexistent cursor returned {resp_bad.status_code} instead of 400")


def test_correlation_ids():
    """Test that X-Correlation-Id is auto-generated and echoed back."""
    print(f"\n{BOLD}{'─'*60}{RESET}")
    print(f"{BOLD}  TEST: Correlation IDs{RESET}")
    print(f"{BOLD}{'─'*60}{RESET}")

    merchant_id = ensure_test_merchant()

    # ── Test 1: No correlation header sent → auto-generated in response ──
    info("Creating intent WITHOUT X-Correlation-Id header...")
    resp = requests.post(
        f"{BASE}/v1/payment_intents",
        json={"merchantId": merchant_id, "amount": 1000, "currency": "USD"},
        timeout=10,
    )
    resp.raise_for_status()

    auto_corr = resp.headers.get("X-Correlation-Id")
    if auto_corr:
        ok(f"Auto-generated X-Correlation-Id: {auto_corr}")
    else:
        warn("No X-Correlation-Id in response when none was sent")
        record_issue("WARNING", "correlation_ids", "No auto-generated X-Correlation-Id in response")

    # ── Test 2: Custom correlation header sent → echoed back unchanged ──
    custom_id = f"test-corr-{uuid.uuid4()}"
    info(f"Creating intent WITH X-Correlation-Id: {custom_id}")
    resp2 = requests.post(
        f"{BASE}/v1/payment_intents",
        json={"merchantId": merchant_id, "amount": 2000, "currency": "EUR"},
        headers={"X-Correlation-Id": custom_id},
        timeout=10,
    )
    resp2.raise_for_status()

    echoed = resp2.headers.get("X-Correlation-Id")
    if echoed == custom_id:
        ok(f"Custom X-Correlation-Id echoed back unchanged")
    elif echoed:
        fail(f"X-Correlation-Id changed: sent '{custom_id}', got '{echoed}'")
        record_issue("CRITICAL", "correlation_ids", f"Correlation ID modified: {custom_id} -> {echoed}")
    else:
        warn("No X-Correlation-Id in response when custom header was sent")
        record_issue("WARNING", "correlation_ids", "Custom X-Correlation-Id not echoed back")


def test_business_metrics():
    """Test that custom business metrics are exposed on the Prometheus endpoint."""
    print(f"\n{BOLD}{'─'*60}{RESET}")
    print(f"{BOLD}  TEST: Business Metrics (Prometheus){RESET}")
    print(f"{BOLD}{'─'*60}{RESET}")

    resp = requests.get(f"{BASE}/actuator/prometheus", timeout=10)
    if resp.status_code != 200:
        fail(f"Prometheus endpoint returned {resp.status_code}")
        record_issue("CRITICAL", "business_metrics", f"Prometheus endpoint returned {resp.status_code}")
        return

    ok(f"Prometheus endpoint accessible ({len(resp.text)} bytes)")
    prom_text = resp.text

    # Micrometer converts dots to underscores in Prometheus exposition format.
    # It may also append _total, _seconds, _bucket, etc.
    expected_metrics = [
        "payment_intents_created",
        "payment_intents_confirm_duration",
        "payment_intents_capture_requested",
        "payment_webhooks_processed",
    ]

    for metric in expected_metrics:
        if metric in prom_text:
            ok(f"Found metric: {metric}")
        else:
            warn(f"Metric not found: {metric}")
            record_issue("WARNING", "business_metrics", f"Metric '{metric}' not found in Prometheus output")


def test_hikari_pool_metrics():
    """Verify HikariCP connection pool metrics are exposed via actuator."""
    print(f"\n{BOLD}{'─'*60}{RESET}")
    print(f"{BOLD}  TEST: HikariCP Pool Metrics{RESET}")
    print(f"{BOLD}{'─'*60}{RESET}")

    # ── Check hikaricp.connections.max ──
    resp_max = requests.get(f"{BASE}/actuator/metrics/hikaricp.connections.max", timeout=10)
    if resp_max.status_code != 200:
        warn(f"hikaricp.connections.max endpoint returned {resp_max.status_code}")
        record_issue("WARNING", "hikari_pool", f"hikaricp.connections.max returned {resp_max.status_code}")
    else:
        data = resp_max.json()
        max_val = None
        for m in data.get("measurements", []):
            if m["statistic"] == "VALUE":
                max_val = m["value"]
                break

        if max_val is not None:
            expected_pool_size = 80
            if int(max_val) == expected_pool_size:
                ok(f"hikaricp.connections.max = {int(max_val)} (matches expected {expected_pool_size})")
            else:
                info(f"hikaricp.connections.max = {int(max_val)} (expected {expected_pool_size})")
                record_issue("INFO", "hikari_pool", f"Pool max is {int(max_val)}, expected {expected_pool_size}")
        else:
            warn("Could not extract VALUE from hikaricp.connections.max")
            record_issue("WARNING", "hikari_pool", "No VALUE measurement in hikaricp.connections.max")

    # ── Check hikaricp.connections.active exists ──
    resp_active = requests.get(f"{BASE}/actuator/metrics/hikaricp.connections.active", timeout=10)
    if resp_active.status_code == 200:
        ok("hikaricp.connections.active metric exists")
    else:
        warn(f"hikaricp.connections.active returned {resp_active.status_code}")
        record_issue("WARNING", "hikari_pool", f"hikaricp.connections.active returned {resp_active.status_code}")


def test_graceful_shutdown_config():
    """Verify graceful shutdown configuration and actuator health endpoint."""
    print(f"\n{BOLD}{'─'*60}{RESET}")
    print(f"{BOLD}  TEST: Graceful Shutdown Config{RESET}")
    print(f"{BOLD}{'─'*60}{RESET}")

    # Try to read server.shutdown from configprops (may not be exposed by default)
    resp_config = requests.get(f"{BASE}/actuator/configprops", timeout=10)
    if resp_config.status_code == 200:
        config_text = resp_config.text
        if "server.shutdown" in config_text or "graceful" in config_text.lower():
            ok("Graceful shutdown config found in configprops")
        else:
            info("server.shutdown not found in configprops (may not be exposed)")
    else:
        info(f"configprops endpoint returned {resp_config.status_code} (may not be enabled)")

    # Fallback: verify the actuator health endpoint works
    resp_health = requests.get(f"{BASE}/actuator/health", timeout=10)
    if resp_health.status_code == 200:
        ok(f"Actuator health endpoint returns 200")
        health_data = resp_health.json()
        status = health_data.get("status")
        if status == "UP":
            ok(f"Health status: {status}")
        else:
            warn(f"Health status: {status}")
            record_issue("WARNING", "graceful_shutdown", f"Health status is {status}, expected UP")
    else:
        fail(f"Actuator health endpoint returned {resp_health.status_code}")
        record_issue("CRITICAL", "graceful_shutdown", f"Health endpoint returned {resp_health.status_code}")


if __name__ == "__main__":
    reset_issues()

    test_cursor_pagination()
    test_correlation_ids()
    test_business_metrics()
    test_hikari_pool_metrics()
    test_graceful_shutdown_config()

    print_results()
