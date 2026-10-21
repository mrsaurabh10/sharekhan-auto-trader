# Personal Telegram notifications

In the personal workspace open Settings → Telegram notifications. Save your
personal Telegram username, select Connect Telegram, open the generated link,
and press Start using that Telegram account. Refresh status to confirm Connected.
Links last ten minutes, are single-use, and are stored only as SHA-256 hashes.
A new link invalidates the previous one. Linking requires an authenticated
Telegram webhook, a private chat, identical chat/sender numeric IDs, a non-bot
sender and a username matching the account's saved username (case insensitive).
The app captures the numeric chat ID; a username alone is only pending setup.

Only the signed-in application user can change their destination. No user ID is
accepted by the settings API. Admin accounts without a personal AppUser do not
expose this setting. Changing username disconnects the old destination. Removing
the setting restores shared delivery. Save/link do not send Telegram messages.

Ordinary user trade updates and durable exit-attention alerts route to a connected
personal destination. Users without one retain existing shared delivery. A send
failure never falls back to the shared destination. Entry-action prompts and
source-control buttons retain existing shared operator chat authorization,
message IDs, and callback behavior; this release grants no personal trade actions.
Global operational alerts remain shared. Telegram bot token is never returned to
the browser or stored in personal settings. getMe metadata uses a five-minute
cache and sanitized failures. No broker/token/order changes are part of setup.

Apply docs/personal-telegram-schema.sql before starting this version. The additive
table is independent of trading data. Preserve it if rolling the application back.
Live account linking and actual notification delivery need the account holder to
press Start; deployment checks do not send notifications or real trades.
