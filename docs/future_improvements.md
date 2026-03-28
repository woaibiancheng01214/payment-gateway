# Future Improvements

Research sources: Stripe documentation, Adyen engineering blog, PCI DSS 4.0 spec,
Modern Treasury, and architecture guides from craftingsoftware.com, slickerhq.com,
and medium.com (Jan–Mar 2026).

---

## 1. Transactional Outbox Pattern — IMPLEMENTED

**Status:** Implemented via commit-first + dispatch-retry scheduler.

The `InternalAttempt` entity now has a `dispatched: Boolean` column. Gateway dispatch
happens in a `TransactionSynchronization.afterCommit()` callback — the DB row is committed
first, then the HTTP call fires. If the call fails (network timeout, JVM crash), the
`sweepUndispatchedAttempts` scheduler (every 3s) picks up any `InternalAttempt` where
`dispatched = false AND status = PENDING AND createdAt < now - 10s` and re-dispatches it.

The gateway-server deduplicates using a `ConcurrentHashMap<String>` keyed on
`internalAttemptId`, so duplicate dispatches from the retry scheduler are harmless.

**Update:** Debezium CDC is now deployed in the Docker Compose stack, capturing
`payment_intents` table changes and publishing them to Kafka. The ledger-service (§7)
consumes these events. The dispatch-retry scheduler remains in place for gateway dispatch
(orthogonal concern), but the CDC pipeline is available for future event-driven consumers.

---

## 2. Webhook Signature Verification (HMAC) — IMPLEMENTED

**Status:** Implemented via HMAC-SHA256 signing + verification filter.

The external-payment-gateway signs every webhook with HMAC-SHA256 using a shared secret
(`gateway.webhook.secret`). Two headers are added to every webhook POST:

- `X-Gateway-Signature` — hex-encoded HMAC of `"$timestamp.$body"`
- `X-Gateway-Timestamp` — Unix epoch seconds when the webhook was created

The acquirer-core-backend verifies webhooks via `WebhookSignatureFilter`, a Spring
`OncePerRequestFilter` scoped to `/webhooks/**`. The filter:

1. Reads the raw request body (using a `CachedBodyRequest` wrapper so the downstream
   `@RequestBody` controller can still read it)
2. Recomputes the HMAC and compares using `MessageDigest.isEqual()` (constant-time)
3. Rejects requests where `|now - timestamp| > 300 seconds` (replay protection)
4. Returns HTTP 401 on any verification failure

The shared secret is configured via `gateway.webhook.secret` in both services'
`application.properties` (overridable via environment variables in Docker Compose).

**Reference:** [Stripe Webhooks Done Right: Production Architecture](https://iurii.rogulia.fi/blog/stripe-webhooks-production)

---

## 3. Multi-Gateway Routing & Smart Retry

**Current gap:** Every payment attempt goes to `mock-visa`. If that gateway is down or
returns a decline, the intent fails permanently (or waits for the expiry scheduler).

**Industry standard:** Production orchestrators maintain a priority-ordered list of gateway
connectors and apply routing rules based on:

- **Cost** — interchange rates differ per acquirer/BIN range
- **Performance** — real-time auth rate per gateway per card scheme, updated with a
  sliding window
- **Availability** — circuit breaker that pauses a gateway after N consecutive failures
- **Regulatory** — some card schemes mandate domestic routing (e.g., India's RuPay)

On a soft decline (e.g., `do_not_honor`, `insufficient_funds`), retry on the next gateway
in the list. On a hard decline (e.g., `stolen_card`, failed 3DS), do not retry.

**Measured impact:** Multi-gateway smart retry improves authorization rates by 7–13
percentage points and recovers ~8% of failed transactions automatically.

**Reference:** [Slicker — Multi-gateway smart retries setup guide 2025](https://www.slickerhq.com/resources/blog/multi-gateway-smart-payment-retries-setup-guide-2025),
[Stripe — Intelligent Payment Routing](https://stripe.com/us/resources/more/intelligent-payment-routing)

---

## 4. Queue-Based Async Processing (Replace In-Process Scheduler)

**Current status:** Partially addressed. The external-payment-gateway uses a
`ScheduledThreadPoolExecutor` with 64 threads. The acquirer-core-backend now uses a
commit-first pattern with a DB-backed dispatch-retry scheduler (§1), so gateway tasks
survive JVM restarts. The remaining gap is the gateway itself — its in-memory retry state
is still lost on restart.

**Update:** Kafka is now deployed in the Docker Compose stack (used by Debezium CDC for
the ledger service). The infrastructure is in place to migrate gateway dispatch to Kafka
topics, replacing the in-process scheduler with durable consumers.

**When to introduce a queue:** When the orchestrator needs to handle >500 TPS or
operate across multiple data centres, a durable queue gives:
- Horizontal scaling (add consumers, not threads)
- Built-in backpressure and dead-letter queues
- Decoupled deployment of gateway workers

**Lower priority now** because the commit-first + scheduler approach gives crash safety
for the orchestrator side. The gateway is a simulator and would be replaced by real
acquirer APIs in production.

**Reference:** [Stripe Webhooks Production Architecture](https://iurii.rogulia.fi/blog/stripe-webhooks-production)

---

## 5. 3D Secure (3DS2) Authentication

**Current gap:** The PRD explicitly marks 3DS as out of scope. In production this is not
optional — PSD2 Strong Customer Authentication (SCA) mandates it for EU card payments,
and Visa/Mastercard liability shift rules make unauthenticated chargebacks the merchant's
problem.

**How it fits into the state machine:** Stripe models this as a `requires_action` state
between `requires_confirmation` and `processing`. The front-end must handle the
`next_action.redirect_to_url` or `next_action.use_stripe_sdk` step before the payment
can continue.

**Suggested state additions:**

```
requires_confirmation
    │
    ▼
requires_action  ◄── 3DS challenge issued by issuing bank
    │
    ├── 3DS success ──► (existing auth flow)
    └── 3DS failure ──► failed (no retry allowed — hard decline)
```

**Reference:** [Stripe — How PaymentIntents work](https://docs.stripe.com/payments/paymentintents/lifecycle)

---

## 6. Card Tokenisation & PCI DSS Scope Reduction

**Current gap:** `paymentMethod` is stored as a plain string (`card_4242`). In a real
system this would be a full PAN (16-digit card number), making every service that touches
this field in-scope for PCI DSS Level 1 audits.

**Industry standard (2025):** Never store PANs. Use one of:

| Method | Description | Best for |
|---|---|---|
| **Network tokens** | Visa/Mastercard issue a token that auto-updates on card renewal | Subscriptions, recurring billing |
| **Vault-based tokenisation** | PAN encrypted in a secure vault; token is a random UUID | Refunds, partial captures needing original card |
| **Vaultless tokenisation** | Cryptographic token, no vault required | Stateless one-time charges |

PCI DSS 4.0 (fully enforced from March 2025) tightens continuous monitoring requirements
and reduces the compliance scope of any system that does not store PANs.

**Immediate change:** Replace `paymentMethod VARCHAR` with `paymentToken VARCHAR` and
integrate a token vault service (e.g., Basis Theory, Evervault, or Stripe's built-in
tokenisation). The orchestrator then passes the token to gateways — never the raw PAN.

**Reference:** [Evervault — Card Tokenization in 2025](https://evervault.com/blog/how-to-implement-card-tokenization-2025),
[PCI DSS 4.0 — payrails.com](https://www.payrails.com/blog/pci-dss-4-0-compliance-whats-new)

---

## 7. Double-Entry Ledger & Reconciliation — IMPLEMENTED

**Status:** Implemented as a separate microservice (`ledger-service`) consuming Debezium
CDC events via Kafka.

### Architecture

The acquirer-core-backend writes payment state changes to PostgreSQL as before (no code
changes). Debezium captures row-level changes from PostgreSQL WAL (logical replication)
and publishes them to a Kafka topic (`payment-gateway.public.payment_intents`). The
ledger-service consumes these events and posts balanced double-entry ledger records.

### Data model (separate `ledger` database)

- **LedgerAccount** — `merchant_receivables` (ASSET), `gateway_payable` (LIABILITY),
  `merchant_revenue` (REVENUE). Seeded on startup.
- **LedgerEntry** — immutable, append-only. Each entry records `paymentIntentId`,
  `entryType` (DEBIT/CREDIT), `amount`, `currency`, `eventType`, `eventTimestamp`
  (bi-temporal), and `createdAt`.

### Posting rules

| Payment Event | Debit | Credit | Amount |
|---|---|---|---|
| AUTHORIZED | merchant_receivables | gateway_payable | intent.amount |
| CAPTURED | gateway_payable | merchant_revenue | intent.amount |
| FAILED (after auth) | gateway_payable | merchant_receivables | reversal |
| EXPIRED (after auth) | gateway_payable | merchant_receivables | reversal |

Deduplication is enforced via a unique constraint on
`(paymentIntentId, eventType, entryType, ledgerAccountId)`.

### API

- `GET /ledger/entries?paymentIntentId=X` — all ledger entries for a payment
- `GET /ledger/balances` — aggregate balances per account

### Remaining opportunity

A nightly reconciliation job comparing `SUM(ledger)` against gateway settlement files
is the natural next step. The ledger data model supports it; only the batch comparison
logic and settlement file ingestion are missing.

**Reference:** [Modern Treasury Ledgers](https://www.moderntreasury.com/solutions/reconciliation),
[NestJS Ledger — double-entry library](https://github.com/remade/nestjs-ledger)

---

## 8. Idempotency Layer Performance (Redis Pre-Check)

**Current status:** Idempotency for `create` uses PostgreSQL advisory locks
(`pg_advisory_xact_lock`) which serialise concurrent requests for the same key at the
DB level. This is correct and works across multiple application instances.

**Remaining gap:** Every request still hits PostgreSQL for the advisory lock + lookup.
Under sustained high concurrency (>1,000 rps of retries), a Redis pre-check would
short-circuit most duplicates before touching the database:

```
incoming request with Idempotency-Key
        │
        ▼
Redis GET idempotency:<key>
        ├── HIT  → return cached response (no DB, no lock)
        └── MISS → PostgreSQL advisory lock + lookup (existing flow)
                       └── on completion: SET Redis idempotency:<key> EX 86400
```

**Known gap — connection pool exhaustion under lock contention:** `pg_advisory_xact_lock`
is a blocking call — the calling thread and its Hikari connection are both held for the
entire duration of the lock wait. Under high concurrency on a hot idempotency key,
waiting threads exhaust the connection pool (`maximum-pool-size=50`) before the Redis
pre-check is ever needed. The short-term mitigation (without Redis) is to switch to
`pg_try_advisory_xact_lock` (non-blocking — returns `false` immediately if the lock is
taken) and respond with HTTP 409 so the caller can retry, rather than blocking a
connection indefinitely.

**Note — load test blind spot:** The sustained load test in `stress_test.py` does not
exercise this path because `worker()` never passes an idempotency key to `confirm_intent`.
To expose the bottleneck, the test would need to send concurrent confirms for the same
intent using the same idempotency key, reduce `maximum-pool-size` below the thread count,
and ramp beyond 500 TPS.

**Lower priority now** because advisory locks handle correctness. Redis is a performance
optimization for when DB connection pool saturation becomes a bottleneck.

**Reference:** [Stripe Webhooks Production Architecture — dual-layer idempotency](https://iurii.rogulia.fi/blog/stripe-webhooks-production)

---

## 9. Authentication & Authorization

**Current gap:** All endpoints are unauthenticated. Any caller can create, confirm, or
capture any intent.

**Standard approach for a payment orchestrator:**

| Endpoint group | Recommended auth |
|---|---|
| `POST /payment_intents`, `GET /payment_intents` | API key (server-to-server), scoped to merchant |
| `POST /{id}/confirm` | Short-lived client secret (publishable key pattern — Stripe's model) |
| `POST /{id}/capture` | API key, requires `payments:capture` scope |
| `POST /webhooks/gateway` | HMAC signature verification (see §2), no user auth |

**Spring Security implementation:** Add a `OncePerRequestFilter` that reads
`Authorization: Bearer <api-key>`, validates against a hashed key store, and sets the
merchant context in `SecurityContextHolder` for downstream use.

---

## 10. Observability & Alerting

**Current gap:** Logging only. No metrics, no distributed tracing, no alerting.

**Recommended additions:**

| Concern | Tool | Key signals |
|---|---|---|
| Metrics | Micrometer + Prometheus | Auth rate, capture rate, webhook latency P99, expiry count/min |
| Tracing | OpenTelemetry + Jaeger | Trace `confirm → gateway → webhook` end-to-end across threads |
| Alerting | Grafana / PagerDuty | Auth rate drops below 65%, webhook failure rate > 5%, expiry spike |
| Structured logs | Logstash JSON encoder | Add `intentId`, `attemptId`, `provider` to every log line for correlation |

**Key business metrics to track:**

- **Authorization rate** — `authorized / (authorized + failed)` per gateway per card scheme
- **Capture rate** — `captured / authorized`
- **Expiry rate** — intents expiring per minute (spike = gateway degradation)
- **Webhook delivery latency** — time from `confirm` to status change (P50/P95/P99)

---

## 11. Incremental Authorization & Partial Capture

**Current gap:** Capture is all-or-nothing at the original authorized amount.

**Industry use case:** Hotels, car rentals, and marketplaces need to:
- Authorize a larger hold, capture a smaller final amount (partial capture)
- Extend an existing authorization before it expires (incremental auth)
- Release the uncaptured remainder (void/reversal)

**Stripe model:** `capture_method: manual` + `amount_to_capture` parameter on capture.
This maps to additional `InternalAttemptType` values (`VOID`, `INCREMENTAL_AUTH`) and
new `PaymentIntent` transitions.

---

## 12. Refunds

**Current gap:** No refund flow exists. Once `captured`, a payment cannot be reversed.

**Standard model:**
- A `Refund` entity linked to a `PaymentIntent` (not a `PaymentAttempt`) with its own
  state machine: `pending → succeeded | failed`
- A `REFUND` InternalAttemptType dispatched to the gateway
- Partial refunds supported (refund amount ≤ captured amount)
- Ledger entries posted as a reversal of the original capture entries

---

## Priority Matrix

| # | Improvement | Status | Effort | Risk if skipped |
|---|---|---|---|---|
| 1 | Transactional Outbox | **DONE** | — | — |
| 2 | Webhook HMAC verification | **DONE** | — | — |
| 7 | Double-entry ledger | **DONE** | — | — |
| 6 | Card tokenisation | TODO | Medium | PCI DSS non-compliance |
| 9 | Authentication & authorisation | TODO | Medium | Any caller can capture any payment |
| 10 | Observability | TODO | Medium | Invisible degradation in production |
| 12 | Refunds | TODO | Medium | No reversal path for chargebacks |
| 3 | Multi-gateway routing | TODO | High | Single point of failure, lower auth rate |
| 5 | 3DS2 authentication | TODO | High | Liability shift, regulatory non-compliance in EU |
| 11 | Incremental auth / partial capture | TODO | High | Missing for hospitality/marketplace use cases |
| 4 | Durable queue (Kafka/SQS) | Deferred | High | Kafka is deployed; migration is infrastructure-ready |
| 8 | Redis idempotency pre-check | Deferred | Low | Connection pool exhaustion under hot-key contention; load test does not cover this path |
