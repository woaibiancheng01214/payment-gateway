CREATE TABLE IF NOT EXISTS payment_methods (
    id              VARCHAR(255) PRIMARY KEY,
    customer_id     VARCHAR(255),
    card_data_id    VARCHAR(255) NOT NULL,
    brand           VARCHAR(50)  NOT NULL,
    last4           VARCHAR(4)   NOT NULL,
    exp_month       INTEGER      NOT NULL,
    exp_year        INTEGER      NOT NULL,
    status          VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
