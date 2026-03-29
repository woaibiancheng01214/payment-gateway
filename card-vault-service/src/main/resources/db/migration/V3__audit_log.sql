-- PCI DSS 10.2: Audit log for all cardholder data access
CREATE TABLE IF NOT EXISTS audit_log (
    id             VARCHAR(255) PRIMARY KEY,
    action         VARCHAR(50)  NOT NULL,
    card_data_id   VARCHAR(255) NOT NULL,
    caller_service VARCHAR(100),
    caller_ip      VARCHAR(45),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_audit_card_data ON audit_log (card_data_id, created_at DESC);
CREATE INDEX idx_audit_created ON audit_log (created_at);
