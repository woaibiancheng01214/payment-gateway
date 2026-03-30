# CDC & Event-Driven Architecture

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

## 49. Kafka partitions unlock parallel CDC consumption

The Debezium CDC topic (`acquirer-core.public.payment_intents`) was created with a single
partition by default. The ledger-service consumer processed events single-threaded. At
300+ TPS, CDC event processing becomes the bottleneck — a single consumer can't keep up
with the write rate.

**Fix:**
1. Debezium connector config: `topic.creation.default.partitions: 8`
2. Ledger service: `spring.kafka.listener.concurrency=4`

Kafka guarantees ordering within a partition. Since Debezium partitions by primary key
(`payment_intent_id`), all events for the same intent hash to the same partition. This
preserves per-intent ordering (critical for AUTHORIZED→CAPTURED→FAILED sequences) while
enabling 4x parallel throughput.

**Takeaway:** Single-partition Kafka topics are a hidden bottleneck. Set partitions at
topic creation time based on expected throughput. The dedup unique constraint in the
ledger DB provides safety against any out-of-order edge cases.

---

## 55. Dead letter events need automatic retry — manual retry doesn't scale

Dead letter events were persisted but only retryable via a manual `POST /retry/{id}` API.
At 300+ TPS, transient CDC failures (connection timeout, temporary deadlock) produce 30+
dead letters per day. Manual retry via API calls is not operationally sustainable.

**Fix:** Added `DeadLetterRetryScheduler` with exponential backoff:

| Retry | Delay |
|-------|-------|
| 1 | 1 minute |
| 2 | 5 minutes |
| 3 | 30 minutes |
| 4 | 2 hours |
| 5 | 12 hours |

After 5 retries, `next_retry_at` is set to null (stop retrying). The scheduler processes
max 5 events per sweep to avoid thundering herd on reconnection after an outage.

Migration adds `retry_count` and `next_retry_at` columns with an index for the scheduler
query.

**Takeaway:** Any event-driven system with a dead letter queue needs auto-retry with
backoff. Manual retry APIs are for exceptional cases, not for transient failures. The
backoff curve should match the expected recovery time of the most common failure modes.
