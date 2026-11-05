-- Additive, explicit deployment migration. No changes to historical trades or configured exits.
CREATE TABLE IF NOT EXISTS ngas_scalp_profit_lock_state (
 trade_id bigint PRIMARY KEY,
 scrip_code integer NOT NULL,
 app_user_id bigint NOT NULL,
 broker_credentials_id bigint NOT NULL,
 policy_version varchar(32) NOT NULL,
 entry_at timestamp NOT NULL,
 initial_premium double precision NOT NULL,
 peak_bid double precision NOT NULL,
 protection_floor double precision NOT NULL,
 protection_armed boolean NOT NULL,
 last_evaluated_at timestamp NOT NULL,
 last_message varchar(80) NOT NULL
);
CREATE TABLE IF NOT EXISTS ngas_scalp_profit_lock_event (
 id bigserial PRIMARY KEY,
 trade_id bigint NOT NULL,
 observed_at timestamp NOT NULL,
 event_type varchar(32) NOT NULL,
 bid double precision NOT NULL,
 peak_bid double precision NOT NULL,
 protection_floor double precision NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ngas_scalp_lock_event_trade ON ngas_scalp_profit_lock_event(trade_id,observed_at);
