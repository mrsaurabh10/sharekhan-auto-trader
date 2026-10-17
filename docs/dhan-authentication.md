# Dhan token generation

Dhan is available in the customer portal's Brokers form for authentication only.
Order placement, quotes, positions and execution reconciliation are not implemented
for Dhan. Trading activation and strategy/order selection remain blocked for Dhan.
Sharekhan's existing connection and trading flow remains available.

Source: https://dhanhq.co/docs/v2/authentication/ (reviewed 2026-10-17).
The documented PIN/TOTP endpoint does not accept an API key. API key/secret uses
a separate browser-consent flow; these are offered as separate login methods.

## PIN + TOTP

1. Enable TOTP in your Dhan account and retain the authenticator setup secret.
2. In Brokers, add Dhan, select PIN + TOTP, and enter your Dhan Client ID,
   six-digit PIN and Base32 authenticator secret (not the current six-digit code).
3. Save, then select Generate token with TOTP. The backend generates the fresh
   code and POSTs to `https://auth.dhan.co/app/generateAccessToken` with the
   documented `dhanClientId`, `pin` and `totp` query parameters.

Keep the application host clock synchronized for TOTP. No global credential
fallback is supported. Repeat explicit token generation when verification expires.

## API key + secret

1. Create an individual API key/secret in Dhan Web. Configure the redirect URL
   as `https://YOUR_APP_HOST/api/user/brokers/dhan/callback`, matching your app's
   actual public origin. Remain signed in to this application in the same browser.
2. Add Dhan, select API key + secret, and save Dhan Client ID, API key and secret.
3. Select Login with Dhan API key. The backend POSTs generate-consent with the
   app credentials in headers, then navigates to Dhan's browser login page.
4. Complete Dhan login/2FA. Dhan redirects back with `tokenId`; the backend GETs
   consumeApp-consent with the saved key/secret headers and stores the token.
   The browser returns to Brokers with a success/failure message.

A pending consent is bound to the authenticated browser session, credential row,
owner and unchanged encrypted credentials, expires after ten minutes and is
consumed on the first callback attempt. Starting another consent replaces the
pending session. Missing/expired/replayed callbacks and wrong-account responses
cannot establish verification. After an application restart/session expiry,
start browser login again. No automatic background browser consent is attempted.

## Storage and verification

Existing broker credential/token tables and persistent AES-GCM encryption are
reused; no schema migration is needed. Dhan Client ID maps to encrypted clientCode,
PIN to brokerPassword, API secret to secretKey and authenticator seed to totpSecret.
Sharekhan-only customerId is not required for Dhan. Blank edits preserve saved
values and credential updates invalidate verification. API and TOTP credentials
can coexist on one saved Dhan account; edits add/replace the selected fields.

Both methods validate the returned Dhan Client ID, nonempty token and future
expiry. Offset-free expiry is interpreted as Asia/Kolkata, as documented by Dhan;
an explicit offset is honored. Verification expires 60 seconds early. Tokens are
persisted encrypted per credential row and never returned to the portal. Broker
response bodies, request URLs and exception causes are not included in errors.
The dedicated transport avoids RestTemplate's debug URL logging for PIN/TOTP.
Infrastructure access logging must avoid recording sensitive authentication query
strings, including callback tokenId; use HTTPS for credential entry and callbacks.

Automated verification uses synthetic credentials, mocked Dhan transport and
HTTP/security/JPA tests with H2. Live Dhan login and the public redirect registration
still require operator verification; no real broker calls or trades are test steps.
