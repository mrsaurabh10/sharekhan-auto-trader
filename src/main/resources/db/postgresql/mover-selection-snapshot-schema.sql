-- Additive schema for the PRIMARY JPA datasource; not a mirror/backfill table.
-- Times are market-local Asia/Kolkata timestamps, matching the strategy clock.
-- Existing ddl-auto=update installations create this JPA table automatically.
CREATE TABLE IF NOT EXISTS mover_selection_snapshot (
    id VARCHAR(100) PRIMARY KEY,
    template_id VARCHAR(80) NOT NULL,
    trading_day DATE NOT NULL,
    capture_started_at TIMESTAMP NOT NULL,
    captured_at TIMESTAMP NOT NULL,
    schema_version INTEGER NOT NULL,
    candidates_json TEXT NOT NULL
);
