# Product Improvements

## Critical (Core Payment Lifecycle Gaps)

### 1. No refunds or voids

The system has no way to reverse a payment. This is the most critical missing feature for any payment gateway.

**What's needed:**
- **Full refund** — reverse a captured payment entirely
- **Partial refund** — return a portion of the captured amount (e.g., one item returned)
- **Void / cancel** — release an authorization hold without capturing (e.g., customer cancels before shipment)

**State machine additions:**
```
CAPTURED → REFUNDED          (full refund)
CAPTURED → PARTIALLY_REFUNDED (partial refund, still capturable remainder)
AUTHORIZED → CANCELLED       (void / release hold)
```

**Ledger impact:**
- Refunds require reversal entries (debit `merchant_revenue`, credit `gateway_payable`)
- Voids require releasing the authorization hold entries

**Why it matters:** Every payment processor supports refunds. Without them, merchants cannot handle returns, disputes, or customer service scenarios. This is table-stakes functionality.

**Reference:** [Stripe Refunds API](https://docs.stripe.com/api/refunds) — supports full/partial refunds, reason codes, and metadata.

---

### 2. No partial capture

The capture endpoint always settles the full authorized amount. Many real-world scenarios require capturing less than authorized:
- Items out of stock at fulfillment time
- Split shipments (capture per shipment)
- Tip adjustments in hospitality
- Discounts applied after authorization

**What's needed:**
- Accept `amount_to_capture` on the `/capture` endpoint (default: full amount)
- Validate: `0 < amount_to_capture <= authorized_amount`
- Track `amount_captured` vs `amount_authorized` on the PaymentIntent
- Pass the capture amount to the external gateway

**Reference:** Stripe supports `amount_to_capture` on `POST /v1/payment_intents/:id/capture`.

---

### 3. No 3D Secure / Strong Customer Authentication (SCA)

For European payments under PSD2, Strong Customer Authentication is **legally mandatory**. The current state machine has no `REQUIRES_ACTION` state for customer authentication challenges.

**What's needed:**
- New state: `REQUIRES_ACTION` (customer must complete 3DS challenge)
- Flow: `REQUIRES_CONFIRMATION` → `REQUIRES_ACTION` → `AUTHORIZED` (or `FAILED`)
- Client-side redirect/popup for 3DS challenge
- Handle frictionless vs. challenge flows
- SCA exemptions for low-value, recurring, and low-risk transactions
- Soft decline handling (issuer requests SCA after initial authorization attempt)

**Liability shift:** Successful 3DS authentication shifts fraud chargeback liability from merchant to issuer — a significant financial benefit.

**Reference:** [Stripe 3D Secure Documentation](https://docs.stripe.com/payments/3d-secure), [Judopay 3DS & Chargebacks Guide](https://www.judopay.com/guides/definitive-guide-3d-secure-chargebacks-liability)

---

## High Priority (Production Readiness)

### 4. No dispute / chargeback handling

There's no mechanism for:
- Receiving chargeback notifications from card networks
- Tracking dispute lifecycle (evidence requested → evidence submitted → won/lost)
- Adjusting ledger entries for chargebacks (debit merchant, credit cardholder)
- Uploading evidence for dispute responses (receipts, shipping proof, etc.)
- Chargeback fee tracking

**What's needed:**
- `Dispute` entity with status machine: `NEEDS_RESPONSE` → `UNDER_REVIEW` → `WON` / `LOST`
- Webhook events for dispute state changes
- Evidence upload API
- Ledger entries for chargebacks and reversals

**Why it matters:** Chargebacks are inevitable in payment processing. Without dispute management, merchants have no way to respond to or track disputes, leading to automatic losses.

**Reference:** [Evervault: Mastering 3D-Secure for Minimizing Chargebacks](https://evervault.com/blog/mastering-3d-secure-minimizing-chargebacks-and-fraud-rates-for-payment-gateways-psps-and-payfacs)

---

### 5. No merchant model (multi-tenancy) — PARTIALLY IMPLEMENTED

~~Everything operates as a single-tenant system.~~ Basic multi-tenancy is now in place.

**Implemented:**
- `merchant-service` microservice (port 8087) with Merchant CRUD (create, get, list)
- All PaymentIntents scoped to a merchant via required `merchantId` field
- Internal validation: acquirer-core validates merchant exists before creating intents
- `GET /v1/payment_intents/merchant/{merchantId}` endpoint for per-merchant intent listing
- Stress test updated: 100 merchants with distributed payment intents across workers

**Still needed:**
- Per-merchant API key authentication (publishable + secret keys)
- Per-merchant webhook delivery
- Per-merchant reporting and dashboards
- Per-merchant fee configuration (MDR, processing fees)
- Merchant onboarding flow (KYC/KYB)

**Why it matters:** Without multi-tenancy, the system is a payment processor for a single business, not a payment gateway platform.

---

### 6. No outbound webhook delivery to merchants

The system receives webhooks from the external gateway but doesn't send event notifications to merchants. Merchants need to know when payments succeed, fail, or are disputed.

**What's needed:**
- Event types: `payment_intent.succeeded`, `payment_intent.payment_failed`, `payment_intent.captured`, `payment_intent.refunded`, `dispute.created`, etc.
- Webhook delivery with retry (exponential backoff, up to 3-5 days)
- Webhook signature verification (so merchants can verify authenticity)
- Webhook endpoint management API (CRUD for merchant webhook URLs)
- Event log / delivery history
- Manual retry from dashboard

**Reference:** Stripe sends webhooks for every state change and retries for up to 3 days with exponential backoff.

---

### 7. No payment retry flow

Once a payment fails (`FAILED` state), the intent is terminal. There's no ability to retry with a different card or payment method.

**What's needed:**
- Allow `FAILED` intents to return to `REQUIRES_PAYMENT_METHOD` (or a new `REQUIRES_CONFIRMATION` with a new attempt)
- Support multiple PaymentAttempts per intent (the data model already allows this, but the state machine doesn't)
- Configurable retry limits

**Why it matters:** Card declines happen for many temporary reasons (insufficient funds, wrong CVC). Allowing retries significantly improves payment success rates. Stripe's flow explicitly supports this: `REQUIRES_PAYMENT_METHOD` → attach new method → `REQUIRES_CONFIRMATION` → retry.

---

## Medium Priority (Competitive Features)

### 8. No reconciliation or settlement

There's no:
- Settlement batching (grouping captured payments for merchant payout)
- Settlement reports or files
- Reconciliation between payment records and ledger entries
- Fee calculation (MDR, interchange, processing fees)
- Net settlement amount computation

**What's needed:**
- `Settlement` entity tracking batched payouts
- Daily/weekly settlement runs
- Settlement file generation (CSV/PDF)
- Fee deduction logic
- Reconciliation reports comparing transactions, ledger, and bank statements

**Why it matters:** Settlement is how merchants actually receive their money. Without it, the gateway processes payments but has no payout mechanism.

**Reference:** [Payment Gateway Reconciliation Explained](https://optimus.tech/knowledge-base/payment-gateway-reconciliation-explained-or-optimus), [How Payment Gateways Settle Transactions](https://toucanus.com/blogs/how-payment-gateways-settle-online-transactions/)

---

### 9. No payment methods beyond cards

The system only handles card payments (Visa, Mastercard, Amex, Discover).

**What's needed (prioritized by market):**
- **Bank transfers** — ACH (US), SEPA (EU), Faster Payments (UK)
- **Digital wallets** — Apple Pay, Google Pay (high conversion rates)
- **Buy Now Pay Later** — Klarna, Afterpay, Affirm (growing rapidly)
- **Local payment methods** — iDEAL (NL), Bancontact (BE), PIX (BR), UPI (IN)

**Why it matters:** Card-only gateways lose merchants who need alternative payment methods. Digital wallets alone can increase checkout conversion by 10-20%.

---

### 10. No multi-currency / FX support

`currency` is stored on PaymentIntents but there's no:
- Exchange rate management
- Settlement currency concept (merchant settles in USD, customer pays in EUR)
- Multi-currency pricing
- Currency conversion fee handling
- The ledger mixes all currencies in the same accounts without separation

**What's needed:**
- FX rate service integration
- `presentment_currency` vs `settlement_currency` on PaymentIntent
- Per-currency ledger accounts (or currency-tagged entries)
- FX markup/fee configuration

---

### 11. No fraud detection

There's no fraud scoring, velocity checks, or risk assessment.

**What's needed:**
- Velocity checks (too many attempts from same IP/card/email)
- Amount anomaly detection
- Device fingerprinting
- Address Verification Service (AVS) checks
- CVV/CVC verification results tracking
- Risk score on each PaymentIntent
- Configurable rules engine (block transactions from high-risk countries, etc.)

**Why it matters:** Fraud prevention directly reduces chargebacks and financial losses. It's a core differentiator for payment gateways.

---

### 12. No audit trail / event sourcing

There's no immutable event log beyond mutable database state. The current system only stores the latest state of each entity.

**What's needed:**
- Immutable event log for every state change (who, what, when, why)
- Event types: `payment_intent.created`, `payment_intent.confirmed`, `webhook.received`, etc.
- Queryable event history per PaymentIntent
- Tamper-evident log (append-only, hashed chain)

**Why it matters:**
- **Regulatory compliance** — PCI DSS and financial regulations require audit trails
- **Debugging** — "why did this payment fail?" requires seeing the full history
- **Dispute evidence** — timestamped event logs are used in chargeback responses
- **Support** — customer service needs to trace what happened

---

### 13. No amount/currency in capture gateway call

**File:** `acquirer-core-backend/.../service/GatewayClient.kt:47-54`

The capture request to the external gateway sends only `internalAttemptId` and `callbackUrl` — no amount or currency. Real capture requests must include:
- The capture amount (especially for partial captures)
- The currency
- Reference to the original authorization

---

## Future Considerations

### 14. Subscription / recurring payments
- Scheduled billing (daily, weekly, monthly, yearly)
- Usage-based billing
- Stored credential transactions (CIT/MIT framework for SCA exemptions)
- Dunning management (retry logic for failed subscription payments)

### 15. Merchant dashboard / analytics
- Real-time transaction monitoring
- Payment success/failure rate charts
- Revenue analytics by currency, card brand, country
- Dispute rate tracking
- API usage and error rate monitoring

### 16. Payouts to merchants
- Automated bank transfer payouts on settlement schedule
- Payout status tracking
- Minimum payout thresholds
- Payout hold/release for risk management

---

## Priority Matrix

| Priority | Feature | Business Impact |
|----------|---------|----------------|
| **P0** | Refunds & voids | Cannot operate without reversal capability |
| **P0** | Partial capture | Required for e-commerce fulfillment workflows |
| **P0** | 3D Secure / SCA | Legally required for EU payments |
| **P1** | Dispute/chargeback handling | Financial risk without it |
| **P1** | Merchant model (multi-tenancy) | Required to serve more than one business |
| **P1** | Outbound webhooks to merchants | Merchants need event notifications |
| **P1** | Payment retry flow | Significantly improves payment success rate |
| **P2** | Reconciliation & settlement | Required for merchant payouts |
| **P2** | Additional payment methods | Market expansion |
| **P2** | Multi-currency / FX | International commerce |
| **P2** | Fraud detection | Reduces chargebacks and losses |
| **P3** | Audit trail / event log | Compliance and operational support |
| **P3** | Subscriptions / recurring | New revenue model |
| **P3** | Merchant dashboard | Self-service operations |

---

## References

- [Stripe Payment Intents API](https://docs.stripe.com/payments/payment-intents)
- [Stripe 3D Secure Authentication](https://docs.stripe.com/payments/3d-secure)
- [Stripe Idempotent Requests](https://docs.stripe.com/api/idempotent_requests)
- [Payment Gateway Architecture Guide](https://www.unipaas.com/blog/payment-gateway-architecture)
- [Payment Gateway Reconciliation](https://optimus.tech/knowledge-base/payment-gateway-reconciliation-explained-or-optimus)
- [How Payment Gateways Settle Transactions](https://toucanus.com/blogs/how-payment-gateways-settle-online-transactions/)
- [Mastering 3D-Secure for Payment Gateways](https://evervault.com/blog/mastering-3d-secure-minimizing-chargebacks-and-fraud-rates-for-payment-gateways-psps-and-payfacs)
- [3D Secure, Chargebacks & Liability Guide](https://www.judopay.com/guides/definitive-guide-3d-secure-chargebacks-liability)
- [Payment System Architecture Manual](https://devoxsoftware.com/blog/the-2022-manual-to-payment-system-architecture/)
- [Design a Payment System](https://www.systemdesignhandbook.com/guides/design-a-payment-system/)
