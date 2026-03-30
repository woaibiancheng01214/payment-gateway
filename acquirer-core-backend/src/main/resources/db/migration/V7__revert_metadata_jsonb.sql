-- Revert JSONB back to TEXT: Hibernate's default varchar binding doesn't support
-- JSONB without a custom AttributeConverter. TEXT works correctly and metadata
-- is serialized/deserialized in application code via Jackson.
ALTER TABLE payment_intents ALTER COLUMN metadata TYPE TEXT;
