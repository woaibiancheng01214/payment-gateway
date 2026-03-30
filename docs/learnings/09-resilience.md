# Resilience & Scheduling

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

---

## 43. Circuit breakers need slow-call detection, not just failure detection

All circuit breakers were configured with `failureRateThreshold(50%)` but no slow-call
threshold. A degraded downstream service responding at 4999ms (just under the 5s read
timeout) would never trip the circuit breaker — every call "succeeds" — but would consume
threads and connections, eventually exhausting the pool.

This is worse than a failed service because the circuit breaker stays CLOSED, the system
doesn't shed load, and all Tomcat threads pile up waiting for slow responses.

**Fix:** Added slow-call detection to all circuit breakers:
```kotlin
.slowCallRateThreshold(80f)
.slowCallDurationThreshold(Duration.ofSeconds(2))
.slidingWindowSize(20)
.minimumNumberOfCalls(10)
```

If 80% of calls in the window take >2 seconds, the circuit opens and subsequent calls
fail immediately, freeing threads and connections. The half-open state then tests whether
the downstream service has recovered.

**Takeaway:** A circuit breaker without slow-call detection is half a circuit breaker.
Degraded performance is a more common failure mode than complete outage, and it's more
dangerous because it's invisible to failure-only metrics.
