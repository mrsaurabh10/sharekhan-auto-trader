CREATE TABLE IF NOT EXISTS telegram_user_settings (
    app_user_id bigint PRIMARY KEY REFERENCES app_user(id),
    username varchar(32),
    chat_id varchar(20),
    link_hash varchar(64) UNIQUE,
    link_expires_at timestamptz
);
