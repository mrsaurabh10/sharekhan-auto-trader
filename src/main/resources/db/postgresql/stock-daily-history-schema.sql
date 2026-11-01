CREATE TABLE IF NOT EXISTS stock_history_imports (
 id VARCHAR(80) PRIMARY KEY,
 provider VARCHAR(16) NOT NULL CHECK (provider='DHAN'),
 requested_from DATE NOT NULL,
 requested_to_exclusive DATE NOT NULL,
 required_session DATE NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 instrument_count INTEGER NOT NULL CHECK (instrument_count>0),
 candle_count INTEGER NOT NULL CHECK (candle_count>0),
 manifest_sha256 VARCHAR(64) NOT NULL,
 CHECK (requested_from<=required_session AND required_session<requested_to_exclusive)
);
CREATE TABLE IF NOT EXISTS stock_daily_candles (
 provider VARCHAR(16) NOT NULL CHECK (provider='DHAN'),
 exchange_segment VARCHAR(16) NOT NULL CHECK (exchange_segment IN ('NSE_EQ','BSE_EQ')),
 security_id VARCHAR(32) NOT NULL,
 symbol VARCHAR(64) NOT NULL,
 candle_date DATE NOT NULL,
 candle_epoch BIGINT NOT NULL,
 open_price NUMERIC(20,8) NOT NULL CHECK (open_price>0),
 high_price NUMERIC(20,8) NOT NULL,
 low_price NUMERIC(20,8) NOT NULL CHECK (low_price>0),
 close_price NUMERIC(20,8) NOT NULL CHECK (close_price>0),
 volume BIGINT NOT NULL CHECK (volume>=0),
 tick_size NUMERIC(12,6) NOT NULL CHECK (tick_size>0),
 captured_at TIMESTAMP WITH TIME ZONE NOT NULL,
 adjustment_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' CHECK (adjustment_status='UNKNOWN'),
 import_id VARCHAR(80) NOT NULL REFERENCES stock_history_imports(id),
 PRIMARY KEY(provider,exchange_segment,security_id,candle_date),
 CHECK (high_price>=open_price AND high_price>=close_price AND high_price>=low_price),
 CHECK (low_price<=open_price AND low_price<=close_price)
);
CREATE INDEX IF NOT EXISTS idx_stock_daily_symbol_date ON stock_daily_candles(provider,exchange_segment,symbol,candle_date);

CREATE TABLE IF NOT EXISTS stock_index_constituents (
 index_name VARCHAR(32) NOT NULL,
 snapshot_date DATE NOT NULL,
 symbol VARCHAR(64) NOT NULL,
 isin VARCHAR(12) NOT NULL,
 source_url VARCHAR(512) NOT NULL,
 source_sha256 VARCHAR(64) NOT NULL,
 captured_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(index_name,snapshot_date,symbol)
);

CREATE TABLE IF NOT EXISTS stock_history_update_runs (
 required_session DATE PRIMARY KEY,
 started_at TIMESTAMP WITH TIME ZONE NOT NULL,
 completed_at TIMESTAMP WITH TIME ZONE,
 status VARCHAR(24) NOT NULL,
 updated_count INTEGER NOT NULL DEFAULT 0,
 skipped_count INTEGER NOT NULL DEFAULT 0,
 failed_count INTEGER NOT NULL DEFAULT 0,
 problem VARCHAR(240)
);
