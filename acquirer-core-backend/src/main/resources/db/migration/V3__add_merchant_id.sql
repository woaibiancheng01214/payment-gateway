ALTER TABLE payment_intents ADD COLUMN merchant_id VARCHAR(255) NOT NULL DEFAULT '';

CREATE INDEX idx_pi_merchant_id ON payment_intents (merchant_id);
