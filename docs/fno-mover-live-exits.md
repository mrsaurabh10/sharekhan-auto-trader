# FNO mover early exits

The user authorized live activation on 26 October 2026. These exits apply only to
intraday BUY CE/PE positions with source `strategy:FNO_0925_MOVER_ATR_BREAKOUT`,
including active Simulator and live broker accounts. They do not activate paused
subscriptions or apply to cash movers, manual entries, straddles or MCX.

`app.strategy.fno-mover-exits.enabled` defaults to false. Deployment explicitly
sets `APP_STRATEGY_FNO_MOVER_EXITS_ENABLED=true` after applying the additive
`db/postgresql/fno-mover-exit-state.sql` migration. The runtime observer and Natural
Gas settings must be preserved. No historical fills or P&L are rewritten.

## Profit lock only (3 November 2026)

The user removed the 45-minute no-progress exit for both live and Simulator FNO
mover trades. Elapsed time and failure to reach half of the original stock risk
no longer produce an exit. Original stop-loss, targets, trailing and scheduled
square-off remain in their existing paths.

Progress fields and the persisted `FNO_EXIT_45M_V1` state version remain for schema
compatibility and diagnostics. This rollout neither resets nor erases existing
premium peaks, armed state or profit floors. The historical `FNO_NO_PROGRESS_45M`
reason remains recognized only so an already-claimed protective exit can still be
reconciled safely; no new evaluation emits that reason. Startup explicitly logs
`mode=PROFIT_LOCK_ONLY | noProgressEnabled=false`.

## Early premium profit protection

On 4 November 2026 the user lowered arming from 10% to 5%. The 50% retention
rate and cost/quote checks remain unchanged. Existing positions retain their saved
peaks and floors; their next valid quote is evaluated under the lower threshold.
An already-retraced open position can therefore request an immediate exit; this
does not recreate a fill at an earlier protected price.

Arm when a verified option **bid** is at least 5% above the actual entry premium
and exceeds the application-estimated round-trip cost floor.
Thereafter the floor is the higher of:

- entry premium plus 50% of the maximum observed premium gain; and
- the application's estimated net-profitable exit price for the remaining size.

The floor never decreases. A later verified bid at/below the floor closes the
entire remaining position with `FNO_EARLY_PROFIT_PROTECTION`. This is not a promise
of break-even or a guaranteed fill: a gap, slippage or actual broker charges can
produce a loss.

## Data and order handling

New exits require a coherent, noncrossed, positive Shoonya book, matching exchange
and exact option token, a book timestamp within five seconds, and displayed bid
quantity sufficient for the entire remaining position. Unknown/stale/insufficient
books do not trigger these exits. Stock progress is retained only as diagnostic state; a spot observation gap does
not disable the premium profit lock.

Trade ticks and a five-second cache-based timer evaluate the policy only during
the same day's 09:15–15:30 regular session. The timer makes no independent quote
or authentication requests; the existing exit pipeline retains its normal broker
reconciliation. No new standalone feed routes are added.

Peaks, arming, floors, original risk, progress and receipt times are committed in
the isolated state table before the trade's exit-claim transaction. Row locking
and insert-on-conflict protect concurrent observations. Existing stop-losses have
priority; other new protective claims close the full position. Target/partial
booking continues when no protective exit is due. A remaining position created
by partial booking inherits the previous peak, floor and progress state.

Only the existing atomic status claim and dispatcher can execute an exit. A losing
worker cannot submit an order. Existing exit IDs are repriced/reconciled and never
replaced blindly; failed modifications retain the same ID and protective reason
for chase. Recovery of an abandoned protective claim closes the full position,
not a target-sized partial lot. A policy-state failure leaves the original
stop/target processing available. Disabling the feature does not erase state or
cancel an already-claimed protective exit.

## Validation and rollout

Run policy persistence/data-quality tests and the price-trigger/exit-chase,
execution, broker, mover, ORM/journal, shadow and MCX regressions. The historical
research was not conclusive: only one recorded option session existed and its
paths had gaps. Removing the time exit is an explicit user decision, not evidence
of improved returns. Do not promise lower losses or profitability.

The initial feature used an additive migration; this update requires no schema change.
Take and fully decode the usual database backup before rollout;
retain the old image and state table for rollback. Rebuild/restart only the app,
preserving PostgreSQL, `.env`, data/log/dump mounts, paused subscriptions, and
other runtime overrides. Verify startup configuration, schema, packaged classes,
container state, `/login` and authentication guards without submitting test trades.
A production order trigger/fill is not proven by a healthy post-market startup.
