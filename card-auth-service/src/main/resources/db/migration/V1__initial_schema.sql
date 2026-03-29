CREATE TABLE IF NOT EXISTS internal_attempts (
    id                 VARCHAR(255) PRIMARY KEY,
    payment_attempt_id VARCHAR(255) NOT NULL,
    provider           VARCHAR(50)  NOT NULL,
    status             VARCHAR(50)  NOT NULL,
    type               VARCHAR(50)  NOT NULL,
    request_payload    TEXT,
    response_payload   TEXT,
    retry_count        INTEGER      NOT NULL DEFAULT 0,
    dispatched         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ia_attempt_id ON internal_attempts (payment_attempt_id);
CREATE INDEX IF NOT EXISTS idx_ia_dispatched_status_created ON internal_attempts (dispatched, status, created_at);
CREATE INDEX IF NOT EXISTS idx_ia_type_status_created ON internal_attempts (type, status, created_at);
