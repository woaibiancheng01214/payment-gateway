CREATE TABLE IF NOT EXISTS card_data (
    id              VARCHAR(255) PRIMARY KEY,
    encrypted_pan   TEXT         NOT NULL,
    exp_month       INTEGER      NOT NULL,
    exp_year        INTEGER      NOT NULL,
    cardholder_name VARCHAR(255),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
