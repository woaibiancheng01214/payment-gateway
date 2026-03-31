# Performance

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

## 42. Caffeine local cache eliminates redundant inter-service lookups

Every `createPaymentIntent` and `listPaymentIntentsByMerchant` called
`merchantClient.merchantExists()` — a synchronous HTTP call to merchant-service. Merchant
data changes at CRUD frequency (maybe 1/hour), but existence checks happen at transaction
frequency (100/second).

**Fix:** Wrapped the merchant existence check in a Caffeine cache with 5-minute TTL and
10K max entries. Cache hits avoid the HTTP round-trip entirely (~0ms vs ~5-50ms).

```kotlin
private val merchantCache = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(Duration.ofMinutes(5))
    .build<String, Boolean>()
```

At 100 TPS, this eliminates ~100 HTTP calls/second to merchant-service (cache hit ratio
approaches 100% after the first call for each merchant). At 500 QPS reads, it eliminates
~500 calls/second for list endpoints.

**Takeaway:** Cache near-static reference data aggressively. If the data changes at CRUD
frequency but is read at transaction frequency, even a 1-minute cache provides a
100x+ reduction in downstream calls.

---

## 54. OpenEntityManagerInView silently holds DB connections for entire HTTP requests

At 100 TPS sustained load, HikariCP showed 80/80 active connections with 30-50 pending,
despite moving all HTTP calls outside `transactionTemplate.execute` blocks. The optimization
appeared to have zero effect.

**Root cause:** Spring Boot's `spring.jpa.open-in-view` defaults to `true`. This keeps the
JPA EntityManager (and its underlying JDBC connection) open for the **entire HTTP request
lifecycle** — from controller entry to response send. Even though our transactions were
short, the connection was held during all downstream RPC calls:

```
OEMIV grabs connection → vaultClient (100-500ms) → tokenClient (100-500ms)
→ transactionTemplate.execute (10ms) → authClient (100-500ms) → connection released
```

Total hold time: ~1-1.5s per request. At 100 TPS: 100 × 1.5s = 150 concurrent connections
needed, but pool max is 80 → 70 pending.

**Fix:** `spring.jpa.open-in-view=false` in all 6 JPA services. Result:

| Metric | OEMIV on | OEMIV off |
|--------|----------|-----------|
| Create P50 | 211ms | **3ms** |
| Create P99 | 417ms | 224ms |
| HikariCP pending | 30-50 | **0** |

Create latency dropped 70x because the create path (merchant validation + DB insert) no
longer holds a connection during the merchant-service HTTP call.

**Safe to disable** when entities have no lazy-loaded relationships (no `@OneToMany`,
`@ManyToOne`, etc.) — which is our case since all entities are standalone.

**Takeaway:** `open-in-view=true` is the silent killer of connection pools in microservices
that make downstream HTTP calls. Disable it in any service that does RPC within
request handling. It's Spring Boot's most dangerous default for microservice architectures.

---

## 55. HTTP calls inside transactions negate transaction brevity

The confirm flow had `authClient.confirm()` (HTTP call to card-auth-service) inside
`transactionTemplate.execute`. The comment even said "outside transaction" but the call
was inside the lambda. This meant:

1. `findByIdForUpdate` acquires a pessimistic row lock
2. PaymentAttempt is saved
3. HTTP call to card-auth-service (100-500ms) — **connection and row lock held**
4. Transaction commits

At 100 TPS, this caused:
- HikariCP pool saturation (80/80 active, 100 pending, 2.1s max acquire wait)
- 8% transaction rollback rate on acquirer_core (lock wait timeouts)
- 60 connections idle-in-transaction in PostgreSQL

**Fix:** Extract HTTP calls to after the transaction:
```kotlin
val txResult = transactionTemplate.execute {
    // DB work only — ~10ms
}
// HTTP call after commit — connection already released
authClient.confirm(...)
```

Same fix applied to `capturePaymentIntent` and `handleWebhook`. Combined with the OEMIV
fix, this reduced connection hold time from ~1.5s to ~10ms per transaction.

**Takeaway:** Never make HTTP calls inside a database transaction. The transaction holds a
connection and potentially a row lock for the entire duration of the RPC call. Move RPC
calls before or after the transaction boundary.
