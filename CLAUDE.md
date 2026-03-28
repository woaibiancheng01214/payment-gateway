# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A payment gateway system with three Kotlin/Spring Boot 3.2 microservices, PostgreSQL, Kafka, and Debezium CDC. All services use JDK 17 and Gradle (Kotlin DSL).

## Build & Run Commands

Each service has its own Gradle wrapper. Run from the service directory:

```bash
# Build a single service
cd acquirer-core-backend && ./gradlew build

# Run a single service locally (needs PostgreSQL running)
cd acquirer-core-backend && ./gradlew bootRun

# Run tests for a single service
cd acquirer-core-backend && ./gradlew test

# Run a specific test class
cd acquirer-core-backend && ./gradlew test --tests "com.payment.gateway.SomeTest"

# Full stack via Docker Compose (from repo root)
docker-compose up -d

# Run the stress/integration test (requires full stack running)
.venv/bin/python3 stress_test.py
```

Replace `acquirer-core-backend` with `external-payment-gateway` or `ledger-service` as needed.

## Architecture

### Three Services

| Service | Port | Database | Purpose |
|---------|------|----------|---------|
| **acquirer-core-backend** | 8080 | `acquirer_core` (PostgreSQL) | Core payment processing: create/confirm/capture payment intents, webhook handling, scheduling |
| **external-payment-gateway** | 8081 | — | Mock payment gateway simulator with probabilistic outcomes and latency |
| **ledger-service** | 8082 | `ledger` (PostgreSQL) | Double-entry ledger driven by Debezium CDC events from Kafka |

### Data Flow

1. Client creates/confirms/captures payment intents via `acquirer-core-backend` REST API
2. Core service dispatches auth/capture requests to `external-payment-gateway`
3. Gateway processes asynchronously, sends results back via signed webhooks (`POST /webhooks/gateway`)
4. Debezium captures `payment_intents` table changes via PostgreSQL WAL → publishes to Kafka topic `acquirer-core.public.payment_intents`
5. `ledger-service` consumes CDC events and posts balanced debit/credit entries

### Key Patterns

- **Pessimistic write locking**: `findByIdForUpdate()` locks the PaymentIntent aggregate root before any mutation (confirm, capture, webhook, expiry)
- **PostgreSQL advisory locks**: `pg_advisory_xact_lock` serializes idempotency key checks across instances
- **Transactional outbox**: InternalAttempts are created with `dispatched=false`, committed, then dispatched async. `PaymentCleanupScheduler` retries undispatched attempts
- **Terminal state guards**: Webhooks are silently ignored if the InternalAttempt is already in a terminal state (SUCCESS, FAILURE, EXPIRED)
- **Separate @Service for REQUIRES_NEW**: `PaymentExpiryService` exists as a separate bean so Spring's AOP proxy intercepts the `REQUIRES_NEW` transaction (self-invocation bypasses proxies)
- **HMAC-SHA256 webhook verification**: `WebhookSignatureFilter` validates `X-Gateway-Signature` and `X-Gateway-Timestamp` headers with constant-time comparison

### Entity Hierarchy

`PaymentIntent` → has many `PaymentAttempt` → has many `InternalAttempt` (AUTH or CAPTURE type)

### Payment Intent State Machine

`REQUIRES_CONFIRMATION` → `AUTHORIZED` (auth success) → `CAPTURED` (capture success)
`REQUIRES_CONFIRMATION` → `FAILED` (auth failure) or `EXPIRED` (timeout)

### Background Schedulers (PaymentCleanupScheduler)

- **sweepExpiredAuthAttempts**: expires stale `REQUIRES_CONFIRMATION` intents (180s timeout)
- **sweepExpiredCaptureAttempts**: expires stale capture InternalAttempts (60s timeout)
- **sweepUndispatchedAttempts**: retries `dispatched=false` InternalAttempts (outbox retry, 10s threshold)

## Infrastructure

- PostgreSQL 16 with `wal_level=logical` for Debezium CDC
- Kafka (KRaft mode, single broker) for CDC event streaming
- Debezium Connect 2.5 with PostgreSQL connector (registered via `infra/debezium-connector.json`)
- Database initialization: `infra/init-db.sql` creates `acquirer_core` and `ledger` databases

## Service Health Checks

```bash
curl http://localhost:8080/actuator/health  # core
curl http://localhost:8081/actuator/health  # gateway
curl http://localhost:8082/actuator/health  # ledger
```
