-- Additive primary-datasource schema. Review/apply only during an authorized rollout.
-- Keep this journal in backups with the corresponding requests/trades.
CREATE TABLE IF NOT EXISTS dhan_order_submissions (
    correlation_id VARCHAR(30) PRIMARY KEY,
    credential_id BIGINT NOT NULL,
    submission_key VARCHAR(80) NOT NULL,
    order_side VARCHAR(4) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
