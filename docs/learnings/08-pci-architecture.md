# PCI Architecture

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
