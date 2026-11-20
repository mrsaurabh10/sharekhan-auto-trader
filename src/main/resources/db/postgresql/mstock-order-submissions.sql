-- Additive schema; ddl-auto=update creates the same table where enabled.
CREATE TABLE IF NOT EXISTS mstock_order_submissions (
    tag VARCHAR(24) PRIMARY KEY, credential_id BIGINT NOT NULL,
    submission_key VARCHAR(100) NOT NULL, generation INTEGER NOT NULL,
    order_id VARCHAR(255), outcome VARCHAR(255), created_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (credential_id, submission_key, generation)
);
