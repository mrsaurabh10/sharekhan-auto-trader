-- Reviewed additive rollout DDL. Apply after expiry-straddle and profit-target DDL.
-- No source-label inference or history backfill. Legacy rows retain NULL run IDs.
CREATE TABLE IF NOT EXISTS strategy_runs (
 id VARCHAR(64) PRIMARY KEY, app_user_id BIGINT NOT NULL, broker_credentials_id BIGINT NOT NULL,
 subscription_id BIGINT, template_id VARCHAR(100) NOT NULL, symbol VARCHAR(255) NOT NULL,
 trading_day DATE NOT NULL, instance VARCHAR(16) NOT NULL, lots INTEGER,
 risk_amount_mode VARCHAR(16), risk_profit_target NUMERIC(19,2), risk_max_loss NUMERIC(19,2),
 risk_trail_activation NUMERIC(19,2), risk_trail_giveback NUMERIC(19,2),
 risk_sl_step_profit NUMERIC(19,2), risk_sl_step_raise NUMERIC(19,2),
 status VARCHAR(16) NOT NULL, created_at TIMESTAMP NOT NULL, close_requested_at TIMESTAMP, closed_at TIMESTAMP,
 exit_reason VARCHAR(80), peak_pnl NUMERIC(19,2), stop_floor NUMERIC(19,2), message VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_strategy_run_owner_time ON strategy_runs(app_user_id,created_at);
CREATE INDEX IF NOT EXISTS idx_strategy_run_status ON strategy_runs(status);
ALTER TABLE strategy_subscriptions ADD COLUMN IF NOT EXISTS risk_amount_mode VARCHAR(16);
ALTER TABLE strategy_subscriptions ADD COLUMN IF NOT EXISTS risk_profit_target NUMERIC(19,2);
ALTER TABLE strategy_subscriptions ADD COLUMN IF NOT EXISTS risk_max_loss NUMERIC(19,2);
ALTER TABLE strategy_subscriptions ADD COLUMN IF NOT EXISTS risk_trail_activation NUMERIC(19,2);
ALTER TABLE strategy_subscriptions ADD COLUMN IF NOT EXISTS risk_trail_giveback NUMERIC(19,2);
ALTER TABLE strategy_subscriptions ADD COLUMN IF NOT EXISTS risk_sl_step_profit NUMERIC(19,2);
ALTER TABLE strategy_subscriptions ADD COLUMN IF NOT EXISTS risk_sl_step_raise NUMERIC(19,2);
ALTER TABLE trigger_trade_requests ADD COLUMN IF NOT EXISTS strategy_run_id VARCHAR(64);
ALTER TABLE triggered_trade_setups ADD COLUMN IF NOT EXISTS strategy_run_id VARCHAR(64);
ALTER TABLE trade_audit_events ADD COLUMN IF NOT EXISTS strategy_run_id VARCHAR(64);
ALTER TABLE expiry_straddle_sessions ADD COLUMN IF NOT EXISTS strategy_run_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_trade_request_run ON trigger_trade_requests(strategy_run_id);
CREATE INDEX IF NOT EXISTS idx_trade_setup_run ON triggered_trade_setups(strategy_run_id);
