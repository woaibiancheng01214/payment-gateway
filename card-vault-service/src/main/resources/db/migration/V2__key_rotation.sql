-- Support encryption key rotation: track which key version encrypted each row
ALTER TABLE card_data ADD COLUMN key_version INTEGER NOT NULL DEFAULT 1;
CREATE INDEX idx_cd_key_version ON card_data (key_version);
