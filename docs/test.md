# Manual Testing Guide

How to manually test the payment gateway end-to-end using curl.

## Prerequisites

Start the full stack:

```bash
docker-compose up -d
```

Wait ~30 seconds for all services to become healthy, then verify:

```bash
curl http://localhost:8080/actuator/health  # acquirer-core-backend
curl http://localhost:8081/actuator/health  # external-payment-gateway
curl http://localhost:8082/actuator/health  # ledger-service
curl http://localhost:8087/actuator/health  # merchant-service
```

---

## Happy Path: Create → Confirm → Capture

### Step 1: Create a Merchant

```bash
curl -s -X POST http://localhost:8087/v1/merchants \
  -H "Content-Type: application/json" \
  -d '{"name": "Test Merchant"}' | jq .
```

Response:
```json
{
  "id": "merch_...",
  "name": "Test Merchant",
  "status": "active",
  "createdAt": "...",
  "updatedAt": "..."
}
```

Save the `id` — you'll need it for every payment intent.

### Step 2: Create a Payment Intent

```bash
curl -s -X POST http://localhost:8080/v1/payment_intents \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "merchantId": "merch_REPLACE_ME",
    "amount": 1000,
    "currency": "USD",
    "description": "Test payment",
    "customerEmail": "test@example.com"
  }' | jq .
```

Response — status will be `requires_confirmation`:
```json
{
  "id": "pi_...",
  "merchantId": "merch_...",
  "amount": 1000,
  "currency": "USD",
  "status": "requires_confirmation",
  "description": "Test payment",
  "customerEmail": "test@example.com",
  "createdAt": "...",
  "updatedAt": "..."
}
```

### Step 3: Confirm (Authorize) the Payment

```bash
curl -s -X POST http://localhost:8080/v1/payment_intents/pi_REPLACE_ME/confirm \
  -H "Content-Type: application/json" \
  -d '{
    "cardNumber": "4242424242424242",
    "cardholderName": "Test User",
    "expiryMonth": 12,
    "expiryYear": 2030,
    "cvc": "123"
  }' | jq .
```

The response returns immediately (status still `requires_confirmation`). The mock gateway processes asynchronously and sends a webhook callback within 1-5 seconds.

### Step 4: Poll Until Authorized

```bash
# Poll every 2 seconds until status changes
watch -n 2 "curl -s http://localhost:8080/v1/payment_intents/pi_REPLACE_ME | jq .status"
```

Or a single check:
```bash
curl -s http://localhost:8080/v1/payment_intents/pi_REPLACE_ME | jq .
```

Possible outcomes (mock gateway probabilities):
- **`authorized`** (70%) — ready to capture
- **`failed`** (20%) — authorization declined
- **`expired`** (10% timeout → retries → eventual expiry after 180s)

### Step 5: Capture the Payment

Only works when status is `authorized`:

```bash
curl -s -X POST http://localhost:8080/v1/payment_intents/pi_REPLACE_ME/capture \
  -H "Content-Type: application/json" | jq .
```

Response — status changes to `captured`:
```json
{
  "id": "pi_...",
  "status": "captured",
  ...
}
```

---

## Payment Intent State Machine

```
requires_confirmation
  ├── [confirm] ──→ authorized (webhook success, 70%)
  │                    └── [capture] ──→ captured
  ├── [confirm] ──→ failed (webhook failure, 20%)
  └── [no webhook] ──→ expired (timeout after 180s)
```

Transitions are strict — once `failed` or `expired`, the intent cannot be resurrected.

---

## Other Useful Endpoints

### List Payment Intents

```bash
# Offset-based pagination
curl -s "http://localhost:8080/v1/payment_intents?page=0&size=20" | jq .

# Cursor-based pagination (preferred for large datasets)
curl -s "http://localhost:8080/v1/payment_intents/cursor?limit=20" | jq .

# Next page using cursor
curl -s "http://localhost:8080/v1/payment_intents/cursor?starting_after=pi_LAST_ID&limit=20" | jq .
```

### List by Merchant

```bash
curl -s "http://localhost:8080/v1/payment_intents/merchant/merch_ID?page=0&size=20" | jq .

# Cursor-based
curl -s "http://localhost:8080/v1/payment_intents/cursor/merchant/merch_ID?limit=20" | jq .
```

### Get Payment Intent Detail (with attempts)

```bash
curl -s http://localhost:8080/v1/payment_intents/pi_ID | jq .
```

Returns the full hierarchy: PaymentIntent → PaymentAttempt → InternalAttempt (gateway interactions).

### List/Get Merchants

```bash
curl -s "http://localhost:8087/v1/merchants?page=0&size=20" | jq .
curl -s http://localhost:8087/v1/merchants/merch_ID | jq .
```

### Ledger Balances

```bash
# Global balances (total debits should equal total credits)
curl -s http://localhost:8082/v1/ledger/balances | jq .

# Entries for a specific payment
curl -s "http://localhost:8082/v1/ledger/entries?paymentIntentId=pi_ID" | jq .
```

### Dead Letter Events

```bash
# List unresolved CDC failures
curl -s "http://localhost:8082/v1/ledger/dead-letter-events?unresolvedOnly=true" | jq .

# Manually retry a dead letter event
curl -s -X POST http://localhost:8082/v1/ledger/dead-letter-events/ID/retry | jq .
```

---

## Idempotency Key

The `Idempotency-Key` header on `POST /v1/payment_intents` prevents duplicate payments on retries:

```bash
KEY="my-unique-key-123"

# First request — creates the intent
curl -s -X POST http://localhost:8080/v1/payment_intents \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $KEY" \
  -d '{"merchantId": "merch_...", "amount": 1000, "currency": "USD"}' | jq .

# Retry with same key + same body — returns cached response (no duplicate created)
curl -s -X POST http://localhost:8080/v1/payment_intents \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $KEY" \
  -d '{"merchantId": "merch_...", "amount": 1000, "currency": "USD"}' | jq .
```

Keys expire after 48 hours.

---

## Test Card Numbers

The mock gateway uses `cardBrand` (derived from BIN) for behavior routing:

| Card Number | Brand | Notes |
|-------------|-------|-------|
| `4242424242424242` | Visa | Standard test card |
| `5100000000000000` | Mastercard | BIN range 51-55 |
| `340000000000000` | Amex | BIN 34 or 37 |
| `6011000000000000` | Discover | BIN 6011 |

All cards go through the same mock gateway with 70% success / 20% failure / 10% timeout.

---

## Validation Error Examples

```bash
# Missing merchant ID
curl -s -X POST http://localhost:8080/v1/payment_intents \
  -H "Content-Type: application/json" \
  -d '{"amount": 1000, "currency": "USD"}' | jq .

# Invalid currency
curl -s -X POST http://localhost:8080/v1/payment_intents \
  -H "Content-Type: application/json" \
  -d '{"merchantId": "merch_...", "amount": 1000, "currency": "XYZ"}' | jq .

# Zero amount
curl -s -X POST http://localhost:8080/v1/payment_intents \
  -H "Content-Type: application/json" \
  -d '{"merchantId": "merch_...", "amount": 0, "currency": "USD"}' | jq .

# Capture on non-authorized intent
curl -s -X POST http://localhost:8080/v1/payment_intents/pi_NOT_AUTHORIZED/capture \
  -H "Content-Type: application/json" | jq .
```

All errors return structured responses:
```json
{
  "type": "invalid_request_error",
  "code": "parameter_invalid",
  "message": "...",
  "param": "fieldName"
}
```

---

## Monitoring

- **Grafana dashboards:** http://localhost:3000 (admin/admin)
- **Prometheus:** http://localhost:9090
- **Swagger UI:** http://localhost:8080/swagger-ui.html (acquirer-core-backend)
- **Swagger UI:** http://localhost:8087/swagger-ui.html (merchant-service)

---

## Running the Automated Test Suite

```bash
# Full stress test (requires stack running)
.venv/bin/python3 stress_test.py

# Modular API tests
.venv/bin/python3 -m pytest api_tests/ -v
```

---

## Database Access

```bash
# Connect to any service database
psql -h localhost -U postgres -d acquirer_core       # Payment intents
psql -h localhost -U postgres -d merchant_service    # Merchants
psql -h localhost -U postgres -d ledger              # Ledger entries
psql -h localhost -U postgres -d card_vault          # Encrypted card data (PCI)
psql -h localhost -U postgres -d token_service       # Payment methods
psql -h localhost -U postgres -d card_auth           # Internal attempts
```

Password: `postgres`
