# Concurrency & Locking

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

## 45. Pessimistic lock timeout prevents connection pool exhaustion under contention

`findByIdForUpdate()` uses `@Lock(PESSIMISTIC_WRITE)` to serialize mutations on a
`PaymentIntent`. Without a timeout, a thread waiting for the lock holds a HikariCP
connection indefinitely. Under contention (webhook + capture racing on the same intent),
multiple threads can wait simultaneously, each consuming a connection.

With 80 connections and 300 TPS peak, a hot PaymentIntent receiving concurrent webhook +
capture + scheduler sweep can deadlock the entire pool in seconds if waits are unbounded.

**Fix:** Added `@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))` — 3-second maximum wait. If the lock isn't acquired in 3 seconds,
`LockTimeoutException` is thrown and caught by `GlobalExceptionHandler`, returning
HTTP 409 Conflict with a retry hint.

**Takeaway:** Every pessimistic lock needs a timeout. Unbounded lock waits turn connection
pools into deadlock vectors under contention. The timeout should be short enough to fail
fast but long enough to survive normal lock hold times (typically <100ms for a simple
update).

---

## 46. Idempotency keys must have a TTL — unbounded growth is a silent time bomb

The `idempotency_keys` table stored a row for every `create` call with an idempotency key,
with no expiry or cleanup. The Redis layer had a 24-hour TTL, but the database (the source
of truth) grew indefinitely.

At 100 TPS with 50% of creates using idempotency keys, that's 4.3M rows/day. After 30 days:
129M rows. The `findById()` lookup in `createPaymentIntent()` (line 66) scans the primary
key index — which works fine at 1M rows but degrades at 100M+. The unique constraint check
on insert also slows down as the index grows.

**Fix:**
1. Flyway migration adding `expires_at` column (default `created_at + 48h`) with index
2. ShedLock-protected hourly scheduler deleting expired rows
3. Entity updated with `expiresAt` field

The 48-hour TTL matches Stripe's idempotency key expiry. After 48 hours, the same key can
be reused for a different request — this is by design, since idempotency keys protect
against retries, not against intentional new requests days later.

**Takeaway:** Every append-only table needs a lifecycle. If it doesn't have a natural
deletion trigger, add a time-based cleanup. The table that "never needs cleanup" is the
one that fills the disk at 3am.
