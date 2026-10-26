-- Durable observations for the explicitly enabled FNO mover exit policy.
-- Additive only: no changes to orders, fills, subscriptions, or historical P&L.
CREATE TABLE IF NOT EXISTS fno_mover_exit_state (
 trade_id bigint PRIMARY KEY,
 policy_version varchar(32) NOT NULL,
 entry_at timestamp NOT NULL,
 spot_entry double precision NOT NULL,
 initial_spot_stop double precision NOT NULL,
 initial_premium double precision NOT NULL,
 peak_bid double precision NOT NULL,
 reached_half_risk boolean NOT NULL,
 progress_coverage_valid boolean NOT NULL,
 last_spot_at timestamp,
 last_evaluated_at timestamp NOT NULL,
 protection_armed boolean NOT NULL,
 protection_floor double precision NOT NULL,
 last_message varchar(180) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_fno_mover_exit_entry ON fno_mover_exit_state(entry_at);
