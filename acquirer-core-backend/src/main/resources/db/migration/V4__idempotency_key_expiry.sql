-- Add expiry column for automated cleanup of stale idempotency keys
ALTER TABLE idempotency_keys ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE;

-- Backfill existing rows: expire 48h after creation
UPDATE idempotency_keys SET expires_at = created_at + INTERVAL '48 hours';

-- Make non-null going forward
ALTER TABLE idempotency_keys ALTER COLUMN expires_at SET NOT NULL;
ALTER TABLE idempotency_keys ALTER COLUMN expires_at SET DEFAULT now() + INTERVAL '48 hours';

-- Index for cleanup scheduler queries
CREATE INDEX idx_ik_expires_at ON idempotency_keys (expires_at);
