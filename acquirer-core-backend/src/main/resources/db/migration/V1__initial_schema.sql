-- Payment Intents
CREATE TABLE IF NOT EXISTS payment_intents (
    id              VARCHAR(255) PRIMARY KEY,
    amount          BIGINT       NOT NULL,
    currency        VARCHAR(10)  NOT NULL,
    description     TEXT,
    statement_descriptor VARCHAR(22),
    metadata        TEXT,
    customer_email  VARCHAR(255),
    customer_id     VARCHAR(255),
    status          VARCHAR(50)  NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pi_status_updated ON payment_intents (status, updated_at);

-- Payment Attempts
CREATE TABLE IF NOT EXISTS payment_attempts (
    id                VARCHAR(255) PRIMARY KEY,
    payment_intent_id VARCHAR(255) NOT NULL,
    payment_method    VARCHAR(255) NOT NULL,
    card_brand        VARCHAR(50),
    last4             VARCHAR(4),
    status            VARCHAR(50)  NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pa_intent_id ON payment_attempts (payment_intent_id);
CREATE INDEX IF NOT EXISTS idx_pa_intent_id_created ON payment_attempts (payment_intent_id, created_at);

-- Internal Attempts
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

-- Idempotency Keys
CREATE TABLE IF NOT EXISTS idempotency_keys (
    key          VARCHAR(255) PRIMARY KEY,
    request_hash VARCHAR(255) NOT NULL,
    response     TEXT         NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
