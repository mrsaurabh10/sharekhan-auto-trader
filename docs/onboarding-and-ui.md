# Customer onboarding and shared trading UI

Implementation branch: `codex/user-onboarding-ui`.

## User journey

1. Visit `/account?mode=signup`, enter a name, email, and password.
2. Open the verification email and confirm. Tokens expire after 30 minutes and are single use.
3. Sign in at `/login`. New accounts cannot sign in until verified. Existing application users retain login compatibility.
4. In Brokers, save Sharekhan credentials. Passwords and secrets are encrypted and never returned by customer detail APIs. Empty edit fields preserve saved values.
5. Verify the connection explicitly, then explicitly enable trading. Verification uses broker authentication; it submits no trade. Connection expiry is shown in the workspace.
6. Configure and confirm a strategy subscription, or review and submit a buy order. Cash quantity is in shares; derivatives quantity is in lots. Activating a strategy can immediately create a live order when its conditions are met.
7. Review requests, executions, and personal analytics. Cancellation stops future strategy evaluations; it does not close positions. Order-request cancellation refuses requests with an execution record or an in-flight entry claim. Manage accepted orders/positions separately.

The first catalog exposes Sharekhan only. Existing adapters are retained internally;
add a broker to customer onboarding only after its connection and execution behavior
have been implemented and verified. There is no billing component.

## Deployment configuration (not configured or deployed by this change)

Registration and recovery are disabled unless all of these are available:

- `APP_ONBOARDING_ENABLED=true`
- `APP_ONBOARDING_BASE_URL=https://your-application-host` (an HTTPS origin, never a request-derived Host header)
- `APP_ONBOARDING_MAIL_FROM` set to an authorized sender
- A stable `APP_CRYPTO_KEY`, stored through the deployment secret mechanism
- Spring mail configuration: `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, and `SPRING_MAIL_PASSWORD` as required by the mail provider
- Provider-appropriate `spring.mail.properties.mail.smtp.auth` and `spring.mail.properties.mail.smtp.starttls.enable` or SMTP-over-TLS settings

Use a trusted mail provider and set finite SMTP connection, read, and write timeouts.
Do not put credentials in Git. Ensure the deployed administrator password is unique
and configured explicitly. Set `APP_ADMIN_TOKEN` for header-authenticated integrations;
an empty token now rejects calls. The H2 console and unmatched routes are no longer
public. Unknown routes and legacy admin screens require an administrator session.
Session-authenticated writes and logout require CSRF. HTTP Basic remains available for existing clients; the same account verification checks apply.
Header-authenticated trigger-all/close-all, backtest and historical endpoints remain
available with their configured token checks. Existing admin-session historical
access remains available.

The rate limiter allows 20 account/login POSTs per peer in 15 minutes per instance;
email reissue also has a one-minute cooldown per account. Deploy behind an edge rate
limiter and ensure the proxy strips untrusted forwarded headers. The existing
forwarded-header configuration must be reviewed against the actual proxy topology.
A distributed limiter is needed before horizontally scaling this deployment.

## Persistence and rollout

`account_profile` and `broker_connection` are additive JPA entities on the primary
application datasource. No existing `AppUser` constructor, broker table, or trading
record format was changed. Token values are stored as SHA-256 hashes; only emailed
links contain raw tokens, in URL fragments that the account page immediately removes.
Password resets expire registered browser sessions on this instance. Session state is
in memory; a multi-instance deployment requires shared sessions and invalidation.

Inspect the effective primary datasource and back up its data before rollout. The
existing default is H2 with Hibernate `ddl-auto=update`. PostgreSQL mirror flags do
not switch JPA to PostgreSQL. For PostgreSQL-primary installations using controlled
schema changes, review `src/main/resources/db/postgresql/onboarding-schema.sql`.
This change does not run it or switch databases. Include both new tables in backups
and any future H2-to-PostgreSQL migration; existing mirror backfills do not copy them.

A managed connection is required for entries from newly onboarded users and for
legacy accounts using the new connection flow, including background execution.
Global credential fallback is rejected for an explicit user or credential selection.
Existing position exits remain available when new trading is disabled. Disconnecting
or changing credentials is blocked while subscriptions, pending entries, or open
positions depend on that account.

Before release, verify real SMTP delivery/verification/recovery, one Sharekhan login
without submitting orders, expired-session reconnect, and datasource/schema behavior
in staging. Real broker tests were excluded from local verification. The local UX
preview uses a separate mock server; sample data is never included in production APIs.

## Shared theme

The customer workspace uses `portal/portal.css`, with shared tokens in
`portal/theme.css`. Existing static/Thymeleaf admin dashboards and the legacy order
page load `theme.css` and `legacy-theme.js`. Those scripts wrap wide tables for
horizontal scrolling and style text status cells without changing trading handlers.
The customer workspace includes personal request/execution tables, analytics filters,
P&L summary cards, a cumulative P&L chart, daily/symbol breakdowns, and recent closed
trades. Empty and failed data states are explicit; no production results are fabricated.

## Local UX preview

Run `python3 tools/ux-preview/preview.py --port 8766` from the repository root.
Open `http://127.0.0.1:8766/dashboard`, `/legacy`, `/login`, or `/account`.
This loopback-only design preview serves templates with synthetic fixtures and has
no connection to the application datasource, mail server, or broker services.
Do not enter real credentials in the design preview. Screenshots are local review
artifacts in `outputs/onboarding-ux/`; they are not part of the application runtime.

## Backend API contract

All customer writes use the authenticated session and CSRF token from
`GET /api/auth/csrf-token`; customer IDs in URLs/payloads never select another app
user. New-account registration and recovery require the mail configuration above.

| Endpoint | Backend behavior |
| --- | --- |
| `POST /api/account/register` | Validates name/email/password, stores a hashed password and verification-token hash, and sends verification email. |
| `POST /api/account/verify`, `/resend`, `/forgot-password`, `/reset-password` | Single-use expiring tokens and generic email-request responses; reset invalidates existing sessions. |
| `GET /api/user/onboarding` | Returns persisted-account progress, connected/trading-ready broker counts, active subscriptions, request count, and next step. |
| `GET /api/brokers` | Central release-approved broker catalog, including required credential fields. |
| `POST /api/user/brokers` | Typed credential payload; requires positive whole-number Sharekhan `customerId`, client code, API key, password, TOTP secret, and secret key. Saves encrypted, inactive and trading-disabled. |
| `PUT /api/user/brokers/{id}` | Validates lengths and types; blank secrets preserve stored values. Changes invalidate verification and require dependent activity to be cleared. |
| `POST /api/user/brokers/{id}/connect` | Authenticates against Sharekhan and stores an encrypted session token. Submits no trade. Requires customer ID for subsequent order routing. |
| `POST /api/user/brokers/{id}/trading` | Accepts `enabled`; requires a verified active connection before enabling. |
| `POST /api/user/brokers/{id}/disconnect` | Rejects disconnect while subscriptions, pending entries or open positions depend on the broker. |
| `POST /api/strategies/start` | Validates the registered strategy, symbols and quantity. Database locking serializes enrollment per account to avoid duplicate active subscriptions; evaluation runs after the enrollment transaction commits. |
| `POST /api/trades/trigger-on-price` | Validates the owned ready broker, exchange, prices, quantity and option contract. User orders support buy entries with stop-loss below entry and targets above entry. Hidden routing/quantity overrides are cleared. |
| `GET /api/orders/requests`, `/executed` | User-scoped table data; pagination is clamped to 1–100 rows. |
| `GET /api/analytics/trades`, `/sources` | User-scoped analytics; spoofed user/simulator scope does not expose another account. |

Broker credential payloads never accept app-user ownership, activation or trading
flags. Plaintext length limits account for encryption/base64 expansion in database
columns; request objects redact their string representation. SMTP connection,
read and write timeouts default to five seconds. The configured mail base URL must
be an HTTPS origin without embedded credentials, a query, fragment or nested path.

`BackendHttpIntegrationTest` boots the actual security/controllers/services/JPA
against an isolated H2 database. It exercises registration, verification, form login,
encrypted broker persistence, activation, subscriptions, order submission, table
reads, analytics isolation and concurrent enrollment. Only external email, broker
authentication and the existing trading execution boundary are stubbed; no schedulers
or real broker calls run. The existing broker execution tests cover that engine
separately. H2 integration tests do not establish PostgreSQL/live broker readiness.
