-- H2 schema update for durable entry outcome diagnostics.
-- Run once against jdbc:h2:file:./data/scriptdbusertest (user sa) before
-- deploying code that writes TriggerTradeRequestEntity.reason/comment.
ALTER TABLE trigger_trade_requests ADD COLUMN IF NOT EXISTS reason VARCHAR(255);
ALTER TABLE trigger_trade_requests ADD COLUMN IF NOT EXISTS comment VARCHAR(2000);

ALTER TABLE triggered_trade_setups ADD COLUMN IF NOT EXISTS reason VARCHAR(255);
ALTER TABLE triggered_trade_setups ADD COLUMN IF NOT EXISTS comment VARCHAR(2000);
