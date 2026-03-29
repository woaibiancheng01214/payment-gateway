# Technical Improvements

## Critical

### 1. Raw card data flows through the backend API (PCI DSS Scope)

**File:** `acquirer-core-backend/.../dto/Dtos.kt:18-24`

`ConfirmPaymentIntentRequest` accepts raw PAN, CVC, and expiry directly into the backend:

```kotlin
data class ConfirmPaymentIntentRequest(
    val cardNumber: String,      // full PAN hits your server
    val cvc: String              // CVC hits your server
)
```

Even though `TokenVaultService` tokenizes before DB persistence, the backend is in **full PCI DSS scope** because raw card data transits through the API. In production, card data should be collected via a client-side tokenization SDK (like Stripe.js or a PCI-scoped iframe) so the PAN never touches the server. This is how Stripe, Adyen, and Braintree all work — the `confirm` call receives a token/payment method ID, not raw card numbers.

**Fix:** Introduce a client-side tokenization endpoint (or third-party vault like Basis Theory / Evervault) that returns an opaque token. The `confirm` endpoint should accept `paymentMethodId: String` instead of raw card fields.

---

### 2. TokenVaultService is in-memory only

**File:** `acquirer-core-backend/.../service/TokenVaultService.kt`

The `ConcurrentHashMap` vault is:
- **Lost on restart** — all tokens become unresolvable, breaking any in-flight payments
- **Not shared across instances** — horizontal scaling breaks tokenization
- **Unbounded** — no eviction policy, so it grows forever (memory leak under load)

**Fix:** Back the vault with Redis or a dedicated external vault service. Add TTL-based eviction. Define an interface (`TokenVault`) with swappable implementations.

---

### 3. No input validation

**Files:** `PaymentIntentController.kt`, `Dtos.kt`

- `amount` accepts any `Long` — including 0 and negative values
- `currency` has no validation (no ISO 4217 check, no allowlist)
- `expiryMonth`/`expiryYear` are not validated (month 13? year 2020?)
- No `@Valid` / Bean Validation annotations anywhere
- No max-length check on `description`, `metadata`, `customerEmail`

Payment APIs must validate at the boundary. Stripe rejects amounts < 50 cents for most currencies, validates currency codes, and returns structured error objects.

**Fix:** Add Jakarta Bean Validation annotations (`@Min`, `@Pattern`, custom validators for currency ISO 4217). Add `@Valid` on controller parameters.

---

### 4. No API authentication or authorization

Any client can create, confirm, capture, and list all payment intents. Production payment APIs require:
- API key authentication (publishable key vs. secret key)
- Merchant-scoped data isolation (multi-tenancy)
- Rate limiting per API key

**Fix:** Add a `SecurityFilter` that validates API keys from the `Authorization` header. Scope all queries by merchant ID.

---

### 5. Redis lock fallback is unsafe

**File:** `acquirer-core-backend/.../service/PaymentIntentService.kt:82-84`

```kotlin
catch (e: Exception) {
    log.warn("Redis lock acquire failed..., proceeding without lock: ${e.message}")
    return lockValue  // pretends lock was acquired
}
```

If Redis is down, the code proceeds as if the lock was acquired, defeating the purpose of the lock entirely.

**Fix:** Either fail the request (return 503) or fall back explicitly to DB-level pessimistic locking only. Do not silently skip the lock.

---

### 6. Webhook handler reads InternalAttempt twice

**File:** `acquirer-core-backend/.../service/PaymentIntentService.kt:327-336`

```kotlin
val probe = internalAttemptRepository.findById(internalAttemptId)  // 1st read (no lock)
// ... navigate to intent ...
val intent = paymentIntentRepository.findByIdForUpdate(...)        // lock acquired
val internalAttempt = internalAttemptRepository.findById(...)      // 2nd read (redundant)
```

The first read is outside the lock and can see stale data. The entire read should happen after the lock is acquired.

**Fix:** Restructure to: (1) find the intent ID via a JOIN query or indexed lookup, (2) lock the intent, (3) read all related entities within the locked scope.

---

## High Priority

### 7. `ddl-auto=update` in production path

**File:** `acquirer-core-backend/src/main/resources/application.properties`

```properties
spring.jpa.hibernate.ddl-auto=update
```

Hibernate auto-DDL can drop columns, alter types unexpectedly, and doesn't support rollback. It should never be used beyond local development.

**Fix:** Add Flyway or Liquibase. Create versioned migration scripts (`V1__create_payment_intents.sql`, etc.). Set `ddl-auto=validate` so Hibernate checks schema alignment without modifying it.

---

### 8. No tests exist

Zero test files found across all three services. For a payment system, you need at minimum:
- Unit tests for state machine transitions (auth, capture, expiry)
- Integration tests for the webhook handling flow
- Concurrency tests for idempotency and locking
- Contract tests between services
- Edge case tests (double confirm, double capture, webhook after expiry)

**Fix:** Start with integration tests for `PaymentIntentService` using `@SpringBootTest` + Testcontainers (PostgreSQL, Redis). Test the critical paths: create → confirm → webhook(success) → capture → webhook(success).

---

### 9. Ledger consumer silently drops failed events

**File:** `ledger-service/.../service/PaymentEventConsumer.kt:57-58`

```kotlin
catch (e: Exception) {
    log.error("Failed to process CDC event: ${e.message}", e)
}
```

If a CDC event fails processing, it's logged and lost. The Kafka offset still advances, meaning ledger entries can silently go missing.

**Fix:** Either:
- Remove the try/catch and let the exception propagate so Kafka retries delivery
- Publish failed events to a dead-letter topic for manual review
- Use Spring Kafka's `ErrorHandler` with retry + DLT support

---

### 10. Scheduler runs on all instances

**File:** `acquirer-core-backend/.../service/PaymentCleanupScheduler.kt`

With `@Scheduled`, every application instance runs the cleanup/expiry sweeps concurrently. This causes:
- Redundant work and wasted DB connections
- Race conditions between instances expiring the same intent (mitigated by `findByIdForUpdate` but still wasteful)

**Fix:** Use [ShedLock](https://github.com/lukas-krecan/ShedLock) to ensure only one instance runs each scheduled task. Alternatively, use a leader election mechanism.

---

### 11. No retry or circuit breaker on GatewayClient

**File:** `acquirer-core-backend/.../service/GatewayClient.kt`

The `RestTemplate` calls to the external gateway have no retry logic, no circuit breaker, and no bulkhead. If the external gateway is slow or down, threads can be exhausted.

**Fix:** Add Resilience4j with:
- Circuit breaker (fail fast when gateway is down)
- Retry with exponential backoff
- Bulkhead to limit concurrent outbound calls

---

### 12. `data class` entities with JPA

**Files:** All entity files (`PaymentIntent.kt`, `PaymentAttempt.kt`, `InternalAttempt.kt`, etc.)

Using Kotlin `data class` with JPA is problematic:
- `equals()`/`hashCode()` includes all fields including mutable ones (`status`, `updatedAt`), which breaks when entities are in Sets or Maps
- `toString()` can trigger lazy-loading issues with relationships
- Copy semantics can cause detached entity confusion

**Fix:** Use regular `class` with explicit `equals`/`hashCode` based only on the `id` field. Or use `@NaturalId` and override accordingly.

---

## Medium Priority

### 13. No structured error responses

Errors are returned as ad-hoc `Map<String, String>`:

```kotlin
ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "Bad request")))
```

**Fix:** Create a structured error response class:
```kotlin
data class ApiError(
    val type: String,       // "invalid_request_error", "api_error", etc.
    val code: String,       // "amount_too_small", "currency_invalid", etc.
    val message: String,
    val param: String? = null
)
```
Use a `@ControllerAdvice` global exception handler.

---

### 14. Hardcoded secrets in configuration

**Files:** `application.properties`, `docker-compose.yml`

```properties
gateway.webhook.secret=payment-gateway-webhook-secret-2026
spring.datasource.password=postgres
```

**Fix:** Use environment variables with `${ENV_VAR:default}` syntax in properties. Use Docker secrets or a vault (HashiCorp Vault) for production.

---

### 15. No API versioning

Endpoints are at `/payment_intents` with no version prefix. Breaking changes will affect all clients simultaneously.

**Fix:** Add URL prefix versioning: `/v1/payment_intents`. This allows introducing `/v2/` for breaking changes while maintaining backward compatibility.

---

### 16. No observability beyond basic logging

- No distributed tracing (cannot follow a request across services)
- No structured logging (plain text, not JSON)
- No custom metrics (payment success rates, latency percentiles, etc.)
- No alerting integration

**Fix:** Add OpenTelemetry for distributed tracing, switch to structured JSON logging (Logback + logstash-encoder), export Micrometer metrics to Prometheus/Grafana.

---

### 17. No OpenAPI / Swagger documentation

No API documentation exists. Consumers have to read source code to understand the API.

**Fix:** Add `springdoc-openapi` dependency. Annotate DTOs and controllers with OpenAPI annotations. Serve Swagger UI at `/swagger-ui.html`.

---

### 18. No CI/CD pipeline

No GitHub Actions, Jenkinsfile, or any CI/CD configuration found.

**Fix:** Add GitHub Actions workflows for:
- Build and test on PR
- Docker image build and push
- Deployment to staging/production

---

## Architecture Improvements Summary

| Area | Current | Recommended |
|------|---------|-------------|
| Schema management | `ddl-auto=update` | Flyway/Liquibase migrations |
| API versioning | None | URL prefix (`/v1/payment_intents`) |
| Observability | Basic logging | Structured logging + distributed tracing (OpenTelemetry) + metrics |
| Health checks | Spring Actuator basic | Add readiness probes (DB, Redis, Kafka connectivity) |
| Error handling | Ad-hoc exception handlers | Global `@ControllerAdvice` with structured error codes |
| API docs | None | OpenAPI/Swagger spec |
| Rate limiting | None | Per-API-key rate limiting |
| Deployment | docker-compose only | Kubernetes manifests or Helm charts for production |
| CI/CD | None | GitHub Actions for build/test/deploy |
| Card data | Server-side PAN handling | Client-side tokenization SDK |

---

## References

- [PCI DSS 4.0 Standards](https://www.pcisecuritystandards.org/standards/)
- [Stripe: Designing Robust APIs with Idempotency](https://stripe.com/blog/idempotency)
- [Payment Gateway Architecture Best Practices](https://www.unipaas.com/blog/payment-gateway-architecture)
- [Design a Payment System Guide](https://www.systemdesignhandbook.com/guides/design-a-payment-system/)
- [Building PCI DSS Compliant Infrastructure](https://openmetal.io/resources/blog/building-pci-dss-compliant-infrastructure-for-payment-processors/)
