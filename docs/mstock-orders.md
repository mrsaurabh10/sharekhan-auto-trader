# mStock order execution (Type A)

mStock is available in Brokers, the order form and the broker selector for strategy subscriptions.
One broker-independent strategy definition continues to use the existing entry, target,
stop-loss, trailing, manual-exit and end-of-day execution pipeline.

## Connect an account

1. Generate an API key and enable TOTP in mStock's Trading APIs settings.
2. In Brokers, choose **mStock** and save the API key and authenticator **setup secret**.
   A six-digit changing TOTP code is not the setup secret.
3. Verify the connection. The account's encrypted credentials and scoped session are used;
   global market-data credentials and other accounts' sessions cannot place its orders.
4. Explicitly enable trading before submitting an order or subscribing to a strategy.
   Saving/verifying an account does not enable trading. Existing position management remains
   available when new trading is disabled, while the account still has a valid session.

Authentication uses `POST /session/verifytotp`, `X-Mirae-Version: 1` and URL-encoded
`api_key` / `totp`. Session expiry is bounded by midnight Asia/Kolkata. Order calls use
`Authorization: token api_key:access_token`. Response bodies and secrets are not logged.

## Execution and recovery

- NSE/BSE cash and NFO/BFO derivatives resolve through the mStock instrument master.
  Missing mappings, changed persisted identities, invalid lots, unsupported exchanges and
  prices off the instrument's tick size prevent submission.
- Entries use LIMIT or stop-limit SL orders. After-hours placement retains the existing AMO
  selection. Modifying an SL preserves its trigger, total order quantity and validity.
- Cash buys use CNC and derivative buys NRML, including when the strategy exits the same day.
  Intraday cash shorts and covers use MIS. Stored products are retained for modification and
  exits; legacy positions without a stored product retain the previous MIS/intraday rule.
- Placement uses `/orders/{regular|amo}`. Modification and cancellation use
  `/orders/regular/{order_id}`. Status reconciliation uses the current `/orders` day book,
  avoiding historical snapshots incorrectly overriding a later cancellation.
- A database claim commits before the POST. The account/request/reentry or exit generation
  has one deterministic tag. Concurrent workers and retries cannot send it twice.
- A timeout, malformed success or ambiguous server error returns a pending `MSTOCK-TAG-...`
  handle. Polling resolves that tag against the selected account's day book, validating
  contract, side, product and quantities. No authentication/POST retry follows ambiguous delivery.
  If the broker does not return the tag, or the next trading day has begun, reconciliation
  can remain unresolved and needs operator investigation; absence is never permission to resend.
- Cancellation acknowledgement alone does not prove zero fills. Terminal partial entries
  use confirmed executed units and average price through the existing pipeline. Terminal
  partial exits stop repricing and raise an exit reconciliation warning; a full replacement
  exit is blocked until the actual remaining broker position is reconciled.
- Replacement exit generations require an explicit refusal or validated terminal zero-fill
  order. A fresh account-scoped net position must cover the requested exit quantity.
- BIGTRADEPLUS and carry-forward option shorts retain their existing broker capability gates.
  This integration does not add broker-native brackets, baskets or account-wide cancel-all.
  Market-data selection stays independent of execution broker selection.

## WebSocket order tracking

The order stream connects **only while the selected account has a persisted placed
order awaiting reconciliation**, including unresolved `MSTOCK-TAG-...` submissions.
An enabled broker account, an unplaced signal or an executed position without a
pending exit does not open an order connection. Existing pending orders restore
tracking on application restart. Each credential has one connection regardless of
its number of orders. After the last order settles or is confirmed inactive, the
next demand scan closes the connection (default two-second scan interval). A later
placed target/exit opens it again.

The connection uses `wss://ws.mstock.trade` with the account's API key and saved
access token, followed by `LOGIN:<access_token>`. mStock sends `type: order` and
`type: trade` text messages automatically. This service sends no market-data
instrument subscription and does not change the existing market-depth service.

Updates are batched per account (250 ms by default) and wake only that account's
registered orders. They request an authoritative REST day-book read through the
existing locked execution pipeline; socket payloads never directly confirm fills
or replace trade state. Duplicate, out-of-order or partial socket payloads cannot
regress execution state. Reconnect/login completion and disconnects also request
REST reconciliation to recover gaps. Existing periodic REST and active-chase
reads remain available if streaming fails.

Heartbeats detect dead connections. Reconnection backs off from 2 to 60 seconds;
closed session generations ignore late callbacks. Rotating/expired saved tokens
replace/close connections without initiating authentication. Invalid credentials,
unavailable sessions, oversized frames and connection errors never expose tokens
or authenticated URLs in logs. No connection is retained solely because trading
is enabled; position management still works when new trading is disabled.

Optional configuration (defaults shown):

```yaml
app:
  mstock:
    order-updates:
      enabled: true
      scan-ms: 2000
      flush-ms: 250
```

Official protocol: [WebSocket authentication](https://tradingapi.mstock.com/docs/v1/typeA/market-data/)
and [mStock SDK text-message callbacks](https://github.com/MiraeAsset-mStock/pytradingapi-typeA/blob/main/tradingapi_a/mticker.py).

## Schema and verification

`mstock_order_submissions` is additive. Hibernate's configured `ddl-auto=update` creates it;
installations with managed DDL should apply `src/main/resources/db/postgresql/mstock-order-submissions.sql`
during an authorized rollout. Never truncate the journal when recovering orders.

Tests use simulated broker responses, local HTTP fixtures and an H2 database. They cover
scoped authentication, wire headers/forms, entry/exit products, partial fills, SL modification,
cancellation, rejected and ambiguous submissions, recovery, concurrency and rollback survival.
No live broker orders are required for these checks. Deployment and live-session validation
are separate rollout steps.

Official contracts: [Authentication](https://tradingapi.mstock.com/docs/v1/typeA/User/),
[Orders](https://tradingapi.mstock.com/docs/v1/typeA/Orders/),
[Positions](https://tradingapi.mstock.com/docs/v1/typeA/Position/).
