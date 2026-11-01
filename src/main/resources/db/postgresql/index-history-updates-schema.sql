CREATE TABLE IF NOT EXISTS market_history_candles (
 id VARCHAR(80) PRIMARY KEY,symbol VARCHAR(16) NOT NULL,candle_time TIMESTAMP NOT NULL,
 interval_minutes INTEGER NOT NULL DEFAULT 5,open_price DOUBLE PRECISION NOT NULL,
 high_price DOUBLE PRECISION NOT NULL,low_price DOUBLE PRECISION NOT NULL,close_price DOUBLE PRECISION NOT NULL,
 volume BIGINT,provider VARCHAR(32),captured_at TIMESTAMP,
 CONSTRAINT uk_market_history_symbol_interval_time UNIQUE(symbol,interval_minutes,candle_time)
);
ALTER TABLE market_history_candles ADD COLUMN IF NOT EXISTS source_segment VARCHAR(16);
ALTER TABLE market_history_candles ADD COLUMN IF NOT EXISTS source_security_id VARCHAR(24);
ALTER TABLE market_history_candles ADD COLUMN IF NOT EXISTS source_epoch BIGINT;
ALTER TABLE market_history_candles ADD COLUMN IF NOT EXISTS source_interval_minutes INTEGER;
ALTER TABLE market_history_candles ADD COLUMN IF NOT EXISTS source_bar_count INTEGER;
ALTER TABLE market_history_candles ADD COLUMN IF NOT EXISTS expected_source_bars INTEGER;
ALTER TABLE market_history_candles ADD COLUMN IF NOT EXISTS quality_status VARCHAR(40);
ALTER TABLE market_history_candles ADD COLUMN IF NOT EXISTS import_chunk_key VARCHAR(100);
CREATE TABLE IF NOT EXISTS market_history_update_sessions (
 symbol VARCHAR(16) NOT NULL,session_date DATE NOT NULL,status VARCHAR(24) NOT NULL,
 started_at TIMESTAMP WITH TIME ZONE,completed_at TIMESTAMP WITH TIME ZONE,
 one_count INTEGER NOT NULL DEFAULT 0,five_count INTEGER NOT NULL DEFAULT 0,
 raw_one TEXT,raw_five TEXT,one_valid BOOLEAN NOT NULL DEFAULT FALSE,five_valid BOOLEAN NOT NULL DEFAULT FALSE,
 problem VARCHAR(240),PRIMARY KEY(symbol,session_date)
);
