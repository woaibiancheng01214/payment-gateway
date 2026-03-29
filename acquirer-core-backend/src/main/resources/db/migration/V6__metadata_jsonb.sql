-- Convert metadata from TEXT to JSONB for queryability and indexing
ALTER TABLE payment_intents ALTER COLUMN metadata TYPE JSONB USING metadata::jsonb;
