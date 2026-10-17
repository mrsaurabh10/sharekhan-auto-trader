# Dhan live order updates and bounded reconciliation

Dhan's individual order WebSocket is implemented alongside the REST adapter.
Source reviewed 2026-10-17: https://dhanhq.co/docs/v2/order-update/ .
This is local implementation; no live Dhan account/socket/order or deployment
verification has been performed. Tests use mocked sockets and broker responses.

## Connection lifecycle

`DhanOrderUpdateService` opens `wss://api-order-update.dhan.co` per saved credential.
It sends the documented SELF login message with MsgCode 42, ClientId and the valid
same-credential access token. Tokens/credentials are not logged. A successful send
means the socket login message was sent; it is not reported as proof of successful
broker authorization. REST recovery stays independent of stream availability.

The five-second scan connects accounts that enabled trading or still have tracked
entry/exit orders. Order placement also requests a connection refresh. Disabling
new trading retains a stream needed for pending exits. Invalid/expired scoped tokens
close their streams; token/Client ID changes replace the connection. No implicit
broker login, global-token fallback or order action is performed by the connector.
Application shutdown aborts sockets and stops delivery/poll executors.

Connection failures use exponential retry backoff capped at sixty seconds, subject
to the scan interval. Ping/pong checks detect a silent socket (30-second pings,
90-second liveness budget). Socket generations reject delayed frames from an older
connection. A bounded delivery queue and 64 KiB fragmented-message limit prevent
unbounded buffering; dropped events are recovered by REST. Per-session order-version
tracking is bounded to 1,000 entries. Sanitized connection logs contain credential
IDs and retry timing, not auth messages, tokens or raw broker payloads.

## Reconciliation

Only `order_alert` messages for the authenticated Client ID are accepted. Quantity
consistency, average fill price and broker timestamp are validated. Duplicate/older
messages, decreasing filled quantities and terminal-to-other-status regressions
are ignored. OrderNo/CorrelationId, exchange segment, side and credential ownership
are checked before applying updates to a tracked application trade. Other account
orders, including manually placed orders, do not create or mutate application trades.

Stream updates and REST snapshots run through the same trade reconciliation under
per-trade serialization and the existing entry lock. A placement acknowledgement
still does not confirm a fill. The completed entry monitor is stopped before entry
hooks create a target monitor, avoiding a race that delayed new-target tracking.
Normal Dhan REST calls stay uncached, providing independent periodic recovery.
Sending socket login triggers REST catch-up for tracked orders. Unknown submission
correlation handles are matched directly to CorrelationId updates.

An inactive target/exit becomes EXIT_FAILED, retaining its original exit order ID
and open-position semantics. It does not reject the original entry, sync its entry
request to REJECTED or release its live price feed. This fixes the shared poller's
TARGET_ORDER_PLACED/EXIT_TRIGGERED terminal-classification defect for other brokers
as well. Entry rejection behavior and Sharekhan's existing poll interval remain.

Dhan partly filled inactive exits retain their full original tracked quantity and
order ID, become EXIT_FAILED, and receive a manual-reconciliation reason containing
filled/remaining units. No replacement sell is submitted and no position is inferred
closed. Normal full-fill validation is still required before position closure.

## Monitoring budgets

- Dhan gets an initial REST check after placement, then a 60-second fallback cadence.
  Live stream messages reconcile immediately; they do not postpone periodic REST.
- Five consecutive unavailable REST outcomes trigger a notification and five-minute
  REST backoff. Valid stream/REST evidence can clear the unavailable-status reason.
  A restart preserves the attention indication and resumes the slower failure budget.
- Regular reconciliation is session-gated. A final REST attempt occurs on the first
  scheduled check at/after 15:45 IST on the order's recorded session date (including
  an unresolved order rediscovered later). This is a reconciliation deadline, not
  exchange timing or permission to place orders after close.
- If the final outcome is still pending/unavailable, the REST timer is cancelled and
  DHAN_SESSION_RECONCILIATION_REQUIRED is saved with an owner notification attempt.
  The position/order IDs remain; late matching stream events can still establish a
  confirmed outcome. Restart does not reactivate frequent polling for this reason.
- Partial inactive exits get DHAN_PARTIAL_EXIT_RECONCILIATION_REQUIRED and suspend
  their timer immediately. They need operator reconciliation of remaining exposure.

The stream may remain connected while a valid account is trading-enabled or has
unresolved orders. Suspending an order's periodic timer does not disconnect all
other orders in that account. Application monitoring uses the existing weekday
calendar; special sessions/holidays are not newly implemented. The deadline does
not guarantee a broker fill, cancel an order or authorize automatic replacement.
Notification transport failures do not discard the persisted attention reason.

Configuration properties (defaults shown):

| Property | Default |
| --- | --- |
| `app.dhan.order-updates.enabled` | `true` |
| `app.dhan.order-updates.scan-ms` | `5000` |
| `app.dhan.order-updates.rest-fallback-ms` | `60000` |
| `app.dhan.order-updates.unavailable-backoff-ms` | `300000` |
| `app.dhan.order-updates.unavailable-limit` | `5` |

No additional schema is required for streaming. Attention reasons/comments use
existing trade columns; the prior Dhan submission journal remains required.

Correction to earlier investigation notes: shared REST polling reads
`app.order.poll-delay-ms`, default 2000 ms. The source YAML's 5000 ms value belongs
to `app.mstock.poll-delay-ms` and does not set this shared property. The new Dhan
fallback uses its separate explicit property above. Actual production overrides
were not inspected in this change.
