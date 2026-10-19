-- Additive DDL for an explicitly authorized rollout, after expiry-straddle-schema.sql.
-- Legacy NULL target keeps scheduled exits only. No active subscription is opted in.
ALTER TABLE strategy_subscriptions ADD COLUMN IF NOT EXISTS profit_target_per_lot NUMERIC(19,2);
ALTER TABLE expiry_straddle_sessions ADD COLUMN IF NOT EXISTS profit_target_per_lot NUMERIC(19,2);
ALTER TABLE expiry_straddle_sessions ADD COLUMN IF NOT EXISTS profit_exit_requested BOOLEAN NOT NULL DEFAULT FALSE;
