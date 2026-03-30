# Domain Modeling

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
