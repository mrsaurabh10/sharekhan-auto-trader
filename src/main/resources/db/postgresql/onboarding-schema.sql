-- Reviewed, additive schema for installations using PostgreSQL as the PRIMARY JPA datasource.
-- Do not apply this to the PostgreSQL mirror while the application still uses H2 as primary.
-- Existing development installations using ddl-auto=update create these JPA tables automatically.
CREATE TABLE IF NOT EXISTS account_profile (
    user_id BIGINT PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    verified_at TIMESTAMP WITH TIME ZONE,
    token_hash VARCHAR(64) UNIQUE,
    token_purpose VARCHAR(16),
    token_expires_at TIMESTAMP WITH TIME ZONE,
    version BIGINT
);
CREATE TABLE IF NOT EXISTS broker_connection (
    credential_id BIGINT PRIMARY KEY,
    verified_until TIMESTAMP WITH TIME ZONE,
    version BIGINT
);
