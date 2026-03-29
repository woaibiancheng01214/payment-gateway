-- Composite index for merchant-scoped queries filtered by status
-- Covers: GET /v1/merchants/{id}/payment_intents?status=authorized
CREATE INDEX IF NOT EXISTS idx_pi_merchant_status_created
    ON payment_intents (merchant_id, status, created_at DESC);
