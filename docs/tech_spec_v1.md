# Payment Orchestrator — Technical Specification v1

## 1. Overview

A simplified Payment Orchestrator implementing the Auth + Capture flow against a mocked Visa gateway. No real card network integration; all gateway interactions are simulated in-process.

**Stack**
| Layer | Technology |
|---|---|
| Backend | Kotlin 1.9 + Spring Boot 3.2 |
| Persistence | PostgreSQL via Spring Data JPA / Hibernate 6 |
| Async | Spring `@Async` + `ScheduledThreadPoolExecutor` |
| Frontend | React 18 + Vite |
| Scheduler | Spring `@Scheduled` |

---

## 2. Project Structure

```
payment-gateway/
├── acquirer-core-backend/               # Payment orchestrator (port 8080)
│   ├── build.gradle.kts
│   ├── src/main/kotlin/com/payment/gateway/
│   │   ├── Application.kt              # @SpringBootApplication, @EnableAsync, @EnableScheduling
│   │   ├── config/
│   │   │   ├── AsyncConfig.kt          # ThreadPoolTaskExecutor (4 core / 8 max)
│   │   │   └── WebConfig.kt            # CORS for localhost:5173 / 3000
│   │   ├── controller/
│   │   │   ├── PaymentIntentController.kt
│   │   │   └── WebhookController.kt
│   │   ├── dto/
│   │   │   └── Dtos.kt                 # Request / response DTOs + extension mappers
│   │   ├── entity/
│   │   │   ├── PaymentIntent.kt
│   │   │   ├── PaymentAttempt.kt
│   │   │   ├── InternalAttempt.kt
│   │   │   └── IdempotencyKey.kt
│   │   ├── repository/
│   │   │   └── Repositories.kt
│   │   └── service/
│   │       ├── PaymentIntentService.kt
│   │       ├── GatewayClient.kt
│   │       ├── DispatchMarkService.kt
│   │       ├── PaymentCleanupScheduler.kt
│   │       └── PaymentExpiryService.kt
│   └── src/main/resources/
│       └── application.properties
├── external-payment-gateway/            # Mock acquirer gateway (port 8081)
│   ├── build.gradle.kts
│   ├── src/main/kotlin/com/payment/gateway/mock/
│   │   ├── MockGatewayApplication.kt
│   │   ├── controller/GatewayController.kt
│   │   ├── dto/Dtos.kt
│   │   └── service/GatewaySimulatorService.kt
│   └── src/main/resources/
│       └── application.properties
├── ledger-service/                      # Double-entry ledger (port 8082)
│   ├── build.gradle.kts
│   ├── src/main/kotlin/com/payment/ledger/
│   │   ├── LedgerApplication.kt
│   │   ├── entity/
│   │   ├── repository/
│   │   ├── service/
│   │   └── controller/
│   └── src/main/resources/
│       └── application.properties
├── frontend/
│   └── src/
│       ├── api/client.js
│       ├── components/
│       │   ├── CreateForm.jsx
│       │   ├── ConfirmForm.jsx
│       │   ├── CaptureForm.jsx
│       │   ├── PaymentIntentDetail.jsx  # polls every 1.5s
│       │   └── StatusBadge.jsx
│       └── App.jsx
├── docker-compose.yml                   # Full stack: PG, Kafka, Debezium, services
├── stress_test.py                       # 12-suite correctness + load test
└── docs/
    ├── tech_spec_v1.md                  # this file
    └── future_improvements.md
```

---

## 3. Data Model

### PaymentIntent
| Field | Type | Notes |
|---|---|---|
| id | UUID (PK) | |
| amount | BIGINT | Minor currency units |
| currency | VARCHAR | ISO 4217, uppercased |
| status | ENUM | See state machine §5 |
| createdAt | TIMESTAMP | |
| updatedAt | TIMESTAMP | Updated on every state change |

### PaymentAttempt
| Field | Type | Notes |
|---|---|---|
| id | UUID (PK) | |
| paymentIntentId | UUID (FK) | |
| paymentMethod | VARCHAR | e.g. `card_4242` |
| status | ENUM | `pending / authorized / captured / failed / expired` |
| createdAt | TIMESTAMP | |
| updatedAt | TIMESTAMP | |

### InternalAttempt
| Field | Type | Notes |
|---|---|---|
| id | UUID (PK) | |
| paymentAttemptId | UUID (FK) | |
| provider | VARCHAR | Always `mock-visa` |
| type | ENUM | `auth / capture` |
| status | ENUM | `pending / success / failure / timeout / expired` |
| retryCount | INT | Incremented on each timeout retry; persisted immediately |
| requestPayload | TEXT (JSON) | Snapshot of what was sent to gateway |
| responsePayload | TEXT (JSON) | Snapshot of gateway response |
| createdAt | TIMESTAMP | |
| updatedAt | TIMESTAMP | |

### IdempotencyKey
| Field | Type | Notes |
|---|---|---|
| key | VARCHAR (PK) | From `Idempotency-Key` header |
| requestHash | VARCHAR | SHA-256 of `intentId + paymentMethod` |
| response | TEXT (JSON) | Cached serialised `PaymentIntentResponse` |
| createdAt | TIMESTAMP | |

---

## 4. API Reference

### POST /payment_intents
Creates a new PaymentIntent.

**Request**
```json
{ "amount": 1000, "currency": "USD" }
```
**Response** `201`
```json
{ "id": "uuid", "amount": 1000, "currency": "USD", "status": "requires_confirmation", ... }
```

---

### POST /payment_intents/{id}/confirm
Confirms a PaymentIntent and dispatches to the mock gateway asynchronously. Returns immediately; status is updated via webhook.

**Headers** `Idempotency-Key: <uuid>` (optional but recommended)

**Request**
```json
{ "paymentMethod": "card_4242" }
```
**Response** `200`
```json
{ "id": "uuid", "status": "requires_confirmation", ... }
```

**Idempotency behaviour**
- Same key + same payload → cached response, no duplicate attempt created
- Same key + different payload → `400 Bad Request`

---

### POST /payment_intents/{id}/capture
Captures an authorized PaymentIntent. Precondition: `status = authorized`.

**Response** `200`
```json
{ "id": "uuid", "status": "authorized", ... }
```
Status transitions to `captured` asynchronously via webhook.

**Error cases**
- `status ≠ authorized` → `409 Conflict`
- `status = expired` → `409 Conflict`

---

### GET /payment_intents/{id}
Returns the full detail view including nested PaymentAttempts and InternalAttempts.

---

### GET /payment_intents
Lists all PaymentIntents (summary only).

---

### POST /webhooks/gateway
Internal endpoint called by the mock gateway after processing an InternalAttempt.

**Request**
```json
{ "internalAttemptId": "uuid", "status": "success | failure | timeout" }
```

---

## 5. State Machines

### PaymentIntent

```
requires_confirmation ──── webhook:success ──→ authorized ──── webhook:success ──→ captured
        │                                          │
        ├──── webhook:failure ──→ failed            └──── (capture gateway failure) stays authorized
        │
        └──── scheduler (> 30s) ──→ expired (terminal)
```

### PaymentAttempt

```
pending ──── auth success ──→ authorized ──── capture success ──→ captured
   │                │
   └── auth failure ┘── expired (scheduler)
       → failed
```

### InternalAttempt

```
pending ──── success ──→ SUCCESS (terminal)
   │
   ├──── failure ──→ FAILURE (terminal)
   │
   ├──── timeout ──→ TIMEOUT → retry (backoff) ──→ success/failure
   │                   max 2 retries; failure on exhaustion
   │
   └──── scheduler (> timeout threshold) ──→ EXPIRED (terminal)
```

**Disallowed transitions**
- `expired → authorized` — blocked at webhook handler (EXPIRED is terminal)
- `expired → captured` — capture endpoint returns 409 on expired intent

---

## 6. Mock Gateway

**File:** `MockGatewayService.kt`

The gateway is simulated in-process using a `ScheduledThreadPoolExecutor`. After 1–2 seconds it calls back to `/webhooks/gateway`.

### Outcome probabilities

| Type | Success | Failure | Timeout |
|---|---|---|---|
| AUTH | 70% | 20% | 10% |
| CAPTURE | 90% | 5% | 5% |

Auth uses a wider failure band to exercise the retry and failure paths. Capture has a higher success rate since it settles already-reserved funds.

### Retry logic (timeouts only)

- Max 2 retries with exponential backoff: 1s → 2s
- `retryCount` is persisted to the database before each retry so it survives a process restart
- After max retries exhausted: fires a `failure` webhook

### Special payment methods (testing)

| Payment method prefix | Gateway behaviour |
|---|---|
| `card_hang` | Gateway never fires the webhook — used to exercise the expiry scheduler |
| All others | Normal probabilistic outcome |

---

## 7. Idempotency

**Scope:** `POST /payment_intents/{id}/confirm`

1. If `Idempotency-Key` header is present, compute `SHA-256(intentId + paymentMethod)` as the request hash.
2. Look up the key in `idempotency_keys`.
   - **Key exists, hash matches** → return cached response, create no new records.
   - **Key exists, hash differs** → `400 Bad Request` (key reused with different payload).
   - **Key absent** → process normally, store key + hash + serialised response.

Concurrent replays of the same key are safe — they all return the cached response.

---

## 8. Race Condition Handling

Two race conditions were found via stress testing and fixed.

### Double-confirm race

**Symptom:** 10 concurrent `POST /confirm` on the same intent each created a `PaymentAttempt`.

**Root cause:** Status check (`requires_confirmation?`) and record creation were not atomic.

**Fix:** `PESSIMISTIC_WRITE` lock on `PaymentIntent` row via `findByIdForUpdate`, plus an existence check for an already-created `PaymentAttempt` inside the lock.

```kotlin
// PaymentIntentRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM PaymentIntent p WHERE p.id = :id")
fun findByIdForUpdate(id: String): Optional<PaymentIntent>
```

### Double-capture race

**Symptom:** 10 concurrent `POST /capture` each created a `capture` InternalAttempt.

**Fix:** Same `PESSIMISTIC_WRITE` lock, plus a guard checking whether a `CAPTURE` InternalAttempt already exists for the PaymentAttempt before creating a new one.

---

## 9. Webhook Idempotency (Replay Safety)

**Problem found in stress testing:** Replaying a `success` webhook on an already-`failed` intent flipped it back to `authorized`.

**Fix:** Terminal state guard at the top of `handleWebhook`:

```kotlin
val terminalStatuses = setOf(SUCCESS, FAILURE, EXPIRED)
if (internalAttempt.status in terminalStatuses) {
    log.warn("Ignoring webhook for terminal InternalAttempt ...")
    return
}
```

`EXPIRED` is explicitly included so a late webhook after scheduler expiry is silently dropped.

---

## 10. Expiry Scheduler

**Files:** `PaymentCleanupScheduler.kt`, `PaymentExpiryService.kt`

### Configuration (`application.properties`)

```properties
payment.timeout.auth-seconds=30       # auth webhook deadline
payment.timeout.capture-seconds=60    # capture webhook deadline
payment.cleanup.interval-seconds=10   # sweep frequency
payment.cleanup.batch-size=50         # intents processed per sweep
```

### Auth sweep (`sweepExpiredAuthAttempts`)

Runs every `interval-seconds`. Finds up to `batch-size` `REQUIRES_CONFIRMATION` intents whose `updatedAt < now - auth-timeout`. For each:

1. Acquires `PESSIMISTIC_WRITE` lock (re-validates status — webhook may have just resolved it).
2. Marks all `PENDING/TIMEOUT` InternalAttempts → `EXPIRED`.
3. Marks PaymentAttempt → `EXPIRED`.
4. Marks PaymentIntent → `EXPIRED`.

Each intent is expired in its own `REQUIRES_NEW` transaction (via `PaymentExpiryService`) so one failure doesn't roll back the whole batch.

### Capture sweep (`sweepExpiredCaptureAttempts`)

Finds `CAPTURE` InternalAttempts with `status IN (PENDING, TIMEOUT)` and `createdAt < now - capture-timeout`. Marks them `EXPIRED`. The `PaymentIntent` is intentionally left as `AUTHORIZED` — the merchant can submit a new capture.

### Why a separate `PaymentExpiryService` bean?

Spring's `@Transactional(propagation = REQUIRES_NEW)` is applied via AOP proxy. Self-invocation within the same bean bypasses the proxy and ignores the annotation. The per-intent expiry logic is in a separate `@Service` so the proxy intercepts each call and starts a genuine nested transaction.

---

## 11. Frontend

Built with React 18 + Vite. No external UI library.

- **CreateForm** — amount + currency inputs, fires `POST /payment_intents`
- **ConfirmForm** — payment method selector, optional idempotency key toggle
- **CaptureForm** — enabled only when status = `authorized`
- **PaymentIntentDetail** — polls `GET /payment_intents/{id}` every 1.5s, renders full attempt tree including InternalAttempt retry counts and statuses; stops polling on terminal state

---

## 12. Stress & Correctness Tests

**File:** `stress_test.py` — run with `python3 stress_test.py` from the `payment-gateway/` directory (requires `.venv`).

| # | Test | What it verifies |
|---|---|---|
| 1 | Throughput & latency | 40 sequential creates; P50/P95/P99; error rate |
| 2 | Concurrent creates | 50 threads; no errors, no duplicate IDs |
| 3 | Double-confirm race | 10 concurrent confirms on same intent → exactly 1 PaymentAttempt |
| 4 | Idempotency correctness | Replay returns cache; different payload rejected; 10 concurrent replays consistent |
| 5 | Double-capture race | 10 concurrent captures on authorized intent → exactly 1 capture InternalAttempt |
| 6 | State machine guards | Capture on non-authorized intent → 409 |
| 7 | Consistency under load | 20 concurrent confirms; all 20 intents fully consistent after webhooks settle |
| 8 | Webhook replay | Duplicate webhook on terminal attempt → status unchanged |
| 9 | Auth expiry | `card_hang` confirm → intent expires within `auth-timeout + interval` seconds |
| 10 | Late webhook blocked | Success webhook after expiry → status stays `expired` |

### Running the tests

```bash
# One-time setup
cd payment-gateway
python3 -m venv .venv
.venv/bin/pip install requests

# Run
.venv/bin/python3 stress_test.py
```

---

## 13. How to Run

### Prerequisites

- Java 17+
- Docker (PostgreSQL container on port 5432 with user/password `postgres`)
- Node.js 18+

### Database

```bash
docker exec <postgres-container> psql -U postgres -c "CREATE DATABASE payment_gateway;"
```

### Backend (Acquirer Core)

```bash
cd payment-gateway/acquirer-core-backend
./gradlew bootRun
# Starts on http://localhost:8080
```

### External Payment Gateway

```bash
cd payment-gateway/external-payment-gateway
./gradlew bootRun
# Starts on http://localhost:8081
```

### Frontend

```bash
cd payment-gateway/frontend
npm install
npm run dev
# Starts on http://localhost:5173
```

---

## 14. Known Limitations

| Item | Note |
|---|---|
| `card_hang` is not gated | Any client can submit `card_hang` and intentionally stall an intent. In production this would be removed or access-controlled. |
| Capture failure not retried | If the mock gateway returns `failure` for a capture, the intent stays `AUTHORIZED`. The merchant must re-submit capture manually. PRD does not specify capture failure recovery. |
| Single-region only | No distributed locking; `PESSIMISTIC_WRITE` only works with a single DB primary. |
| No auth/authz | All endpoints are unauthenticated. |
| Webhook endpoint is public | `/webhooks/gateway` accepts any payload with no HMAC verification. |
