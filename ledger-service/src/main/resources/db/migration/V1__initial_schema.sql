-- Ledger Accounts
CREATE TABLE IF NOT EXISTS ledger_accounts (
    id         VARCHAR(255) PRIMARY KEY,
    name       VARCHAR(255) NOT NULL UNIQUE,
    type       VARCHAR(50)  NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Ledger Entries
CREATE TABLE IF NOT EXISTS ledger_entries (
    id                 VARCHAR(255) PRIMARY KEY,
    ledger_account_id  VARCHAR(255) NOT NULL,
    payment_intent_id  VARCHAR(255) NOT NULL,
    entry_type         VARCHAR(50)  NOT NULL,
    amount             BIGINT       NOT NULL,
    currency           VARCHAR(10)  NOT NULL,
    description        TEXT         NOT NULL,
    event_type         VARCHAR(100) NOT NULL,
    event_timestamp    TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL
);

ALTER TABLE ledger_entries
    ADD CONSTRAINT uq_ledger_entry_dedup
    UNIQUE (payment_intent_id, event_type, entry_type, ledger_account_id);
