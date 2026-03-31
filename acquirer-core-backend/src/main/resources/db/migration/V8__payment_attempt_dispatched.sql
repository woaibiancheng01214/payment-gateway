-- Add dispatched flag for outbox pattern on confirm dispatch to card-auth-service
ALTER TABLE payment_attempts ADD COLUMN dispatched BOOLEAN NOT NULL DEFAULT FALSE;

-- Index for the sweep scheduler: find undispatched PENDING attempts efficiently
CREATE INDEX idx_pa_dispatched_status_created
    ON payment_attempts (dispatched, status, created_at);
