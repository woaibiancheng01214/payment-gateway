CREATE TABLE IF NOT EXISTS dead_letter_events (
    id            VARCHAR(255) PRIMARY KEY,
    topic         VARCHAR(255) NOT NULL,
    partition_num INTEGER      NOT NULL DEFAULT 0,
    offset_num    BIGINT       NOT NULL DEFAULT 0,
    key           TEXT,
    payload       TEXT         NOT NULL,
    error_message TEXT         NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at   TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_dle_unresolved ON dead_letter_events (resolved_at) WHERE resolved_at IS NULL;
