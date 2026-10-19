-- Additive DDL for a separately authorized rollout. Not applied by the implementation task.
CREATE TABLE IF NOT EXISTS expiry_straddle_sessions (
    id VARCHAR(120) PRIMARY KEY,
    app_user_id BIGINT NOT NULL,
    broker_credentials_id BIGINT NOT NULL,
    symbol VARCHAR(16) NOT NULL,
    trading_day DATE NOT NULL,
    slot VARCHAR(2) NOT NULL,
    exit_at TIMESTAMP NOT NULL,
    strike DOUBLE PRECISION NOT NULL,
    lots INTEGER NOT NULL,
    expected_quantity BIGINT NOT NULL,
    claimed_at TIMESTAMP NOT NULL,
    abort_requested BOOLEAN NOT NULL,
    message VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_expiry_straddle_day ON expiry_straddle_sessions(trading_day);
