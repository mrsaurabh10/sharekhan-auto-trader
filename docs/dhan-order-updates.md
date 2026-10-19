# Dhan live order updates and polling ownership

Dhan's individual order WebSocket runs alongside the REST adapter.
Single-order REST lookups accept both an object and an array (the latter observed
on 2026-10-19). Selection requires exactly one matching order/correlation and the
configured client; empty, foreign-account, duplicate or malformed results cannot
confirm execution. This restores entry confirmation through REST/chase snapshots
when a socket fill is missed. Only a consistent full fill with a positive actual
average price reaches the existing execution persistence and post-entry hooks.
Source reviewed 2026-10-17: https://dhanhq.co/docs/v2/order-update/ .
Local tests use mocked sockets/broker responses. No live broker connection, order,
production CPU measurement or deployment has been performed for this feature.

## Monitoring policy

| Mode | Status requests |
| --- | --- |
| Submitted entry awaiting confirmation | Shared poller every `app.order.poll-delay-ms` (default 2 seconds), unless its entry chase owns status reads. Matching stream updates can finish it sooner. |
| Resting `TARGET_ORDER_PLACED` on Dhan/Sharekhan | No recurring status timer or REST fallback. Dhan fill updates reconcile directly. Sharekhan ACKs request a coalesced authoritative REST check because an ACK alone lacks complete fill evidence. |
| Active entry, SL, exit or target chase | The chase obtains status once per iteration and publishes its scoped response to shared reconciliation. The general poller skips its REST call while the chase owns the order. |
| Entry chase paused/stopped with entry still pending | Entry confirmation polling resumes automatically; pausing repricing does not forget the submitted order. |
| Exit chase stopped without confirmed closure | One final shared status check when a general exit monitor exists, then stream updates/reconnect recovery. The chase deadline reports unresolved exposure. No indefinite exit polling. |
| Broker without an implemented order stream | Resting targets retain a slower 60-second REST fallback; confirmation/chase behavior stays available. |

Exit chases, including ordinary target chases, stop after five minutes or their
existing session deadline. Unknown broker status never permits repricing or a
replacement sell. A deadline does not establish a fill or cancel the order.
Startup restores monitors once; reconnect performs catch-up for tracked orders.
The previous five-minute repeating full-table recovery scan has been removed.
There is no timed final-session REST check for a socket-only resting target.
Explicit catch-up after the Dhan session deadline can still persist unresolved
attention, retaining order IDs and accepting later confirmed stream fills.

Dhan entry status failures retain the five-unavailable-outcome warning and
five-minute REST backoff. Broker/session gating remains; a final unresolved entry
check at/after 15:45 IST suspends that timer with attention. Polling never treats
an unknown response as rejection or success.

## Connection lifecycle and CPU

`DhanOrderUpdateService` opens `wss://api-order-update.dhan.co` per saved credential.
It sends SELF LoginReq with MsgCode 42, ClientId and the valid same-credential
access token. Sending login does not prove broker authorization. Tokens, raw
messages and credentials are not logged.

A five-second in-memory maintenance tick handles reconnection/heartbeat. Account,
token and pending-order demand are refreshed from the database once per minute;
placement requests a coalesced earlier refresh. This means credential changes
can take up to a minute to reach an already-connected stream. Cached expiry is
checked every tick. New order actions still validate current scoped credentials.
Trading-disabled accounts retain streams required by pending orders. No implicit
broker login or global-token fallback is used.

Retry backoff is capped at 60 seconds. Ping/pong uses 30-second pings and a
90-second liveness budget. Generations reject old socket callbacks. Delivery is
bounded to 256 queued tasks and frames to 64 KiB. A dropped/failed delivery marks
a gap; maintenance reconnects streams and triggers REST catch-up, preventing a
silently lost terminal event from leaving a socket-only target unresolved.
Per-session version tracking is bounded to 1,000 orders.

Dhan updates use an in-memory credential/order/correlation index. Manual or
unrelated account updates cause no trade lookup. Sharekhan ACK routing also uses
an order index and queues at most one outstanding check per tracked trade. Its
reconnect restores ACK subscriptions for pending entries and exits as well as
resting targets, then requests catch-up.

Dhan REST status reads remain fresh. Their full metadata may be consumed once by
the same worker's immediate modify call, within two seconds, avoiding a duplicate
GET before PUT. This is scoped by credential, trade, client, order and current
token. It is not shared between threads, cannot be seeded by socket data, and is
consumed before mutation. Stale/rotated metadata requires a new GET. Cancellation
always performs its own fresh lookup.

## Reconciliation and exposure

Only authenticated-client `order_alert` messages with consistent quantities,
valid average fill prices and broker timestamps are accepted. Duplicate/older
updates, decreasing fills and terminal regressions are ignored. Credential,
order/correlation, side and exchange segment must match before reconciliation.
A placement/modify acknowledgement never proves execution.

Chase snapshots, REST and stream events share per-trade serialization with the
existing decision lock. An old entry monitor stops before entry hooks create its
target. Replaced orders and foreign-credential snapshots cannot complete the new
order. Exit chase cancellation does not interrupt the thread persisting a fill.

Inactive targets/exits become `EXIT_FAILED`, retaining order ID and open-position
semantics. They do not reject the entry, sync its request to rejected, or release
its price feed. Sharekhan rejection ACKs now use this same exit-aware path.
Partial inactive Dhan exits retain original quantity/order ID and receive
`DHAN_PARTIAL_EXIT_RECONCILIATION_REQUIRED`; both polling and chase stop for manual
remaining-position reconciliation. No replacement sell is submitted.

Calendar close alone no longer clears an NFO delivery exit ID. Broker verification
is required; a missing status preserves the order for reconciliation. Notifications
and monitoring deadlines never infer that the bought position has closed.

Configuration defaults:

| Property | Default |
| --- | --- |
| `app.order.poll-delay-ms` | `2000` |
| `app.dhan.order-updates.enabled` | `true` |
| `app.dhan.order-updates.scan-ms` | `5000` |
| `app.dhan.order-updates.unavailable-backoff-ms` | `300000` |
| `app.dhan.order-updates.unavailable-limit` | `5` |

The former `app.dhan.order-updates.rest-fallback-ms` is superseded by mode-based
ownership. Special sessions/holidays are not newly implemented. Streaming adds no
schema; the prior Dhan submission journal is still required. Existing market-data
feeds are still needed for strategy and chase prices.
