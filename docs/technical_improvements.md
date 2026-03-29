# Technical Improvements

## Status Legend
- [x] Completed
- [ ] Pending
- [~] Scoped out / deferred

---

## Critical

### 1. [x] Raw card data flows through the backend API (PCI DSS Scope)

**Resolution:** Split into PCI-scoped services. `card-vault-service` handles encrypted PAN storage (AES-256-GCM). `token-service` manages payment method references (brand, last4). `card-auth-service` orchestrates authorizations. Raw PAN still transits through `acquirer-core-backend` briefly (accepted from browser over HTTPS) but is immediately forwarded to vault-service and never persisted in the checkout DB. Full client-side tokenization (Stripe.js-style) is a future improvement.

---

### 2. [x] TokenVaultService is in-memory only

**Resolution:** Replaced with `card-vault-service` backed by PostgreSQL with AES-256-GCM encryption. Card data survives restarts and is shared across instances. `token-service` provides the payment method abstraction layer.

---

### 3. [x] No input validation

**Resolution:** Added Jakarta Bean Validation annotations to all request DTOs (`@field:Min`, `@field:NotBlank`, `@field:Size`, `@field:Email`). Created `Currency` enum (ISO 4217), `CardBrand` enum, `Provider` enum. Added `@Valid` on all controller `@RequestBody` parameters.

---

### 4. [~] No API authentication or authorization

**Status:** Scoped out. Will be addressed when adding a unified API gateway layer with API key management and merchant model.

---

### 5. [x] Redis lock fallback is unsafe

**Resolution:** Implemented `DistributedLockManager` with Resilience4j circuit breaker (`redisLockCircuitBreaker`) for Redis operations and bulkhead (`dbLockFallbackBulkhead`, max 5 concurrent) for DB fallback. When Redis is down and circuit breaker is OPEN, requests fall back to DB-only locking gated by bulkhead. If bulkhead is full, returns 503 instead of silently proceeding without a lock.

---

### 6. [x] Webhook handler reads InternalAttempt twice

**Resolution:** Refactored as part of service split. Webhook handling is now split: `card-auth-service` processes InternalAttempt updates, returns result to `acquirer-core-backend` which handles PaymentIntent/PaymentAttempt updates under lock.

---

## High Priority

### 7. [x] `ddl-auto=update` in production path

**Resolution:** Added Flyway to all services. Changed `ddl-auto=validate`. Created versioned migration scripts (`V1__initial_schema.sql`, etc.) for each service's database.

---

### 8. [~] No tests exist

**Status:** Deferred — stress_test.py provides comprehensive integration/load testing. Unit tests to be added later.

---

### 9. [x] Ledger consumer silently drops failed events

**Resolution:** Failed CDC events are now persisted to `dead_letter_events` table. Added `GET /v1/ledger/dead-letter-events` API to list failed events (with pagination and unresolved-only filter) and `POST /v1/ledger/dead-letter-events/{id}/retry` to manually retry. Also updated `PaymentIntentSnapshot` to include `createdAt`/`updatedAt` from the source PaymentIntent for correct event ordering.

---

### 10. [x] Scheduler runs on all instances

**Resolution:** Added ShedLock to `acquirer-core-backend` with JDBC provider. Schedulers annotated with `@SchedulerLock` to ensure single-instance execution. Migration `V2__shedlock_table.sql` creates the lock table.

---

### 11. [x] No retry or circuit breaker on GatewayClient

**Resolution:** `GatewayClient` moved to `card-auth-service`. Resilience patterns implemented via the service's architecture (retry via `sweepUndispatchedAttempts` scheduler, circuit breaker via Resilience4j).

---

### 12. [x] `data class` entities with JPA

**Resolution:** All JPA entities across all services converted from `data class` to regular `class` with explicit `equals`/`hashCode` on `id` only. Added as a coding rule in CLAUDE.md.

---

## Medium Priority

### 13. [x] No structured error responses

**Resolution:** Created `ApiError` data class with `type`, `code`, `message`, `param` fields. Added `@ControllerAdvice` `GlobalExceptionHandler` in all services. Removed per-controller `@ExceptionHandler` methods.

---

### 14. [x] Hardcoded secrets in configuration

**Resolution:** Added Docker secrets infrastructure (`infra/secrets/`). Secret files: `db_password.txt`, `webhook_secret.txt`, `vault_encryption_key.txt`. Docker compose `secrets:` section defined. Services can reference secrets via `/run/secrets/` mount.

---

### 15. [x] No API versioning

**Resolution:** All endpoints prefixed with `/v1/`. Internal RPC endpoints use `/internal/` prefix. `stress_test.py` updated to use `/v1/` paths.

---

### 16. [x] No observability beyond basic logging

**Resolution:** Added `logback-spring.xml` to all services with profile-based output: plain text for local dev, JSON (logstash-logback-encoder) for Docker/prod. API metrics via ELK stack noted as **unfinished/future** — will use a separate stack (ELK/Prometheus/Grafana) for log aggregation, API metrics, and alerting.

---

### 17. [x] No OpenAPI / Swagger documentation

**Resolution:** Added `springdoc-openapi-starter-webmvc-ui` to all services. Swagger UI available at `/swagger-ui.html`, API docs at `/api-docs`.

---

### 18. [~] No CI/CD pipeline

**Status:** Not needed — running locally. Will add GitHub Actions when deploying to cloud.

---

## Architecture Improvements Summary

| Area | Before | After |
|------|--------|-------|
| PCI scope | Full backend handles PAN | Split into vault/token/auth PCI services |
| Schema management | `ddl-auto=update` | Flyway versioned migrations |
| API versioning | None | `/v1/` prefix on all endpoints |
| Error handling | Ad-hoc per-controller | `@ControllerAdvice` + `ApiError` |
| Redis reliability | Fail-open (unsafe) | Circuit breaker + bulkhead fallback |
| Scheduler concurrency | All instances run | ShedLock single-instance |
| Logging | Plain text only | Structured JSON (logstash-encoder) |
| API docs | None | OpenAPI/Swagger |
| Input validation | None | Jakarta Bean Validation |
| Entity design | `data class` | Regular `class` + id-based equality |
| Secrets | Hardcoded | Docker secrets |
| CDC failure handling | Swallowed exceptions | Dead letter table + retry API |
