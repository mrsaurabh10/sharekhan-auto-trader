-- Additive schema for the exit safety release. Review during an authorized rollout.
-- ddl-auto=update installations create the same columns/tables from the JPA model.
-- Existing journals stay intact; a null legacy generation means generation zero.
ALTER TABLE triggered_trade_setups ADD COLUMN IF NOT EXISTS broker_security_id VARCHAR(255);
ALTER TABLE dhan_order_submissions ADD COLUMN IF NOT EXISTS generation INTEGER;
ALTER TABLE dhan_order_submissions ADD COLUMN IF NOT EXISTS outcome VARCHAR(255);
CREATE TABLE IF NOT EXISTS broker_exit_submissions (
    id VARCHAR(255) PRIMARY KEY, trade_id BIGINT, credential_id BIGINT,
    generation INTEGER NOT NULL, order_id VARCHAR(255), confirmed_inactive BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE IF NOT EXISTS exit_chase_control (
    trade_id BIGINT PRIMARY KEY, order_id VARCHAR(255), intent VARCHAR(255),
    started_at TIMESTAMP WITH TIME ZONE, active BOOLEAN NOT NULL,
    attention VARCHAR(2000), notification_attempts INTEGER NOT NULL,
    next_notification_at TIMESTAMP WITH TIME ZONE, notification_delivered BOOLEAN NOT NULL,
    version BIGINT
);
