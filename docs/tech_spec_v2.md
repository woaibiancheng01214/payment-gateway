# Payment Gateway — Technical Specification v2

## 1. Overview

A payment gateway system with seven Kotlin/Spring Boot 3.2 microservices split into PCI and non-PCI zones, backed by PostgreSQL, Redis, Kafka, and Debezium CDC. All services run on JDK 17 with Gradle (Kotlin DSL).

**Stack**
| Layer | Technology |
|---|---|
| Backend | Kotlin 1.9 + Spring Boot 3.2 (7 microservices) |
| Persistence | PostgreSQL 16 (6 databases, logical replication for CDC) |
| Cache / Locking | Redis 7 (idempotency cache + distributed locks) |
| Messaging | Kafka (KRaft mode) + Debezium CDC |
| Monitoring | Prometheus + Grafana + cAdvisor + postgres-exporter |

---

## 2. Architecture

### Service Topology

```
                        ┌─────────────────────────────────────────────────┐
                        │              Non-PCI Zone (external)            │
                        │                                                 │
  Client ──────────────▶│  acquirer-core-backend :8080                    │
                        │       │                                         │
                        │       │  external-payment-gateway :8081         │
                        │       │       ▲                                  │
                        │       │       │ webhook                         │
                        │  ledger-service :8082  (Kafka consumer)         │
                        └───────┼───────┼─────────────────────────────────┘
                                │       │
                        ┌───────┼───────┼─────────────────────────────────┐
                        │       ▼       │       PCI Zone (internal only)  │
                        │                                                 │
                        │  card-vault-service :8086  (AES-256-GCM)       │
                        │  token-service :8084       (payment methods)    │
                        │  card-auth-service :8085   (auth/capture)       │
                        │       │                                         │
                        │       └──────▶ external-payment-gateway         │
                        └─────────────────────────────────────────────────┘
```

PCI zone services have **no external port mappings** — accessible only within the Docker network.

### Services

| Service | Zone | Port | Database | Purpose |
|---------|------|------|----------|---------|
| acquirer-core-backend | Non-PCI | 8080 (external) | `acquirer_core` | Payment orchestration: create/confirm/capture intents, webhook routing, scheduling |
| external-payment-gateway | Non-PCI | 8081 (external) | — | Mock gateway simulator with probabilistic outcomes and latency |
| ledger-service | Non-PCI | 8082 (external) | `ledger` | Double-entry ledger driven by Debezium CDC events from Kafka |
| card-vault-service | PCI | 8086 (internal) | `card_vault` | Encrypted card data storage (AES-256-GCM) |
| token-service | PCI | 8084 (internal) | `token_service` | Payment method references (brand, last4, expiry — no PAN) |
| card-auth-service | PCI | 8085 (internal) | `card_auth` | Authorization/capture orchestration, gateway dispatch, InternalAttempt lifecycle |
| merchant-service | Non-PCI | 8087 (external) | `merchant_service` | Merchant CRUD — all PaymentIntents scoped to a merchant |

---

## 2.1 System Requirements

| | Minimum | Recommended |
|---|---|---|
| **CPU** | 2 cores | 4+ cores |
| **RAM** | 6 GB | 8+ GB |
| **Disk** | 10 GB | 20 GB |
| **Docker** | Docker Compose v2, 4 GB allocated | Docker Compose v2, 6+ GB allocated |
| **OS** | macOS 12+ / Linux (x86_64 or arm64) | macOS 14+ / Linux |

The full stack runs 15 containers (7 application services + 8 infrastructure). Minimum spec runs all containers but with degraded throughput (~30 TPS). Recommended spec sustains 100 TPS with headroom for monitoring and CDC.

---

## 3. Project Structure

```
payment-gateway/
├── acquirer-core-backend/                # Payment orchestrator (port 8080)
│   ├── build.gradle.kts
│   ├── Dockerfile
│   ├── src/main/kotlin/com/payment/gateway/
│   │   ├── Application.kt
│   │   ├── config/
│   │   │   ├── AsyncConfig.kt           # ThreadPoolTaskExecutor
│   │   │   ├── WebConfig.kt             # CORS
│   │   │   ├── ShedLockConfig.kt        # Distributed scheduler locks
│   │   │   └── GlobalExceptionHandler.kt
│   │   ├── controller/
│   │   │   ├── PaymentIntentController.kt
│   │   │   └── WebhookController.kt
│   │   ├── dto/
│   │   ├── entity/
│   │   │   ├── PaymentIntent.kt
│   │   │   └── PaymentAttempt.kt
│   │   ├── repository/
│   │   └── service/
│   │       ├── PaymentIntentService.kt
│   │       ├── VaultClient.kt           # RPC to card-vault-service
│   │       ├── TokenClient.kt           # RPC to token-service
│   │       ├── AuthClient.kt            # RPC to card-auth-service
│   │       ├── PaymentCleanupScheduler.kt
│   │       └── PaymentExpiryService.kt
│   └── src/main/resources/
│       ├── application.properties
│       └── db/migration/
│           ├── V1__initial_schema.sql
│           └── V2__shedlock_table.sql
│
├── card-vault-service/                   # PCI: encrypted card storage (port 8086)
│   ├── build.gradle.kts
│   ├── Dockerfile
│   ├── src/main/kotlin/com/payment/vault/
│   │   ├── VaultApplication.kt
│   │   ├── entity/CardData.kt
│   │   ├── repository/CardDataRepository.kt
│   │   ├── service/
│   │   │   ├── EncryptionService.kt     # AES-256-GCM encrypt/decrypt
│   │   │   └── VaultService.kt
│   │   ├── dto/Dtos.kt
│   │   ├── controller/VaultInternalController.kt
│   │   └── config/GlobalExceptionHandler.kt
│   └── src/main/resources/
│       ├── application.properties
│       └── db/migration/V1__initial_schema.sql
│
├── token-service/                        # PCI: payment method tokens (port 8084)
│   ├── build.gradle.kts
│   ├── Dockerfile
│   ├── src/main/kotlin/com/payment/token/
│   │   ├── TokenApplication.kt
│   │   ├── entity/
│   │   │   ├── PaymentMethod.kt
│   │   │   ├── CardBrand.kt             # Enum: VISA, MASTERCARD, AMEX, DISCOVER, UNKNOWN
│   │   │   └── PaymentMethodStatus.kt   # Enum: ACTIVE, EXPIRED, REVOKED
│   │   ├── repository/PaymentMethodRepository.kt
│   │   ├── service/PaymentMethodService.kt
│   │   ├── dto/Dtos.kt
│   │   ├── controller/TokenInternalController.kt
│   │   └── config/GlobalExceptionHandler.kt
│   └── src/main/resources/
│       ├── application.properties
│       └── db/migration/V1__initial_schema.sql
│
├── card-auth-service/                    # PCI: auth/capture dispatch (port 8085)
│   ├── build.gradle.kts
│   ├── Dockerfile
│   ├── src/main/kotlin/com/payment/auth/
│   │   ├── AuthApplication.kt
│   │   ├── entity/
│   │   │   ├── InternalAttempt.kt
│   │   │   ├── InternalAttemptStatus.kt  # Enum: PENDING, SUCCESS, FAILURE, TIMEOUT, EXPIRED
│   │   │   ├── InternalAttemptType.kt    # Enum: AUTH, CAPTURE
│   │   │   └── Provider.kt              # Enum: MOCK_VISA
│   │   ├── repository/InternalAttemptRepository.kt
│   │   ├── service/
│   │   │   ├── AuthService.kt           # Auth/capture orchestration
│   │   │   ├── GatewayClient.kt         # RPC to external-payment-gateway
│   │   │   ├── VaultClient.kt           # RPC to card-vault-service
│   │   │   ├── TokenClient.kt           # RPC to token-service
│   │   │   └── AuthCleanupScheduler.kt  # Sweep expired captures + retry undispatched
│   │   ├── dto/Dtos.kt
│   │   ├── controller/AuthInternalController.kt
│   │   └── config/GlobalExceptionHandler.kt
│   └── src/main/resources/
│       ├── application.properties
│       └── db/migration/V1__initial_schema.sql
│
├── external-payment-gateway/             # Mock acquirer gateway (port 8081)
│   ├── build.gradle.kts
│   ├── Dockerfile
│   ├── src/main/kotlin/com/payment/gateway/mock/
│   │   ├── MockGatewayApplication.kt
│   │   ├── controller/GatewayController.kt
│   │   ├── dto/Dtos.kt
│   │   └── service/GatewaySimulatorService.kt
│   └── src/main/resources/application.properties
│
├── ledger-service/                       # Double-entry ledger (port 8082)
│   ├── build.gradle.kts
│   ├── Dockerfile
│   ├── src/main/kotlin/com/payment/ledger/
│   │   ├── LedgerApplication.kt
│   │   ├── entity/
│   │   ├── repository/
│   │   ├── service/
│   │   └── controller/
│   └── src/main/resources/
│       ├── application.properties
│       └── db/migration/
│           ├── V1__initial_schema.sql
│           └── V2__dead_letter_events.sql
│
├── frontend/                             # React 18 + Vite
│   └── src/
│
├── infra/
│   ├── init-db.sql                       # Creates 5 databases
│   ├── debezium-connector.json           # CDC connector config
│   ├── secrets/                          # Docker secrets
│   │   ├── db_password.txt
│   │   ├── webhook_secret.txt
│   │   └── vault_encryption_key.txt
│   └── monitoring/
│       ├── prometheus.yml                # Scrape config (5s interval)
│       └── grafana/
│           ├── provisioning/
│           │   ├── datasources/prometheus.yml
│           │   └── dashboards/dashboards.yml
│           └── dashboards/
│               ├── jvm-overview.json     # JVM heap, GC, threads, HTTP, HikariCP
│               ├── docker-containers.json # Container CPU/memory/network/disk
│               └── postgres.json         # PG connections, txn rate, locks, cache
│
├── docker-compose.yml                    # Full stack: 7 services + infra + monitoring
├── api_tests/                            # 27 integration tests across 6 modules
└── docs/
    ├── tech_spec_v1.md
    └── tech_spec_v2.md                   # this file
```

---

## 4. Data Model

### PaymentIntent (acquirer-core-backend → `acquirer_core` DB)

| Field | Type | Notes |
|---|---|---|
| id | UUID (PK) | |
| amount | BIGINT | Minor currency units |
| currency | VARCHAR | ISO 4217 enum |
| status | ENUM | `REQUIRES_CONFIRMATION`, `AUTHORIZED`, `CAPTURED`, `FAILED`, `EXPIRED` |
| paymentMethodId | UUID (nullable) | FK to token-service PaymentMethod |
| cardBrand | VARCHAR (nullable) | Denormalized from PaymentMethod (e.g. `VISA`) |
| cardLast4 | VARCHAR (nullable) | Denormalized from PaymentMethod (e.g. `4242`) |
| createdAt / updatedAt | TIMESTAMP | |

### PaymentAttempt (acquirer-core-backend → `acquirer_core` DB)

| Field | Type | Notes |
|---|---|---|
| id | UUID (PK) | |
| paymentIntentId | UUID (FK) | |
| paymentMethod | VARCHAR | e.g. `card_4242` |
| status | ENUM | `PENDING`, `AUTHORIZED`, `CAPTURED`, `FAILED`, `EXPIRED` |
| createdAt / updatedAt | TIMESTAMP | |

### InternalAttempt (card-auth-service → `card_auth` DB)

| Field | Type | Notes |
|---|---|---|
| id | UUID (PK) | |
| paymentAttemptId | UUID | Links back to acquirer-core-backend |
| provider | ENUM | `MOCK_VISA` |
| type | ENUM | `AUTH`, `CAPTURE` |
| status | ENUM | `PENDING`, `SUCCESS`, `FAILURE`, `TIMEOUT`, `EXPIRED` |
| dispatched | BOOLEAN | `false` until async dispatch succeeds (outbox pattern) |
| retryCount | INT | Timeout retries |
| requestPayload / responsePayload | TEXT (JSON) | Gateway request/response snapshots |
| createdAt / updatedAt | TIMESTAMP | |

### CardData (card-vault-service → `card_vault` DB)

| Field | Type | Notes |
|---|---|---|
| id | UUID (PK) | |
| encryptedPan | BYTEA | AES-256-GCM encrypted card number |
| iv | BYTEA | Initialization vector |
| createdAt | TIMESTAMP | |

### PaymentMethod (token-service → `token_service` DB)

| Field | Type | Notes |
|---|---|---|
| id | UUID (PK) | |
| brand | ENUM | `VISA`, `MASTERCARD`, `AMEX`, `DISCOVER`, `UNKNOWN` |
| last4 | VARCHAR(4) | Last 4 digits |
| expiryMonth / expiryYear | INT | |
| status | ENUM | `ACTIVE`, `EXPIRED`, `REVOKED` |
| createdAt | TIMESTAMP | |

### Ledger (ledger-service → `ledger` DB)

- **LedgerAccount**: `id`, `name`, `type` (ASSET/LIABILITY/REVENUE/EXPENSE), `balance`
- **LedgerEntry**: `id`, `accountId`, `amount`, `direction` (DEBIT/CREDIT), `paymentIntentId`, `description`, `createdAt`
- **DeadLetterEvent**: `id`, `topic`, `partition`, `offset`, `key`, `value`, `errorMessage`, `createdAt`, `retriedAt`

---

## 5. Data Flow

### Payment Confirm Flow

```
Client                 checkout(:8080)        vault(:8086)      token(:8084)      auth(:8085)       gateway(:8081)
  │                         │                     │                 │                 │                   │
  │── POST /confirm ───────▶│                     │                 │                 │                   │
  │                         │── POST /internal/   │                 │                 │                   │
  │                         │   encrypt ─────────▶│                 │                 │                   │
  │                         │◀─── cardDataId ─────│                 │                 │                   │
  │                         │                     │                 │                 │                   │
  │                         │── POST /internal/   │                 │                 │                   │
  │                         │   payment-methods ──┼────────────────▶│                 │                   │
  │                         │◀─── paymentMethod ──┼─────────────────│                 │                   │
  │                         │                     │                 │                 │                   │
  │                         │── POST /internal/   │                 │                 │                   │
  │                         │   dispatch-auth ────┼─────────────────┼────────────────▶│                   │
  │◀─── 200 (async) ───────│                     │                 │                 │── POST /gateway ──▶│
  │                         │                     │                 │                 │                   │
  │                         │                     │                 │                 │◀── webhook ───────│
  │                         │◀── POST /webhooks ──┼─────────────────┼─────────────────│                   │
  │                         │   (status update)   │                 │                 │                   │
```

### CDC Flow (Debezium → Ledger)

```
checkout DB (acquirer_core)
  │── WAL change on payment_intents
  ▼
PostgreSQL logical replication
  │
  ▼
Debezium Connect
  │── acquirer-core.public.payment_intents topic
  ▼
Kafka
  │
  ▼
ledger-service (consumer)
  │── create double-entry journal entries
  ▼
ledger DB
```

---

## 6. State Machines

### PaymentIntent

```
REQUIRES_CONFIRMATION ─── auth success ──▶ AUTHORIZED ─── capture success ──▶ CAPTURED
        │                                       │
        ├── auth failure ──▶ FAILED              └── capture failure: stays AUTHORIZED
        │                                              (merchant can retry capture)
        └── scheduler timeout ──▶ EXPIRED
```

### InternalAttempt

```
PENDING ─── success ──▶ SUCCESS (terminal)
   │
   ├── failure ──▶ FAILURE (terminal)
   │
   ├── timeout ──▶ TIMEOUT → retry (max 2, backoff) ──▶ success/failure
   │
   └── scheduler ──▶ EXPIRED (terminal)
```

---

## 7. Key Patterns

### PCI / Non-PCI Boundary
Raw card data only enters `card-vault-service` (encrypted at rest with AES-256-GCM) and `card-auth-service` (for scheme dispatch). `acquirer-core-backend` never persists PAN — only tokenized references (paymentMethodId, brand, last4).

### Idempotency
- **Create**: Redis cache with TTL; duplicate `POST /payment_intents` returns cached response.
- **Confirm**: Redis distributed lock per PaymentIntent ID prevents double-confirm race. `Idempotency-Key` header supported — same key + same payload returns cache; different payload returns 400.

### Resilience
- **Resilience4j circuit breaker + bulkhead**: Redis lock operations wrapped in circuit breaker; DB fallback gated by bulkhead (5 concurrent max) to prevent pool exhaustion.
- **Pessimistic write locking**: `findByIdForUpdate()` locks PaymentIntent before mutation.
- **Transactional outbox**: InternalAttempts created with `dispatched=false`, committed, then dispatched async. Scheduler retries undispatched attempts.
- **Terminal state guards**: Webhooks silently ignored if InternalAttempt is already terminal.

### Security
- **HMAC-SHA256 webhook verification**: `WebhookSignatureFilter` validates signature + timestamp headers.
- **Input validation**: Jakarta Bean Validation on all request DTOs (`@field:Min`, `@field:Size`, `@Valid`).
- **Structured error responses**: `ApiError(type, code, message, param?)` via `@ControllerAdvice`.

### Schema Management
- **Flyway migrations** for all services. `ddl-auto=validate` — no auto-DDL.
- **ShedLock** for distributed scheduler coordination (single-instance execution).

---

## 8. Infrastructure

### Docker Compose Services

| Service | Image | Port | Purpose |
|---------|-------|------|---------|
| postgres | postgres:16-alpine | 5432 | 6 databases, `wal_level=logical` |
| redis | redis:7-alpine | 6379 | Distributed locking, idempotency cache |
| kafka | cp-kafka:7.6.0 (KRaft) | 9092 | CDC event streaming |
| debezium | debezium/connect:2.5 | 8083 | CDC connector |
| prometheus | prom/prometheus:v2.51.0 | 9090 | Metrics scraping (5s interval) |
| grafana | grafana/grafana:10.4.1 | 3000 | Dashboards (admin/admin) |
| cadvisor | cadvisor:v0.49.1 | 8089 | Container CPU/memory/network metrics |
| postgres-exporter | postgres-exporter:v0.15.0 | 9187 | PG connections, txn rate, locks |

### Resource Limits (Docker)

| Container | CPU | Memory | HikariCP Pool | Tomcat Threads |
|-----------|-----|--------|---------------|----------------|
| postgres | 1.0 | 2 GB | — | — |
| acquirer-core-backend | 1.0 | 1 GB | 80 (min 20) | 200 |
| external-payment-gateway | 1.0 | 1 GB | — | 400 |
| ledger-service | 1.0 | 1 GB | 10 | 200 |
| card-auth-service | 1.0 | 1 GB | 30 (min 10) | 200 |
| card-vault-service | 0.5 | 512 MB | 20 (min 5) | 200 |
| token-service | 0.5 | 512 MB | 20 (min 5) | 200 |
| merchant-service | 0.5 | 512 MB | 15 (min 5) | 200 |
| cadvisor | 0.5 | 256 MB | — | — |

### Idle Resource Usage (measured)

| Container | CPU | Memory |
|-----------|-----|--------|
| acquirer-core-backend | <1% | 553 MB / 1 GB |
| card-auth-service | <2% | 357 MB / 1 GB |
| external-payment-gateway | <5% | 236 MB / 1 GB |
| ledger-service | <1% | 343 MB / 1 GB |
| card-vault-service | <1% | 291 MB / 512 MB |
| token-service | <1% | 307 MB / 512 MB |
| merchant-service | <1% | 290 MB / 512 MB |
| postgres | <4% | 587 MB / 2 GB |
| kafka | <1% | 664 MB |
| redis | <1% | 15 MB |
| **Total (all 15 containers)** | | **~3.9 GB** |

---

## 9. Monitoring

### Prometheus Scrape Targets

All Spring Boot services expose `/actuator/prometheus` via Micrometer registry. Prometheus scrapes every 5 seconds:

- 6 Spring Boot services (JVM heap, GC, threads, HTTP latency, HikariCP pool)
- cAdvisor (container CPU, memory, network, disk I/O)
- postgres-exporter (connections, transactions, locks, cache hit ratio, replication lag)

### Grafana Dashboards (auto-provisioned)

| Dashboard | Key Panels |
|-----------|------------|
| **JVM & Spring Boot Overview** | Heap used/committed/max, GC pause rate, live threads, thread states, HTTP req rate, P99 latency, HikariCP active/pending/max, Tomcat busy threads, process CPU |
| **Docker Containers** | Container CPU %, memory usage, memory % of limit, network RX/TX rate, filesystem read/write rate |
| **PostgreSQL** | Active connections by DB, total connections, txn commit/rollback rate, rows fetched/inserted/updated/deleted, locks by mode, DB size, replication lag, cache hit ratio |

---

## 10. Mock Gateway

### Outcome Probabilities

| Type | Success | Failure | Timeout |
|---|---|---|---|
| AUTH | 70% | 20% | 10% |
| CAPTURE | 90% | 5% | 5% |

### Latency Distribution (Exponential)

- **Webhook delay**: floor 1s, cap 60s, lambda 0.0003 → median ~3.3s, P95 ~11s
- **ACK latency**: floor 10ms, cap 2s, lambda 0.005 → median ~150ms

### Special Payment Methods

| Prefix | Behaviour |
|---|---|
| `card_hang` | Gateway never fires webhook — exercises expiry scheduler |

---

## 11. Background Schedulers

### acquirer-core-backend (ShedLock protected)

| Scheduler | Interval | Action |
|-----------|----------|--------|
| `sweepExpiredAuthAttempts` | 10s | Expires `REQUIRES_CONFIRMATION` intents older than 180s; calls auth-service to expire InternalAttempts |
| `sweepUndispatchedConfirms` | 3s | Retries `dispatched=false` PaymentAttempts older than 10s (outbox retry for confirm dispatch) |

### card-auth-service

| Scheduler | Interval | Action |
|-----------|----------|--------|
| `sweepExpiredCaptureAttempts` | 10s | Expires stale capture InternalAttempts (60s timeout). Intent stays AUTHORIZED. |
| `sweepUndispatchedAttempts` | 3s | Retries `dispatched=false` InternalAttempts (outbox retry) |

---

## 12. Integration Tests

**Directory:** `api_tests/` — 27 tests across 6 modules.

### test_payments.py (7 tests)

| Test | What it verifies |
|---|---|
| `test_throughput_and_latencies` | 40 sequential creates; P50/P95/P99 latency |
| `test_input_validation` | Create DTO: amount, currency, merchantId, description, email, statementDescriptor |
| `test_confirm_input_validation` | Confirm DTO: cardNumber, expiryMonth (1-12), expiryYear, CVC length |
| `test_state_machine_guards` | Capture on unconfirmed → 409; re-confirm on failed → 409 |
| `test_capture_on_non_authorized_states` | Capture on requires_confirmation, failed, captured, expired → 409 with correct message |
| `test_merchant_validation_on_list_endpoints` | Nonexistent merchant on list/cursor endpoints → 400 |
| `test_openapi_docs_available` | OpenAPI docs at /api-docs for all external services |

### test_concurrency.py (5 tests)

| Test | What it verifies |
|---|---|
| `test_concurrent_creates` | 50 threads; no 5xx, no duplicate IDs |
| `test_concurrent_confirm_redis_lock` | 80 concurrent confirms → exactly 1 PaymentAttempt (lock enforced) |
| `test_idempotency_correctness` | Same key returns cache; different payload → 400; 10 concurrent replays consistent |
| `test_concurrent_capture` | 10 concurrent captures → exactly 1 CAPTURE InternalAttempt |
| `test_duplicate_confirm_sequential` | Second confirm on terminal intent → 409, no duplicate attempt |

### test_security.py (6 tests)

| Test | What it verifies |
|---|---|
| `test_webhook_replay` | Replay + different-status late webhook on terminal intent → status unchanged |
| `test_pci_service_isolation` | PCI services (8084, 8085, 8086) not accessible from host |
| `test_pci_tokenization_flow` | Raw PAN not in response; pm_ prefix; correct brand/last4 |
| `test_webhook_signature_verification` | Correct HMAC accepted; wrong secret → 401 |
| `test_webhook_missing_headers` | Missing signature/timestamp headers → 401 |
| `test_webhook_timestamp_tolerance` | Stale timestamp (600s) → 401; recent (10s) → accepted |

### test_load.py (4 tests)

| Test | What it verifies |
|---|---|
| `test_outbox_dispatch_flag` | 5 intents confirmed → all have InternalAttempts (outbox dispatch working) |
| `test_dispatch_retry` | 10 intents → all reach terminal via scheduler retry |
| `test_expiry_auth_hang` | Auth timeout → intent expires (reads actual config from server) |
| `test_sustained_load` | 100 TPS for 30s; consistency, ledger balance, merchant distribution |

### test_ledger.py (2 tests)

| Test | What it verifies |
|---|---|
| `test_ledger_consistency` | Global debit=credit; per-intent entry patterns; per-intent balance |
| `test_dead_letter_api` | Dead-letter events API accessible |

### test_observability.py (5 tests)

| Test | What it verifies |
|---|---|
| `test_cursor_pagination` | Cursor pagination with limit, page2, merchant-scoped, invalid cursor |
| `test_correlation_ids` | Auto-generated and echoed X-Correlation-Id |
| `test_business_metrics` | 5 custom Prometheus metrics present |
| `test_hikari_pool_metrics` | HikariCP pool max and active connections exposed |
| `test_graceful_shutdown_config` | Health endpoint returns UP |

### Measured Performance (4 CPU / 8 GB RAM, Colima on macOS)

| Metric | Value |
|--------|-------|
| **Sustained throughput** | 100 TPS (3,100 intents in 30s) |
| **Create latency** | P50 24ms, P95 159ms, P99 250ms |
| **Confirm latency** | P50 1,008ms, P95 1,459ms, P99 1,796ms |
| **Webhook drain time** | 23s for 3,100 intents to reach terminal |
| **Terminal coverage** | 100% (authorized + failed + captured + expired) |
| **Ledger consistency** | Debit = Credit invariant holds across all intents |
| **Lock contention (80 threads)** | Exactly 1 PaymentAttempt; P50 rejection 71ms |
| **Backend peak CPU (under load)** | 86% |
| **Backend peak heap (under load)** | 377 MB / 1,511 MB |
| **Gateway peak CPU (under load)** | 24% |

Confirm latency is dominated by the 6-hop RPC chain and mock gateway ACK latency (P50 ~150ms, cap 2s). Real gateway integration would have different latency characteristics.

---

## 13. How to Run

### Prerequisites

- Docker Desktop (Docker Compose v2) or Colima
- Python 3.10+ (for integration tests)

### Full Stack (Recommended)

```bash
# macOS with Colima (recommended: 4 CPU, 8 GB RAM)
colima start --cpu 4 --memory 8

# Start everything: 7 services + postgres + redis + kafka + debezium + monitoring
docker-compose up -d --build

# Wait ~30s for all services to initialize, then verify:
curl http://localhost:8080/actuator/health   # acquirer-core-backend
curl http://localhost:8081/actuator/health   # external-payment-gateway
curl http://localhost:8082/actuator/health   # ledger-service
curl http://localhost:8087/actuator/health   # merchant-service
```

### Monitoring

```bash
# Grafana — pre-configured dashboards, no setup needed
open http://localhost:3000
# Login: admin / admin

# Prometheus — raw metric queries
open http://localhost:9090

# Verify Prometheus metrics are flowing from services:
curl http://localhost:8080/actuator/prometheus | head -20
```

### Integration Tests

```bash
# One-time setup
pip install requests

# Run all 27 tests (including sustained load)
python3 -m api_tests.run_all

# Quick mode (skip sustained load)
python3 -m api_tests.run_all --quick

# Stress only (sustained load + ledger)
python3 -m api_tests.run_all --stress

# Configurable load duration and TPS
python3 -m api_tests.run_all --duration 120 --tps 200
```

Watch the Grafana dashboards at `http://localhost:3000` in real-time while the tests run.

### Individual Service (Local Dev)

```bash
# Requires PostgreSQL running (via Docker or locally)
cd acquirer-core-backend && ./gradlew bootRun    # port 8080
cd external-payment-gateway && ./gradlew bootRun # port 8081
cd ledger-service && ./gradlew bootRun           # port 8082
cd card-vault-service && ./gradlew bootRun       # port 8086
cd token-service && ./gradlew bootRun            # port 8084
cd card-auth-service && ./gradlew bootRun        # port 8085
cd merchant-service && ./gradlew bootRun         # port 8087
```

### API Documentation

Swagger UI available at `http://localhost:<port>/swagger-ui.html` for each service.

### Useful Commands

```bash
# View container resource usage
docker stats

# View logs for a specific service
docker-compose logs -f acquirer-core-backend

# Restart a single service
docker-compose restart acquirer-core-backend

# Stop everything
docker-compose down

# Stop everything and delete volumes (clean slate)
docker-compose down -v
```

---

## 14. Changes from v1

| Area | v1 | v2 |
|------|----|----|
| Services | 3 (checkout, gateway, ledger) | 7 (+card-vault, token, card-auth, merchant) |
| PCI isolation | None — PAN in checkout service | PCI zone: vault encrypts at rest, auth dispatches, no external ports |
| Card storage | In-memory vault within checkout | Dedicated card-vault-service with AES-256-GCM |
| Tokenization | None | token-service creates PaymentMethod (brand, last4, expiry) |
| Auth dispatch | Checkout calls gateway directly | card-auth-service owns InternalAttempt lifecycle |
| Schema management | Hibernate `ddl-auto=update` | Flyway migrations, `ddl-auto=validate` |
| Idempotency (create) | DB advisory lock | Redis cache with TTL |
| Idempotency (confirm) | DB-level lock | Redis distributed lock + Idempotency-Key header |
| Resilience | None | Resilience4j circuit breaker + bulkhead on Redis/DB fallback |
| Distributed scheduling | None | ShedLock for scheduler coordination |
| Input validation | Minimal | Jakarta Bean Validation on all DTOs |
| Error responses | Per-controller | `ApiError` DTO via `@ControllerAdvice` |
| Monitoring | None | Prometheus + Grafana + cAdvisor + postgres-exporter |
| Webhook security | None | HMAC-SHA256 signature verification |
| Integration tests | 10 suites | 27 tests across 6 modules (payments, concurrency, security, load, ledger, observability) |
| Dead letter handling | None | Dead letter events table with retry API |
