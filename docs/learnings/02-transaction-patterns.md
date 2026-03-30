# Transaction Patterns

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
