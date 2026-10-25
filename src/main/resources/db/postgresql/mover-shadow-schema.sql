-- Additive, isolated research data. No foreign keys to mutable trading state.
CREATE TABLE IF NOT EXISTS mover_shadow_events (
 id varchar(64) PRIMARY KEY, trading_day date NOT NULL, event_key varchar(200) NOT NULL,
 event_type varchar(24) NOT NULL, app_user_id bigint, observed_at timestamp with time zone NOT NULL,
 payload text NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_mover_shadow_day_type ON mover_shadow_events(trading_day,event_type,observed_at);
CREATE INDEX IF NOT EXISTS idx_mover_shadow_user_day ON mover_shadow_events(app_user_id,trading_day);
CREATE INDEX IF NOT EXISTS idx_mover_shadow_key_time ON mover_shadow_events(event_key,observed_at);
CREATE TABLE IF NOT EXISTS mover_shadow_reports (
 trading_day date NOT NULL, app_user_id bigint NOT NULL, payload text NOT NULL,
 PRIMARY KEY(trading_day,app_user_id)
);
