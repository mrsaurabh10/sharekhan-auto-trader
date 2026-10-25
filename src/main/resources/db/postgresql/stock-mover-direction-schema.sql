-- Additive only; apply before starting the new application during an authorized rollout.
-- NULL preserves legacy BUY behavior and the existing BTP short-source fallback.
ALTER TABLE trigger_trade_requests ADD COLUMN IF NOT EXISTS entry_action VARCHAR(4);
ALTER TABLE triggered_trade_setups ADD COLUMN IF NOT EXISTS entry_action VARCHAR(4);
