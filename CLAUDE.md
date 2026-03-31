# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A payment gateway system with seven Kotlin/Spring Boot 3.2 microservices, PostgreSQL, Redis, Kafka, and Debezium CDC. Services are split into PCI and non-PCI zones. All services use JDK 17 and Gradle (Kotlin DSL).

## Build & Run Commands

Each service has its own Gradle wrapper. Run from the service directory:

```bash
# Build a single service
cd acquirer-core-backend && ./gradlew build

# Run a single service locally (needs PostgreSQL running)
cd acquirer-core-backend && ./gradlew bootRun

# Run tests for a single service
cd acquirer-core-backend && ./gradlew test

# Full stack via Docker Compose (from repo root)
docker-compose up -d

# Run the integration test suite (requires full stack running)
python3 -m api_tests.run_all
```

Replace `acquirer-core-backend` with any service directory name as needed.

## Architecture

### Services

| Service | Zone | Port | Database | Purpose |
|---------|------|------|----------|---------|
| **acquirer-core-backend** (checkout) | Non-PCI | 8080 (external) | `acquirer_core` | Payment orchestration: create/confirm/capture intents, webhook routing, scheduling |
| **external-payment-gateway** | Non-PCI | 8081 (external) | — | Mock gateway simulator with probabilistic outcomes and latency |
| **ledger-service** | Non-PCI | 8082 (external) | `ledger` | Double-entry ledger driven by Debezium CDC events from Kafka |
| **card-vault-service** | PCI | 8083 (internal) | `card_vault` | Encrypted card data storage (AES-256-GCM) |
| **token-service** | PCI | 8084 (internal) | `token_service` | Payment method references (brand, last4, expiry — no PAN) |
| **card-auth-service** | PCI | 8085 (internal) | `card_auth` | Authorization/capture orchestration, gateway dispatch, InternalAttempt lifecycle |
| **merchant-service** | Non-PCI | 8087 (external) | `merchant_service` | Merchant CRUD — all PaymentIntents scoped to a merchant |

PCI zone services have **no external port mappings** — accessible only within the Docker network.

### Data Flow

1. Merchant created via `merchant-service` REST API (`/v1/merchants`)
2. Client creates/confirms/captures payment intents via `acquirer-core-backend` REST API (`/v1/payment_intents`) — each intent scoped to a merchant (validated via `merchant-service`)
3. On confirm: checkout calls `card-vault-service` (encrypt PAN) → `token-service` (create payment method) → `card-auth-service` (dispatch auth)
4. `card-auth-service` dispatches to `external-payment-gateway`
5. Gateway processes asynchronously, sends webhook to checkout → forwarded to `card-auth-service`
6. Debezium captures `payment_intents` table changes via PostgreSQL WAL → Kafka → `ledger-service`

### Key Patterns

- **PCI/non-PCI boundary**: Raw card data only enters `card-vault-service` (encrypted at rest) and `card-auth-service` (for scheme dispatch). Checkout-service never persists PAN.
- **Resilience4j circuit breaker + bulkhead**: Redis lock operations wrapped in circuit breaker; DB fallback gated by bulkhead (5 concurrent max) to prevent pool exhaustion.
- **ShedLock**: Ensures only one instance runs scheduled cleanup tasks.
- **Pessimistic write locking**: `findByIdForUpdate()` locks the PaymentIntent aggregate root before any mutation.
- **Flyway migrations**: Schema changes managed via versioned SQL files (`src/main/resources/db/migration/`). `ddl-auto=validate`.
- **Transactional outbox**: InternalAttempts created with `dispatched=false`, committed, then dispatched async. Scheduler retries undispatched attempts.
- **Terminal state guards**: Webhooks silently ignored if InternalAttempt is already terminal.
- **HMAC-SHA256 webhook verification**: `WebhookSignatureFilter` validates signature + timestamp headers.
- **Dead letter events**: Failed CDC events persisted to `dead_letter_events` table with retry API.

### Entity Hierarchy

- `merchant-service`: `Merchant` (id, name, status)
- `acquirer-core-backend`: `PaymentIntent` (scoped to merchant) → has many `PaymentAttempt`
- `card-auth-service`: `InternalAttempt` (AUTH or CAPTURE type, linked by `paymentAttemptId`)
- `card-vault-service`: `CardData` (encrypted PAN)
- `token-service`: `PaymentMethod` (brand, last4, expiry, status)

### Payment Intent State Machine

`REQUIRES_CONFIRMATION` → `AUTHORIZED` (auth success) → `CAPTURED` (capture success)
`REQUIRES_CONFIRMATION` → `FAILED` (auth failure) or `EXPIRED` (timeout)

### Background Schedulers

**acquirer-core-backend (ShedLock protected):**
- `sweepExpiredAuthAttempts`: expires stale `REQUIRES_CONFIRMATION` intents, calls auth-service to expire InternalAttempts

**card-auth-service:**
- `sweepExpiredCaptureAttempts`: expires stale capture InternalAttempts (60s timeout)
- `sweepUndispatchedAttempts`: retries `dispatched=false` InternalAttempts (outbox retry)

## Infrastructure

- PostgreSQL 16 with `wal_level=logical` for Debezium CDC
- Redis 7 for distributed locking and idempotency cache
- Kafka (KRaft mode, single broker) for CDC event streaming
- Debezium Connect 2.5 with PostgreSQL connector
- Database initialization: `infra/init-db.sql` creates all 6 databases
- Docker secrets in `infra/secrets/` for passwords, webhook secret, vault encryption key

## Coding Rules

- **Enums for bounded-value fields**: Use enums for all bounded-value fields (currency, card brand, status, provider, etc.) when creating or refactoring code. Prefer Kotlin enum classes over raw strings for any field with a finite set of valid values.
- **JPA entity classes**: Use regular `class` (not `data class`) for JPA entities. Override `equals`/`hashCode` based on `id` only. This avoids issues with mutable fields in `data class` equality, lazy-loading in `toString`, and detached entity confusion.
- **Input validation at boundaries**: Use Jakarta Bean Validation annotations (`@field:Min`, `@field:Size`, `@Valid`, etc.) on all request DTOs. Validate currency against ISO 4217 enum, amounts > 0, etc.
- **Structured error responses**: Use `ApiError(type, code, message, param?)` for all error responses. Handle via `@ControllerAdvice` global exception handler, not per-controller `@ExceptionHandler`.
- **API versioning**: All REST endpoints must be under `/v1/` prefix. Internal RPC endpoints use `/internal/` prefix.
- **Schema migrations**: Use Flyway for all schema changes. Never use `ddl-auto=update`.
- **Structured logging**: JSON logging via logstash-logback-encoder in Docker/prod profile. Plain text for local dev.

## Monitoring

Prometheus + Grafana stack runs alongside the services in Docker Compose:

- **Grafana**: `http://localhost:3000` (admin/admin) — 3 pre-built dashboards (JVM, Docker containers, PostgreSQL)
- **Prometheus**: `http://localhost:9090` — scrapes all services every 5s
- **cAdvisor**: container CPU/memory/network metrics
- **postgres-exporter**: PG connections, transactions, locks, cache hit ratio

All Spring Boot services expose `/actuator/prometheus` via Micrometer.

## Workflow Rules

- **Update learnings before pushing**: When making significant changes (new features, bug fixes, performance tuning, architectural decisions), update `docs/learnings.md` with what was learned before committing and pushing. Each learning should explain the problem, the fix, and the takeaway.

## Service Health Checks

```bash
curl http://localhost:8080/actuator/health  # checkout (acquirer-core-backend)
curl http://localhost:8081/actuator/health  # external gateway
curl http://localhost:8082/actuator/health  # ledger
curl http://localhost:8087/actuator/health  # merchant
# PCI services: accessible only from within Docker network
```

## API Documentation

Swagger UI available at `http://localhost:<port>/swagger-ui.html` for each service.
