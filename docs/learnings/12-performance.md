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
