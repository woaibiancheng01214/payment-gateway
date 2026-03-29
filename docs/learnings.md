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

The stress test evolved from 4 quick checks to 13 suites covering throughput, latency,
race conditions, idempotency, state machine guards, data consistency, webhook replay,
expiry, dispatch retry, sustained load with server metrics, and ledger double-entry
consistency validation.

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
