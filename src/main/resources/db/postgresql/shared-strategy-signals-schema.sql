CREATE TABLE IF NOT EXISTS shared_straddle_signals (
 id varchar(64) PRIMARY KEY,
 trading_day date NOT NULL, symbol varchar(16) NOT NULL, slot varchar(2) NOT NULL,
 expiry varchar(16) NOT NULL, strike double precision NOT NULL,
 ce_scrip_code integer NOT NULL, pe_scrip_code integer NOT NULL, lot_size integer NOT NULL,
 spot_price double precision NOT NULL, spot_observed_at timestamp NOT NULL, created_at timestamp NOT NULL,
 CONSTRAINT uq_shared_straddle_session UNIQUE (trading_day,symbol,slot)
);
ALTER TABLE expiry_straddle_sessions ADD COLUMN IF NOT EXISTS shared_signal_id varchar(64);
