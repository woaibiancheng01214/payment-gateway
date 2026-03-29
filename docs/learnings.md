# Learnings — Building a Payment Orchestrator from Scratch

What I learned building a production-grade payment orchestrator with Auth + Capture flow,
starting from a simple PRD and iterating through stress testing, bug fixing, and
architectural evolution.

---

## 1. Concurrency is the hardest part of payment systems

The PRD looked simple: create, confirm, capture. But the moment you add async webhooks
and concurrent clients, every operation becomes a race condition waiting to happen.

**Double-confirm race.** 10 concurrent `POST /confirm` on the same intent each created
a `PaymentAttempt`. The status check (`requires_confirmation?`) and record creation were
not atomic. Fix: `SELECT ... FOR UPDATE` (pessimistic write lock) on the `PaymentIntent`
row, plus an existence check for an already-created `PaymentAttempt` inside the lock.

**Double-capture race.** Same pattern — 10 concurrent captures each created a CAPTURE
`InternalAttempt`. Same fix: lock the `PaymentIntent` first, then check if a CAPTURE
already exists.

**Webhook vs. expiry scheduler race.** The webhook handler could read an `InternalAttempt`
as PENDING, then the expiry scheduler marks it EXPIRED in a `REQUIRES_NEW` transaction,
then the webhook handler overwrites it back to SUCCESS. Fix: the webhook handler acquires
the `PaymentIntent` write lock *before* re-reading the `InternalAttempt`, serializing
against the scheduler.

**Takeaway:** In a payment system, every mutation must hold a write lock on the
aggregate root (`PaymentIntent`). Read-check-write without a lock is always wrong under
concurrency. The lock ordering must be consistent everywhere (webhook, confirm, capture,
scheduler) to avoid deadlocks.

---

## 2. Idempotency requires a global lock, not a retry-and-refetch

My first approach to `create` idempotency was: check if the key exists, create if not,
catch `DataIntegrityViolationException` on duplicate, refetch the cached response. This
fails because the transaction is poisoned after the constraint violation in Hibernate —
you can't read anything else in the same transaction.

The correct approach: **PostgreSQL advisory locks** (`pg_advisory_xact_lock`). Hash the
idempotency key to a 64-bit integer, acquire the advisory lock *at the start* of the
transaction, then do the check-and-write. Concurrent requests with the same key block at
the lock until the first one commits. No constraint violations, no poisoned transactions,
and it works across all application instances connected to the same database.

```
SHA-256(idempotency_key) → first 8 bytes → long → pg_advisory_xact_lock(long)
```

**Takeaway:** Distributed idempotency needs a database-level serialization primitive, not
application-level retry logic. Advisory locks are PostgreSQL's built-in answer to this.

**Update (v8):** Advisory locks were later replaced by a two-tier approach:
Redis `SET NX EX` for the fast path (checked via Resilience4j circuit breaker), with the
DB unique constraint on `IdempotencyKey.key` as the source of truth. This eliminates the
advisory lock's connection-holding cost while maintaining correctness. See Learning 23.

---

## 3. Commit first, dispatch after — the transactional outbox lesson

The original design called the gateway HTTP endpoint *inside* the `@Transactional` method.
Two problems:
1. If the JVM crashes after the gateway accepts but before the DB commits, the gateway
   processes the payment but the orchestrator has no record of it.
2. The HTTP call blocks a database connection for the entire request-response time,
   exhausting the connection pool under load.

The fix was the **commit-first dispatch** pattern:
- Save the `InternalAttempt` with `dispatched = false` inside the transaction.
- After the transaction commits, dispatch to the gateway in a separate thread.
- If dispatch succeeds, mark `dispatched = true` in a `REQUIRES_NEW` transaction.
- A scheduler sweeps for `dispatched = false AND status = PENDING AND createdAt < now - 10s`
  and re-dispatches them.

This gives **at-least-once delivery** with zero risk of silent payment loss. The gateway
must handle duplicates (idempotency via `internalAttemptId`), but that's a much easier
problem than recovering from a lost dispatch.

**Evolution:** I initially used `TransactionSynchronization.afterCommit()` on the Tomcat
thread, but this blocked the request thread waiting for the gateway ACK (which could take
seconds). Moving the dispatch to a dedicated 32-thread executor pool freed the Tomcat
threads and raised sustained throughput from 0.1 TPS to 100+ TPS.

**Further evolution:** Replaced `afterCommit` entirely with `TransactionTemplate` —
the confirm/capture methods run the transaction explicitly, get back the result + the
`InternalAttempt` to dispatch, then call `dispatchBestEffort()` *after* the transaction
is committed and the DB connection is released. Cleaner than `afterCommit` and doesn't
require `@Transactional` on the outer method.

---

## 4. Spring `@Transactional` self-invocation doesn't work

Spring's `@Transactional` is implemented via AOP proxies. If method A in a bean calls
method B in the same bean, and B has `@Transactional(propagation = REQUIRES_NEW)`, the
proxy is bypassed and B runs in A's transaction (or no transaction at all).

This bit me with the expiry scheduler: the batch sweep method called per-intent expiry
in the same class. The `REQUIRES_NEW` annotation was silently ignored, so one failed
intent rolled back the entire batch.

Fix: extract the per-intent logic into a separate `@Service` (`PaymentExpiryService`)
so Spring's proxy intercepts each call.

**Takeaway:** Spring AOP proxies only work on external calls. For `REQUIRES_NEW` to
actually start a new transaction, the method must be on a different bean.

---

## 5. Simulating realistic gateway latency reveals real bugs

Initially the mock gateway returned webhooks in 1-2 seconds. Everything looked fine.
When I switched to exponential distribution (floor 1s, cap 60s, median ~3s), new classes
of bugs appeared:

- **Webhook-before-commit:** With very fast webhooks (~1ms), the webhook arrived *before*
  the orchestrator's transaction committed, causing "InternalAttempt not found" errors.
  This was caused by `@Async` proxy interference in the gateway-server.

- **Connection pool exhaustion:** The gateway's synchronous ACK latency (10ms–2000ms,
  exponential) meant dispatch calls could block for seconds. When this happened inside the
  `afterCommit` callback on the Tomcat thread, 100 concurrent workers saturated all Tomcat
  threads waiting for gateway ACKs.

- **Dedup set blocking retries:** The gateway-server's `ConcurrentHashMap` dedup set
  prevented the retry scheduler from re-dispatching attempts that were already "in-flight"
  but whose scheduled tasks had been lost. Fix: remove the attempt from the dedup set
  *after* the webhook fires, not just on entry.

**Takeaway:** Simulate realistic (slow, variable) latency from the start. Fast mocks hide
entire categories of distributed systems bugs.

---

## 6. Webhook security must be built in, not bolted on

The initial webhook endpoint accepted any POST with the right JSON shape. Any caller
could forge a `success` webhook for any `InternalAttempt` and flip a payment to
`authorized`.

The fix: **HMAC-SHA256 webhook signatures.** The gateway signs every webhook body with
`HMAC(shared_secret, "$timestamp.$body")` and sends the signature + timestamp as headers.
The orchestrator verifies using constant-time comparison and rejects if:
- The signature doesn't match
- `|now - timestamp| > 300 seconds` (replay protection)

Implementation detail: the verification runs in a `OncePerRequestFilter` that wraps the
`HttpServletRequest` in a `CachedBodyRequest` so the raw bytes can be read for HMAC
computation *and* the downstream `@RequestBody` controller can still read the body.

---

## 7. CDC + Kafka for event-driven ledger, not REST calls

The first instinct for a double-entry ledger was to add REST calls from the orchestrator
to the ledger-service whenever a payment state changed. This couples the two services —
if the ledger is down, the payment fails.

Better approach: **Debezium CDC** (Change Data Capture) reads the PostgreSQL WAL
(write-ahead log) and publishes row-level changes to Kafka. The ledger-service consumes
`payment_intents` change events and posts balanced double-entry records. No code changes
in the orchestrator, no coupling, and the ledger eventually catches up even if it was
down during the state change.

Posting rules:
- AUTHORIZED → debit `merchant_receivables`, credit `gateway_payable`
- CAPTURED → debit `gateway_payable`, credit `merchant_revenue`
- FAILED/EXPIRED after auth → reversal entries

Dedup via unique constraint on `(paymentIntentId, eventType, entryType, ledgerAccountId)`.

**Takeaway:** CDC is the right pattern when you need other services to react to state
changes without the source service knowing or caring about them.

---

## 8. Stress testing is not optional — it's the design validation

Every major bug was found by the stress test, not by reading code:

| Bug | How found |
|---|---|
| Double-confirm creating multiple PaymentAttempts | 10 concurrent confirms on same intent |
| Double-capture creating multiple InternalAttempts | 10 concurrent captures on authorized intent |
| Webhook replay flipping terminal state | Replaying success webhook on failed intent |
| Create idempotency race (duplicate PaymentIntents) | 50 concurrent creates with same key |
| Connection pool exhaustion under load | 100 TPS sustained for 30s |
| Gateway dedup set blocking retry scheduler | 3100 intents, 314 stuck at `requires_confirmation` |
| Ledger debit/credit imbalance after CDC lag | Ledger consistency validation on 3000+ intents |

The stress test evolved from 4 quick checks to 14 suites covering throughput, latency,
race conditions, idempotency, state machine guards, data consistency, webhook replay,
expiry, dispatch retry, PCI architecture validation, sustained load with server metrics,
and ledger double-entry consistency validation.

**Takeaway:** Write the stress test early and run it after every change. The test will
find bugs you'd never spot in code review.

---

## 9. Monitoring during load tests catches resource issues

Adding Spring Boot Actuator to both servers and collecting CPU, memory, and thread metrics
during the sustained load test revealed:

- Backend thread count jumped from 25 → 149 under 100 TPS (Tomcat pool + dispatch pool)
- Gateway thread count jumped from 22 → 110 (Tomcat pool + scheduled executor)
- Heap usage was reasonable (~200MB backend, ~100MB gateway) — no memory leak
- CPU peaked at 8% backend, 2% gateway — not CPU-bound

Without these metrics, I would have blamed the gateway for the connection pool exhaustion
instead of correctly identifying the `afterCommit` thread-blocking pattern.

---

## 10. State machine design decisions matter

Key decisions that shaped the system:

**`expired → authorized` is forbidden.** If the expiry scheduler marks an intent as
expired, a late webhook cannot resurrect it. This prevents the scenario where a customer
sees "payment failed" but is actually charged. The gateway-side idempotency key ensures
they aren't double-charged on retry.

**Capture failure leaves intent as AUTHORIZED.** The merchant can retry capture. This
mirrors Stripe's behavior and avoids the complexity of a "capture_failed" state.

**No `expired → captured` transition.** Once expired, always expired. If the gateway
actually processed the capture, the ledger's reconciliation job (future improvement) would
detect the discrepancy.

---

## 11. Docker Compose as the integration test environment

The full stack (PostgreSQL with WAL logical replication, Kafka, Debezium, acquirer-core,
external-gateway, ledger-service, and a one-shot connector registration) runs in a single
`docker-compose up`. This proved essential for:

- Testing CDC end-to-end (Debezium needs WAL access)
- Verifying service-to-service communication (webhook callbacks use Docker network names)
- Reproducing production-like failure modes (kill a container, watch recovery)

Infrastructure files needed:
- `infra/init-db.sql` — creates both databases, enables replication
- `infra/debezium-connector.json` — Debezium connector config for the `payment_intents` table
- `Dockerfile` per service — multi-stage build with Gradle

---

## 12. Architecture evolution summary

```
v1: Monolith with in-process mock gateway
    → Found race conditions via stress test

v2: Pessimistic locking + idempotency
    → Fixed double-confirm, double-capture, webhook replay

v3: External gateway-server (separate Spring Boot app)
    → Realistic async webhooks, discovered webhook-before-commit bug

v4: Commit-first dispatch + retry scheduler
    → Eliminated payment loss on crash, but afterCommit blocked Tomcat threads

v5: Async dispatch pool + advisory locks + HMAC webhooks
    → 100+ TPS sustained, correct idempotency, secure webhooks

v6: Debezium CDC + Kafka + Ledger service + Docker Compose
    → Event-driven double-entry ledger, full-stack containerized deployment

v7: Ledger consistency validation + KRaft + observability
    → End-to-end double-entry validation in stress tests, Zookeeper removed,
      idle DB load explained (Hikari pool + schedulers + WAL replication)
```

Each version was driven by a specific failure found in testing, not by upfront design.
The architecture emerged from concrete problems, not abstract principles.

```
v8: PCI service split + Resilience4j + Flyway + ShedLock + structured errors
    → card-vault-service (AES-256-GCM PAN storage), token-service (payment methods),
      card-auth-service (authorization orchestration). Circuit breaker + bulkhead for
      Redis fallback. Flyway migrations replace ddl-auto=update. ShedLock prevents
      duplicate scheduler runs. Input validation, API versioning (/v1/), OpenAPI docs,
      structured JSON logging, dead-letter events for CDC failures.
```

---

## 13. Debezium CDC operation semantics matter for correctness

Debezium change events carry an `op` field: `c` (create), `u` (update), `d` (delete),
`r` (snapshot read), `t` (truncate). The ledger consumer only processes `c` and `u`.

**Why `r` is excluded:** When Debezium first starts (or restarts with `snapshot.mode=initial`),
it reads every existing row and emits `r` events with `after` populated but `before = null`.
If the ledger processed these, it would re-post entries for every historical intent —
duplicating the entire ledger. The unique constraint on
`(paymentIntentId, eventType, entryType, ledgerAccountId)` provides a safety net, but
skipping `r` at the consumer level avoids the wasted work and log noise entirely.

**Why `d` and `t` are excluded:** Payment intents are never deleted or truncated in this
system. If they were, the ledger would need reversal logic triggered by delete events —
a much more complex problem.

The consumer also short-circuits when `before.status == after.status` (a non-status column
was updated, e.g. `updatedAt`), avoiding spurious ledger postings.

---

## 14. Spring filter body consumption: the `CachedBodyRequest` pattern

The HMAC webhook verification filter needs to read the raw request body to compute
`HMAC(secret, "$timestamp.$body")`. But Spring's `HttpServletRequest.getInputStream()`
can only be read once — if the filter consumes it, the downstream `@RequestBody` controller
gets an empty body and returns 400.

The fix: wrap the request in a `CachedBodyRequest` (or `ContentCachingRequestWrapper`
subclass) that reads the body into a byte array on first access and replays it for
subsequent reads. The filter computes the HMAC from the cached bytes, then passes the
wrapper to the filter chain so the controller can deserialize normally.

```
Filter reads body → caches bytes → computes HMAC → passes CachedBodyRequest downstream
Controller reads body → gets cached bytes → deserializes normally
```

`ContentCachingRequestWrapper` from Spring *almost* works, but it only caches after the
request is fully processed — too late for a pre-dispatch filter. A custom wrapper that
eagerly reads `getInputStream()` into a `ByteArrayOutputStream` in the constructor is
more reliable.

---

## 15. KRaft eliminates Zookeeper — fewer moving parts

The original `docker-compose.yml` used Zookeeper for Kafka coordination. Switching to
**KRaft** (Kafka Raft, available since Kafka 3.3, production-ready since 3.5) removed
an entire container:

- **Before:** Zookeeper container + Kafka container (Zookeeper healthcheck was flaky —
  `nc` not available on some images, `ruok` protocol unreliable in Docker)
- **After:** Single Kafka container with `KAFKA_PROCESS_ROLES=broker,controller` and
  `KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka:9093`

Benefits: faster startup (no Zookeeper election), one fewer failure mode, simpler
healthcheck (just `kafka-broker-api-versions`), smaller compose file.

**Takeaway:** If you're on Kafka 3.5+, there's no reason to keep Zookeeper. KRaft is
simpler in every dimension.

---

## 16. Idle database load is normal — understand what's polling

After the stress test finishes and all intents are terminal, pgAdmin still shows:
- ~50 sessions (mostly idle)
- ~6-8 TPS
- ~20k tuples out
- ~600-800 blocks IO

This is not a bug. Three sources explain 100% of the idle load:

**HikariCP connection pool.** Configured with `maximum-pool-size=50`, `minimum-idle=10`.
After a load spike, all 50 connections stay open in `idle` state (`ClientRead` wait event)
until Hikari's `idle-timeout` (default 10 minutes) shrinks the pool back to 10. These
show as "sessions" in pgAdmin but execute zero queries.

**Background schedulers polling empty tables.** Three schedulers run continuously:

| Scheduler | Interval | Query |
|---|---|---|
| `sweepExpiredAuthAttempts` | 10s | `SELECT ... WHERE status = REQUIRES_CONFIRMATION AND updatedAt < deadline` |
| `sweepExpiredCaptureAttempts` | 10s | `SELECT ... WHERE type = CAPTURE AND status IN (PENDING, TIMEOUT)` |
| `sweepUndispatchedAttempts` | **3s** | `SELECT ... WHERE dispatched = false AND status = PENDING` |

The 3-second dispatch-retry sweep is the chattiest — ~20 queries/minute returning empty
result sets. Each still touches index pages, producing the tuples-out and block IO.

**Debezium WAL replication.** The replication slot (`payment_gateway_slot`) streams WAL
changes continuously. Even at rest, the heartbeat (every 10s) keeps the connection active
and shows as an `active` session with `WalSenderWaitForWAL`.

**Takeaway:** "High sessions" after a test usually means idle connection pools, not runaway
queries. Check `pg_stat_activity` and look at the `state` column — `idle` connections are
harmless. The real cost is the scheduler polling, which can be tuned by increasing intervals
when no work is expected.

---

## 17. Validating eventual consistency across async CDC pipelines

The ledger service receives payment state changes via Debezium → Kafka — an eventually
consistent pipeline with variable latency. Testing this correctly requires:

**Wait for CDC propagation.** After the payment drain completes (all intents terminal),
the ledger may still be processing Kafka messages. The stress test waits 30 seconds before
checking ledger entries. This duration was determined empirically — with 3000+ intents,
CDC lag peaks at ~20 seconds during the webhook drain phase.

**Global invariant: total debits == total credits.** The simplest correctness check.
Query `GET /ledger/balances`, sum all DEBIT amounts across all accounts, sum all CREDIT
amounts, and assert equality. Any imbalance means a posting was written with only one leg
(debit without credit or vice versa).

**Per-intent pattern validation.** Sample intents and check entry patterns match terminal
state:
- `authorized` → exactly 1 AUTHORIZED debit + 1 AUTHORIZED credit
- `captured` → auth pair + capture pair (4 entries total)
- `failed` → 0 entries (failed before auth) or auth pair + FAILED_REVERSAL pair
- `expired` → 0 entries (expired before auth) or auth pair + EXPIRED_REVERSAL pair

**Per-intent balance.** For each sampled intent, assert debits == credits. This catches
partial postings that might not show up in the global balance (e.g., two intents with
opposite imbalances canceling out).

**Tolerance for CDC lag.** Some authorized intents may have zero ledger entries if CDC
hasn't caught up. The test reports these as warnings, not failures — they indicate
propagation delay, not data corruption. Re-running the check after a longer wait confirms
they resolve.

---

## 18. Card tokenization — the PAN must never touch the database

The original design stored `paymentMethod` as a plain string (`"card_4242"`) in
`PaymentAttempt`. In a real system that string would be a 16-digit PAN, making every
service that reads `payment_attempts` — the orchestrator, the scheduler, the gateway
client, the detail endpoint — PCI DSS Level 1 in-scope.

**The fix: tokenize at the boundary.** When the confirm request arrives with raw card
data (`cardNumber`, `cardholderName`, `expiryMonth`, `expiryYear`, `cvc`), the service
tokenizes immediately via an in-memory `TokenVaultService` and stores only the opaque
token (`tok_<uuid>`) in the database. The raw PAN lives exclusively in the vault's
`ConcurrentHashMap` — never serialized to disk, never written to logs.

```
Client POST /confirm  →  tokenize()  →  DB stores "tok_abc123"
                                     →  vault holds {PAN, expiry, CVC} in memory only
```

**Derived fields for display and routing:**
- `cardBrand` — derived from the BIN (first digits): `4xxx` → visa, `51-55xx` → mastercard,
  `34/37xx` → amex. Stored in `PaymentAttempt` for display and passed to the gateway for
  behavior routing.
- `last4` — last 4 digits of the PAN, safe for receipts and customer-facing UIs.

The gateway never sees the raw card. It receives `paymentToken` + `cardBrand` and uses
`cardBrand` for behavior decisions (success/fail/hang simulation). This models the real
architecture where acquirers pass network tokens or vault references to processors.

**PCI DSS 4.0 scope reduction:** With this architecture, only the `TokenVaultService` (and
the HTTP endpoint that receives the card data) are in the CDE (Cardholder Data Environment).
Every other component — database, schedulers, gateway client, ledger service — handles
only tokens and is out of scope. In production the vault would be a separate, hardened
service with HSM-backed encryption, but the architecture is identical.

**Takeaway:** Tokenize as early as possible (at the API boundary), store only the token,
and derive everything else (brand, last4) at tokenization time. The rest of the system
should never need to detokenize.

**Update (v8):** The in-memory `TokenVaultService` was replaced by three PCI-scoped services:
`card-vault-service` (AES-256-GCM encrypted PAN in PostgreSQL), `token-service` (payment
method references), and `card-auth-service` (authorization orchestration). These services
have no external port mapping — accessible only within the Docker network. See Learning 22.

---

## 19. Enriching domain objects — what fields a PaymentIntent actually needs

The initial `PaymentIntent` had only `amount` and `currency`. That's enough for the state
machine to work, but it makes the system feel like a toy. Real payment intents (Stripe,
Adyen) carry merchant context that drives downstream behavior.

**Fields added:**

| Field | Why it matters |
|---|---|
| `description` | Human-readable purpose ("Order #1234"). Appears in dashboards, dispute evidence, and reconciliation reports. |
| `statementDescriptor` | What the cardholder sees on their bank statement. Visa caps at 22 characters — enforced with `.take(22)` at creation time. |
| `metadata` | Arbitrary key-value pairs for the merchant (`orderId`, `sku`, `userId`). Stored as JSON TEXT in the database. Never used by the payment system itself — purely merchant-side context. |
| `customerEmail` | For receipts, fraud scoring, and chargeback evidence. |
| `customerId` | Merchant's customer reference. Enables per-customer payment history queries. |

**Design decisions:**
- `metadata` is stored as a JSON string column, not a `jsonb` column. Keeps the code
  database-agnostic and avoids JPA/Hibernate `jsonb` mapping complexity. Serialized with
  Jackson on write, deserialized on read in the DTO extension function.
- `metadata` and `statementDescriptor` are excluded from the idempotency hash. Two creates
  with the same amount/currency/description/customer but different metadata are the same
  payment intent — metadata is merchant-side context, not payment identity.
- All new fields are nullable with defaults, so existing code paths (scheduler, webhook
  handler, capture) don't need changes.

**Takeaway:** A domain object should carry enough context for every downstream consumer —
dashboards, receipts, reconciliation, fraud — without requiring a join back to the
merchant's system. But keep the idempotency boundary tight: only fields that define
"this is a different payment" should affect the hash.

---

## 20. Backward compatibility when renaming entity fields with Hibernate `ddl-auto=update`

Renaming `PaymentAttempt.paymentMethod` to `PaymentAttempt.paymentToken` sounds simple,
but `ddl-auto=update` doesn't rename columns — it creates a new one and leaves the old one
orphaned. With a real migration tool (Flyway, Liquibase) you'd write `ALTER TABLE ... RENAME
COLUMN`. With `ddl-auto=update`, the cleanest approach is:

```kotlin
@Column(name = "payment_method", nullable = false)
val paymentToken: String
```

The Kotlin field gets the semantically correct name (`paymentToken`), but the `@Column(name)`
annotation keeps the database column as `payment_method`. No schema change, no data
migration, no orphaned columns. The code reads clearly (`attempt.paymentToken`) while the
database stays stable.

**Takeaway:** When using `ddl-auto=update`, rename the code, not the column. Use
`@Column(name = "old_name")` to bridge the gap. Reserve actual column renames for
migration tools that can do it atomically.

---

## 21. Adding new entity fields doesn't break CDC consumers

When new nullable columns (`description`, `metadata`, `customer_email`, etc.) were added
to `payment_intents`, Debezium started including them in CDC events to Kafka. The
ledger-service consumer could have broken if it was strict about unknown fields.

It didn't, because the Debezium DTOs use `@JsonIgnoreProperties(ignoreUnknown = true)`:

```kotlin
@JsonIgnoreProperties(ignoreUnknown = true)
data class PaymentIntentSnapshot(
    val id: String,
    val amount: Long,
    val currency: String,
    val status: String
)
```

The ledger only needs `id`, `amount`, `currency`, and `status`. New columns are silently
ignored during deserialization. No code change required in the ledger service.

**Takeaway:** CDC consumers should always use `ignoreUnknown = true` (or equivalent).
Schema evolution in the source table is inevitable — consumers should be resilient to
additive changes by default. Only opt into new fields when you actually need them.

---

## 22. PCI scope reduction through service boundaries, not just tokenization

Learning 18 described in-memory tokenization. The next step was splitting card handling
into dedicated PCI-scoped services with clear boundaries:

```
NON-PCI ZONE                          PCI ZONE (internal only)
┌──────────────────────┐     RPC     ┌──────────────────┐
│ acquirer-core-backend│────────────▶│ card-vault-service│ AES-256-GCM encrypted PAN
│ (checkout-service)   │     RPC     │ token-service    │ PaymentMethod (brand, last4)
│ port 8080, external  │────────────▶│ card-auth-service │ InternalAttempt + gateway dispatch
└──────────────────────┘             └──────────────────┘
                                      No host port mapping
```

**Key architectural decisions:**

1. **PCI services have no external port mapping.** In docker-compose, the `card-vault-service`,
   `token-service`, and `card-auth-service` have no `ports:` section. They're accessible only
   within the Docker network by service name. This is enforced at the infrastructure level,
   not application level.

2. **Raw PAN still transits through the checkout-service** (accepted from browser over HTTPS)
   but is immediately forwarded to `card-vault-service` and never persisted in the checkout DB.
   Full client-side tokenization (Stripe.js-style iframe) would eliminate this transit entirely
   — that's a future improvement.

3. **Webhook routing stays at the checkout-service.** The external gateway sends webhooks to
   the checkout-service (external-facing). The checkout-service forwards to `card-auth-service`
   internally. This means the stress test's `signed_webhook_post()` still works without changes.

4. **Each PCI service has its own database.** `card_vault`, `token_service`, `card_auth` are
   separate PostgreSQL databases, each with their own Flyway migrations. This enforces data
   isolation — the checkout-service cannot accidentally query raw card data.

**Port conflict gotcha:** `card-vault-service` initially used port 8083, which collides with
Debezium Connect's external port mapping. The stress test caught this — the "PCI service not
exposed" check passed for token and auth but failed for vault. Changed to 8086.

**Takeaway:** Service-level PCI boundary enforcement beats code-level trust. Even if a developer
adds a debug endpoint to the checkout-service, it can't reach card data because it's in a
different database behind a service that has no host port.

---

## 23. Resilience4j circuit breaker + bulkhead — the correct Redis fallback pattern

The original Redis lock code had a dangerous fallback: if Redis was down, it silently
proceeded as if the lock was acquired (fail-open). This defeats the entire purpose of
distributed locking.

**The fix: DistributedLockManager with three layers:**

```
Layer 1: Redis lock via Circuit Breaker
  ↓ (if Redis down or CB open)
Layer 2: DB-only locking via Bulkhead (max 5 concurrent)
  ↓ (if Bulkhead full)
Layer 3: 503 Service Unavailable
```

**Circuit Breaker** (`redisLockCircuitBreaker`): wraps all Redis operations. After 50% failure
rate over 10 calls, the circuit opens and stops hitting Redis for 30 seconds. This prevents
cascading failure — when Redis is down, we stop burning timeout waiting on dead connections.

**Bulkhead** (`dbLockFallbackBulkhead`): limits concurrent DB-fallback requests to 5 per pod.
Without this, all requests would fall back to DB pessimistic locking simultaneously,
exhausting the HikariCP connection pool (50 connections, each held for the full lock duration).
The bulkhead with `maxWaitDuration=0ms` means if 5 threads are already using DB fallback,
the 6th gets rejected immediately with 503.

**For the create flow (idempotency):** Redis is fail-open by design — the DB unique constraint
is the source of truth. The circuit breaker just prevents wasting time on dead Redis connections.

**For the confirm flow (distributed lock):** Redis is fail-safe — if Redis is unavailable,
we fall back to DB-level `SELECT ... FOR UPDATE` gated by the bulkhead.

```kotlin
enum class LockSource { REDIS, DB_FALLBACK }
data class LockResult(val lockValue: String?, val acquiredVia: LockSource)
```

The `releaseLock()` method only releases the Redis lock if it was acquired via Redis.
DB locks are released automatically when the transaction commits.

**Takeaway:** "Fall back to DB" sounds simple but has a hidden danger: if all requests
simultaneously fall back to DB locking, the connection pool is the bottleneck. A bulkhead
caps the fallback concurrency and fails fast for the rest.

---

## 24. ShedLock — single-instance scheduling in a stateless world

Spring's `@Scheduled` runs on every application instance. With 3 replicas, the expiry
scheduler runs 3x, each instance attempting to expire the same intents. The pessimistic
lock prevents data corruption, but it wastes DB connections and creates unnecessary
lock contention.

**ShedLock** provides exactly-once scheduling via a database lock table:

```sql
CREATE TABLE shedlock (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
```

```kotlin
@Scheduled(fixedDelay = 10000)
@SchedulerLock(name = "sweepExpiredAuthAttempts", lockAtMostFor = "9s", lockAtLeastFor = "5s")
fun sweepExpiredAuthAttempts() { ... }
```

The `lockAtMostFor` (9s) < the `fixedDelay` (10s) ensures the lock always expires before the
next scheduled invocation, preventing deadlocks if the locking instance crashes.
`lockAtLeastFor` (5s) prevents rapid re-execution if the task completes quickly.

**Takeaway:** Any scheduler that reads and mutates shared state should use distributed locking.
ShedLock with JDBC is the simplest solution — no Redis dependency, no coordination protocol,
just a database table with atomic upsert semantics.

---

## 25. Flyway migrations — why `ddl-auto=update` is a ticking time bomb

Learning 20 described using `@Column(name)` to work around `ddl-auto=update`'s limitations.
The real fix was switching to **Flyway**:

- `ddl-auto=validate` (not `update`) — Hibernate checks schema alignment at startup without
  modifying anything. If a migration is missing, the app fails fast instead of silently
  creating a wonky schema.
- Versioned SQL files (`V1__initial_schema.sql`, `V2__shedlock_table.sql`) provide an
  auditable history of every schema change.
- Each service has its own migration path because each service has its own database.

**Gotcha with Spring Boot 3.2 + Flyway:** Spring Boot 3.2 ships with Flyway 9.x, which
bundles PostgreSQL support in `flyway-core`. The separate `flyway-database-postgresql`
artifact is only needed for Flyway 10+ (Spring Boot 3.3+). Adding it without a version
causes a "could not resolve" build failure.

**Takeaway:** Schema management is infrastructure, not application code. Let the migration
tool own the DDL, and let Hibernate validate that the code matches.

---

## 26. Debezium timestamp formats depend on your column type and converter

When the ledger consumer's `PaymentIntentSnapshot` added `created_at` and `updated_at`
fields typed as `Long` (expecting epoch microseconds), every CDC event failed with:

```
Cannot deserialize value of type `java.lang.Long` from String "2026-03-29T07:48:14.927098Z"
```

**Root cause:** The Debezium connector uses `JsonConverter` with `schemas.enable=false`.
For `TIMESTAMP WITH TIME ZONE` columns, the JSON converter serializes them as ISO 8601
strings, not epoch microseconds. The `io.debezium.time.MicroTimestamp` representation
only appears when using the Avro converter or `schemas.enable=true`.

**Fix:** Changed the snapshot DTO to accept `String?` and added a `parseTimestamp()` method
that handles both formats:

```kotlin
private fun parseTimestamp(value: String): Instant =
    try { Instant.parse(value) }           // ISO string "2026-03-29T07:48:14Z"
    catch (e: Exception) {
        try {
            val micros = value.toLong()     // Epoch microseconds
            Instant.ofEpochSecond(micros / 1_000_000, (micros % 1_000_000) * 1_000)
        } catch (e2: Exception) { Instant.now() }
    }
```

This made the consumer resilient to converter changes — if someone later switches to Avro
or enables schemas, the consumer still works.

**Takeaway:** Never assume a specific serialization format for Debezium timestamps. The
format depends on the column type, the converter, and the `schemas.enable` setting. Parse
defensively.

---

## 27. Enums everywhere — bounded values should be types, not strings

The original codebase used raw strings for currencies (`"USD"`), card brands (`"visa"`),
and providers (`"mock-visa"`). This caused:

- No compile-time validation — typos like `"usd"` vs `"USD"` pass silently
- No IDE autocomplete or exhaustive `when` checking
- The metadata table showed `"mock-visa"` strings that could diverge across services

**The fix:** Kotlin enums for every bounded-value field:

| Enum | Values | Used for |
|---|---|---|
| `Currency` | 30 ISO 4217 codes | PaymentIntent.currency, request validation |
| `CardBrand` | VISA, MASTERCARD, AMEX, DISCOVER, JCB, UNIONPAY, UNKNOWN | PaymentAttempt.cardBrand, BIN detection |
| `Provider` | MOCK_VISA, MOCK_MASTERCARD, MOCK_GENERIC | InternalAttempt.provider |
| `PaymentMethodStatus` | ACTIVE, EXPIRED, DELETED | PaymentMethod lifecycle |

The `Currency` enum includes a `fromString()` companion method that provides clear error
messages: `"Unsupported currency: XYZ"` instead of a generic 500.

**JPA mapping:** All enums use `@Enumerated(EnumType.STRING)` — stores the name as a string
in the database, not the ordinal. This makes the data readable and survives enum reordering.

**Takeaway:** If a field has a finite set of valid values, make it an enum. The cost is near
zero (one enum class file) and the benefit is compile-time safety, IDE support, and
self-documenting validation.

---

## 28. `data class` entities break JPA — use regular classes

Kotlin `data class` is great for DTOs but harmful for JPA entities:

- `equals()` includes all fields — including mutable ones like `status` and `updatedAt`.
  This breaks `Set<Entity>` operations: after mutating `status`, the entity has a different
  hash and can't be found in the set.
- `hashCode()` changes when mutable fields change — same problem.
- `toString()` includes all fields — triggers lazy-loading of relationships, causing
  `LazyInitializationException` outside a session.
- `copy()` creates detached copies that confuse Hibernate's first-level cache.

**The fix:** Regular `class` with explicit `equals`/`hashCode` on `id` only:

```kotlin
@Entity
class PaymentIntent(
    @Id val id: String = UUID.randomUUID().toString(),
    var status: PaymentIntentStatus = ...,
    ...
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PaymentIntent) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}
```

This ensures identity-based equality (two entities with the same `id` are equal regardless
of field values), stable hash codes, and no lazy-loading surprises.

**Takeaway:** JPA entities are identity objects, not value objects. Use `class` + id-based
equality. Reserve `data class` for DTOs and value types.

---

## 29. Structured error responses — machine-readable errors for API consumers

The original error handling returned ad-hoc `Map<String, String>`:

```kotlin
ResponseEntity.badRequest().body(mapOf("error" to e.message))
```

This forces API consumers to parse arbitrary strings to understand what went wrong. No
error codes, no field-level detail, no machine-readable categorization.

**The fix:** A standard `ApiError` class + global `@ControllerAdvice`:

```kotlin
data class ApiError(
    val type: String,      // "invalid_request_error", "conflict_error", "api_error"
    val code: String,      // "amount_invalid", "currency_invalid", "state_conflict"
    val message: String,   // Human-readable description
    val param: String?     // Which field caused the error (for validation errors)
)
```

The `@ControllerAdvice` handles all exception types consistently:
- `IllegalArgumentException` → 400 + `invalid_request_error`
- `IllegalStateException` → 409 + `conflict_error`
- `MethodArgumentNotValidException` → 400 + field-level error from Bean Validation
- `Exception` → 500 + generic `api_error` (with full stacktrace logged server-side)

Per-controller `@ExceptionHandler` methods were removed — all error handling flows through
one place.

**Takeaway:** Error responses are part of your API contract. Define them as structured
objects early. Adding `@ControllerAdvice` takes 30 minutes and saves every API consumer
from guessing at error formats.

---

## 30. Dead-letter events — CDC failures must not be silently swallowed

The original ledger consumer wrapped everything in try/catch and logged the error:

```kotlin
catch (e: Exception) {
    log.error("Failed to process CDC event: ${e.message}", e)
}
```

The Kafka offset still advances, so the failed event is lost forever. If the failure was
transient (DB connection timeout), the event could have succeeded on retry. If it was
permanent (schema mismatch), it needs manual investigation.

**The fix:**

1. **Persist failed events** to a `dead_letter_events` table with the full payload, error
   message, and timestamps. This preserves the event even if the consumer moves on.

2. **Expose a retry API:** `POST /v1/ledger/dead-letter-events/{id}/retry` re-processes
   the original payload. If it succeeds, the event is marked as resolved. This enables
   manual recovery after a transient issue is fixed.

3. **Query API:** `GET /v1/ledger/dead-letter-events?unresolvedOnly=true` lists pending
   failures for operations/debugging.

The dead-letter table also catches the timestamp parsing bug from Learning 26 — when the
`Long` vs `String` mismatch caused every CDC event to fail, the dead-letter table preserved
all events for replay after the fix.

**Takeaway:** Any event-driven consumer that catches and logs errors is silently losing
data. Persist failures to a dead-letter store and provide retry/query APIs.

---

## 31. Prometheus + Grafana > polling `docker stats` in a script

The stress test already collected CPU, heap, and thread counts via Spring Boot Actuator
during load. But those are point-in-time snapshots printed to stdout — no history, no
visualization, no correlation across services.

Adding Prometheus + Grafana + cAdvisor + postgres-exporter to docker-compose gave:

- **Per-service JVM metrics** (Micrometer → `/actuator/prometheus`): heap used/committed/max,
  GC pause rate, live thread count by state, HTTP request rate and P99 latency, HikariCP
  active/pending/max connections, Tomcat busy threads.
- **Per-container resource metrics** (cAdvisor): CPU %, memory usage and % of limit,
  network RX/TX rate, filesystem I/O — for *every* container including Postgres, Redis, Kafka.
- **PostgreSQL metrics** (postgres-exporter): active connections per database, transaction
  commit/rollback rate, rows fetched/inserted/updated/deleted, locks by mode, cache hit ratio,
  replication lag.

The implementation required just two changes per service:
1. `runtimeOnly("io.micrometer:micrometer-registry-prometheus")` in `build.gradle.kts`
2. Add `prometheus` to `management.endpoints.web.exposure.include` in `application.properties`

Three pre-built Grafana dashboards are auto-provisioned from JSON files via Grafana's
provisioning API — no manual dashboard setup needed after `docker-compose up`.

**Prometheus data persistence:** Without a Docker volume, Prometheus time-series data is
lost on container restart. Adding `prometheus-data:/prometheus` as a named volume makes
metrics survive `docker-compose down/up` (only cleared with `docker-compose down -v`).

**Takeaway:** Real-time dashboards during stress tests reveal patterns that point-in-time
snapshots miss — like a gradual connection pool fill-up or a CPU spike that correlates with
GC pauses. The setup cost (4 containers + 1 dependency per service) pays for itself on
the first load test.

---

## 32. Stress test thread coupling hides true system throughput

The stress test targeted 100 TPS with 100 worker threads, each pacing at 1 request/second.
But each thread did `create → confirm → sleep(remaining)` sequentially. Since confirm took
P50=1452ms (synchronous RPC chain through vault → token → auth → gateway ACK), the
thread's loop iteration took ~2.2s — cutting actual throughput to ~40 TPS (1288 intents
in 30s instead of ~3000).

**The fix:** Separate create and confirm into independent thread pools with a queue between
them:

```
100 create threads ──→ Queue ──→ 100 confirm threads
    (pace: 1/s each)              (drain as fast as possible)
```

Create threads pace at 1 TPS each and enqueue intent IDs. Confirm threads drain the queue
independently. After this change, throughput jumped from 40 TPS to 76 TPS — the remaining
gap from 100 TPS is genuine server-side latency, not test harness coupling.

**Takeaway:** When a load test produces lower-than-expected throughput, check whether the
test client is the bottleneck before blaming the server. Sequential request chaining in a
single thread is a classic throughput limiter.

---

## 33. HikariCP pool sizing — match the pool to your actual concurrency

With 200 concurrent threads (100 create + 100 confirm) hitting the backend, but only 50
HikariCP connections, threads queue for a database connection. Create P50 jumped to 1107ms —
a simple DB insert that should take <50ms was spending most of its time in the connection
wait queue.

The formula: if your sustained concurrency is N threads and each holds a connection for T
seconds, you need at least N × T connections. With 200 threads and ~25ms average transaction
time, 5 concurrent connections would suffice — but that assumes uniform arrivals. Under
bursty load with lock contention (pessimistic writes hold connections longer), you need
headroom. Increasing from 50 to 80 resolved the queuing.

**Diagnosis clue:** If create latency (a simple INSERT) scales linearly with thread count
but the actual query is fast, it's almost always connection pool exhaustion, not a slow
query or missing index.

**Takeaway:** Size HikariCP's `maximum-pool-size` for your peak concurrent *connection holders*,
not your peak TPS. Account for pessimistic locks and long-running transactions holding
connections longer than simple queries.

---

## 34. Synchronous RPC chains amplify latency non-linearly

The confirm path traverses 6 synchronous HTTP hops:

```
checkout → vault(encrypt) → token(create) → auth-service → vault(read) → token(read) → gateway ACK
```

Each hop adds its own latency: network round-trip (~1ms in Docker), serialization (~1ms),
and the actual work. The gateway ACK alone contributes P50=150ms (exponential distribution,
floor 10ms, cap 2000ms). Multiplied across 6 hops under load, with thread-pool contention
at each service, confirm P50 reached 2002ms.

**Key insight:** Synchronous chains don't just *add* latencies — they *multiply* tail
latency effects. If any single service in the chain is slow (GC pause, connection pool
wait, thread exhaustion), the entire chain blocks. The probability of hitting at least one
slow service grows with the number of hops.

**The gateway's ACK delay is intentional simulation** (modeling real acquirer response
times). Reducing `gateway.ack.cap-ms` from 2000 to 200 would bring confirm P50 down to
~200ms but makes the simulation unrealistic. The current ~76 TPS with realistic gateway
latency is a genuine measurement of system capacity under real-world conditions.

**Takeaway:** Long synchronous RPC chains are the enemy of low-latency at scale. Each
hop is a multiplier on tail latency. In production, consider: (a) parallelizing independent
calls (vault + token could run concurrently), (b) async dispatch patterns where the
response doesn't wait for downstream completion, (c) accepting that synchronous chains
set a floor on P99 that scales with the chain length.

---

## 35. When splitting a monolith, carry forward ALL patterns — not just structural ones

Splitting `acquirer-core-backend` into `card-vault-service`, `token-service`, and
`card-auth-service` replicated the *structural* patterns correctly: each service got
Flyway, logback, OpenAPI, `GlobalExceptionHandler`, `ApiError`. But the *behavioral*
patterns were left behind:

| Pattern | acquirer-core-backend | New services |
|---------|----------------------|-------------|
| Input validation (`@Valid`, Bean Validation) | Yes | **Missing** |
| Resilience4j circuit breakers on HTTP clients | Yes (DistributedLockManager) | **Missing** (GatewayClient, TokenClient, VaultClient all bare) |
| ShedLock on `@Scheduled` | Yes | **Missing** (AuthCleanupScheduler ran on all instances) |
| Idempotency-key support | Yes (create flow) | **Missing** |

**Root cause:** The new services were built by extracting code, not by instantiating a
template with all patterns pre-wired. The extraction focused on *what* the code does
(encrypt, tokenize, dispatch) without carrying *how* the code protects itself.

**Fix:** Added Bean Validation to all 4 new services, Resilience4j circuit breakers to
all 3 RPC clients in `card-auth-service`, ShedLock to `AuthCleanupScheduler`, and a
circuit breaker to `MerchantClient` in `acquirer-core-backend`.

**Takeaway:** Treat cross-cutting concerns as a checklist when creating new services.
Structural concerns (logging, migrations, error responses) are easy to remember because
they cause immediate startup failures. Behavioral concerns (validation, resilience,
distributed locking) only surface under load or failure — so they're forgotten until
production blows up.

---

## 36. Internal APIs need validation too — defense in depth

The initial assumption was: "These are internal services called only by our own code.
The caller validates, so the callee doesn't need to." This is wrong for three reasons:

1. **Callers evolve independently.** When `acquirer-core-backend` was refactored, a new
   code path might forget to validate before calling `card-vault-service`. Without server-side
   validation, an invalid PAN gets encrypted and stored — corrupting the vault.

2. **Internal APIs may be exposed later.** The merchant-service started as internal-only
   but got a public `/v1/merchants` controller. Without validation on the internal endpoint,
   any code path that uses the internal API bypasses validation.

3. **Defense in depth is a security principle, not overhead.** In payment systems, the
   cost of processing invalid data (corrupted card records, ledger imbalances) far exceeds
   the cost of a few `@Min`/`@NotBlank` annotations.

**Specific bugs this would have caught:**
- `card-vault-service` accepting `expMonth=13` or `expMonth=-1` → encrypted garbage
- `token-service` silently defaulting invalid brand strings to `UNKNOWN` → misleading data
- `card-auth-service` accepting `amount=0` or `amount=-100` → nonsensical authorization

**Fix:** Added `@Valid` + Bean Validation constraints to every controller across all 4
new services, and changed the token-service from silently defaulting to `CardBrand.UNKNOWN`
to rejecting invalid brands with a 400 error.

**Takeaway:** Validate at every service boundary, not just the external-facing one. The
`@Valid` annotation costs one word per controller method; the protection is permanent.

---

## 37. Every outbound HTTP call needs a circuit breaker

The `card-auth-service` confirm flow chains 3 HTTP calls:
```
auth-service → token-service.getPaymentMethodForAuth()
auth-service → vault-service.getCardData()
auth-service → gateway.authorize()
```

Without circuit breakers, if `token-service` goes down:
1. Every confirm request waits 5s for the TCP timeout
2. All 200 Tomcat threads fill up waiting for dead connections
3. `card-auth-service` becomes unresponsive to ALL requests (including webhooks, captures)
4. `acquirer-core-backend` cascades the failure (its `AuthClient` calls time out)
5. The entire payment flow is dead

With circuit breakers:
1. After 5 failures in 10 calls, the circuit opens
2. Subsequent calls fail immediately with `CircuitBreakerOpenException` (no 5s wait)
3. `card-auth-service` stays responsive for other operations
4. After 15s, the circuit tries `token-service` again (half-open state)

The gateway client additionally gets a **bulkhead** (`maxConcurrentCalls=20`) because
the gateway ACK latency is intentionally high (P50=150ms, cap 2000ms). Without a bulkhead,
a burst of 200 concurrent dispatches would tie up 200 threads waiting for gateway ACKs.

**Takeaway:** Circuit breakers protect the *caller*, not the callee. They prevent one
failing dependency from consuming all resources and cascading to every other operation.
Add them to every outbound HTTP call, even internal ones.

---

## 38. ShedLock must follow @Scheduled — they are inseparable

`AuthCleanupScheduler` had two `@Scheduled` methods but no `@SchedulerLock`. In a
3-replica deployment, this means:
- 3 instances all run `sweepUndispatchedAttempts` every 3 seconds
- Each finds the same undispatched `InternalAttempt` and dispatches it to the gateway
- The gateway receives 3 identical requests, processes all 3, fires 3 webhooks
- The checkout-service processes 3 webhooks — the terminal state guard prevents data
  corruption, but the wasted work is 3x and the unnecessary webhook volume is 3x

ShedLock prevents this by acquiring a database row lock before the scheduled method runs.
Only the instance that wins the lock executes; others skip the cycle.

```kotlin
@Scheduled(fixedDelay = 3000)
@SchedulerLock(name = "sweepUndispatchedAttempts", lockAtMostFor = "9s", lockAtLeastFor = "2s")
fun sweepUndispatchedAttempts() { ... }
```

The `lockAtLeastFor` prevents rapid re-execution if the sweep completes in <100ms,
avoiding wasteful tight loops. `lockAtMostFor` ensures the lock expires if the
instance crashes mid-sweep.

**Takeaway:** `@Scheduled` without `@SchedulerLock` is a bug in any multi-instance
deployment. Treat them as an inseparable pair.
