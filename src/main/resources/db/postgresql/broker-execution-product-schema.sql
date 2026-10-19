-- Additive only. Apply during an authorized rollout; do not backfill legacy rows.
-- NULL retains the pre-policy broker product when closing existing positions.
ALTER TABLE triggered_trade_setups
    ADD COLUMN IF NOT EXISTS broker_execution_product VARCHAR(32);
