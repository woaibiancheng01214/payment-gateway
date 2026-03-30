# Testing & Load

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
