-- Manual archive installation; no application startup migration or order integration.
CREATE TABLE IF NOT EXISTS option_history_chunks (
    chunk_key varchar(160) PRIMARY KEY,
    underlying varchar(12) NOT NULL CHECK (underlying IN ('NIFTY','BANKNIFTY')),
    security_id varchar(4) NOT NULL CHECK (security_id IN ('13','25')),
    expiry_family varchar(5) NOT NULL CHECK (expiry_family IN ('WEEK','MONTH')),
    expiry_code smallint NOT NULL CHECK (expiry_code = 1),
    strike_offset smallint NOT NULL CHECK (strike_offset BETWEEN -2 AND 2),
    option_side varchar(4) NOT NULL CHECK (option_side IN ('CALL','PUT')),
    interval_minutes smallint NOT NULL DEFAULT 1 CHECK (interval_minutes = 1),
    requested_from date NOT NULL,
    requested_to_inclusive date NOT NULL,
    public_request jsonb NOT NULL,
    captured_at timestamptz NOT NULL,
    http_status integer NOT NULL,
    status varchar(12) NOT NULL CHECK (status IN ('OK','PARTIAL','EMPTY','ERROR','SPLIT','QUARANTINED')),
    source_bars integer NOT NULL,
    valid_bars integer NOT NULL,
    regular_bars integer NOT NULL,
    invalid_bars integer NOT NULL,
    duplicate_bars integer NOT NULL,
    first_epoch bigint,
    last_epoch bigint,
    quality jsonb NOT NULL,
    response_sha256 char(64) NOT NULL,
    response_gzip bytea NOT NULL,
    contract_identity_status varchar(32) NOT NULL DEFAULT 'EXPIRY_NOT_PROVIDED',
    provider varchar(8) NOT NULL DEFAULT 'DHAN',
    CHECK (requested_to_inclusive >= requested_from),
    CHECK (requested_to_inclusive <= requested_from + 1),
    CHECK (security_id = CASE underlying WHEN 'NIFTY' THEN '13' ELSE '25' END),
    CHECK (NOT public_request ? 'dhanClientId')
);
ALTER TABLE option_history_chunks ALTER COLUMN response_gzip SET STORAGE EXTERNAL;
CREATE INDEX IF NOT EXISTS option_history_chunks_lookup
    ON option_history_chunks (underlying, option_side, strike_offset, requested_from, requested_to_inclusive);
COMMENT ON TABLE option_history_chunks IS
 'Gzip source blocks for rolling 1-minute options. Inclusive source dates empirically verified. Actual expiry/contract ID absent; never use ATM label as fixed-contract P&L identity.';
