-- Auto-retry support for dead letter events with exponential backoff
ALTER TABLE dead_letter_events ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE dead_letter_events ADD COLUMN next_retry_at TIMESTAMP WITH TIME ZONE;

-- Index for retry scheduler queries
CREATE INDEX idx_dle_retry ON dead_letter_events (next_retry_at)
    WHERE resolved_at IS NULL AND next_retry_at IS NOT NULL;

-- Backfill: set next_retry_at = created_at + 1 minute for existing unresolved events
UPDATE dead_letter_events
SET next_retry_at = created_at + INTERVAL '1 minute'
WHERE resolved_at IS NULL;
