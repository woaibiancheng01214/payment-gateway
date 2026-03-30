# Security & Secrets

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

## 47. Encryption keys must not live in source code or environment variables

The AES-256-GCM encryption key for the card vault was stored in two places:
1. `application.properties`: `vault.encryption.key=0123456789abcdef...`
2. `docker-compose.yml`: `VAULT_ENCRYPTION_KEY: 0123456789abcdef...`

Both are committed to the repository. Anyone with repo access can decrypt all stored PANs.
Docker `inspect` on the running container also reveals the environment variable.

The Docker secrets infrastructure (`infra/secrets/vault_encryption_key.txt`) already existed
but wasn't consumed — services used the environment variable instead.

**Fix:**
1. `EncryptionService` updated to support `vault.encryption.key-file` property (Docker
   secrets path). File-based key takes priority over inline property.
2. Docker-compose updated: `VAULT_ENCRYPTION_KEY_FILE: /run/secrets/vault_encryption_key`
   with `secrets: [vault_encryption_key]`.
3. Inline key kept in `application.properties` as local dev fallback only.

**Takeaway:** Secrets in environment variables are visible to `ps`, `docker inspect`,
and crash dumps. File-based secrets (Docker secrets, Kubernetes secrets, Vault) are the
minimum acceptable pattern. The inline dev fallback is acceptable for local development
but must never be the production mechanism.

---

## 50. Encryption key rotation requires versioned ciphertext

The original `EncryptionService` loaded a single key and used it forever. If the key was
compromised, replacing it would make all existing encrypted PANs unreadable.

**Fix:**
1. Added `key_version` column to `card_data` (Flyway migration)
2. `EncryptionService` maintains a map of version → `SecretKeySpec`
3. New encryptions always use `currentVersion` (configurable)
4. Decryptions read the version from the DB row and use the corresponding key
5. Previous key loaded from `vault.encryption.previous-key` property

During rotation:
- Deploy with `current-version=2` and both keys configured
- All new encryptions use version 2
- Old rows still decrypt with version 1
- Background re-encryption job can gradually migrate old rows to version 2
- Once all rows are version 2, remove the previous key

**Takeaway:** Key rotation is not just "change the key" — it's a data migration. Version
the ciphertext at rest so old and new data can coexist during the transition.

---

## 51. PCI audit logging — every cardholder data access must leave a trail

PCI DSS Requirement 10.2 mandates logging all access to cardholder data. The vault service
decrypted PANs without any record of who requested it, when, or why. In a breach
investigation, there would be no way to determine which PANs were accessed.

**Fix:** Added an `audit_log` table to the `card_vault` database:
```sql
(id, action, card_data_id, caller_service, caller_ip, created_at)
```

Every `VaultService.getCardData()` call writes an audit record before returning the
decrypted PAN. The audit write is wrapped in try/catch — a failed audit log should not
block the payment flow (availability over auditability in real-time).

The `caller_service` field captures the `X-Correlation-Id` header (from Learning 44),
enabling correlation between the audit log and the payment flow that triggered the access.

**Takeaway:** Audit logging for sensitive data access is not optional in regulated systems.
Keep the log writes non-blocking (fire-and-forget) so they don't add latency to the
payment path.

---

## 54. Webhook secret rotation requires dual-secret verification

The HMAC webhook secret was a static string. Rotating it required simultaneously updating
the gateway (signer) and the orchestrator (verifier) with zero downtime — a coordination
problem with no safe window.

**Fix:** `WebhookSignatureFilter` now accepts `gateway.webhook.secret.previous` and tries
verification against both secrets. During rotation:
1. Deploy orchestrator with both current and previous secrets
2. Update the gateway to sign with the new secret
3. Orchestrator verifies against new secret first, falls back to old
4. After all gateways are updated, remove the previous secret

The filter logs a warning when verification succeeds with the previous secret, indicating
rotation is still in progress.

**Takeaway:** Any HMAC-verified channel needs dual-key support for zero-downtime rotation.
The verifier must accept signatures from both old and new keys during the transition
period, with the signer switching atomically.
