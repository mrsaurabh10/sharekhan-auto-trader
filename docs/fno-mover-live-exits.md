# FNO mover early exits

The user authorized live activation on 26 October 2026. These exits apply only to
intraday BUY CE/PE positions with source `strategy:FNO_0925_MOVER_ATR_BREAKOUT`,
including active Simulator and live broker accounts. They do not activate paused
subscriptions or apply to cash movers, manual entries, straddles or MCX.

`app.strategy.fno-mover-exits.enabled` defaults to false. Deployment explicitly
sets `APP_STRATEGY_FNO_MOVER_EXITS_ENABLED=true` after applying the additive
`db/postgresql/fno-mover-exit-state.sql` migration. The runtime observer and Natural
Gas settings must be preserved. No historical fills or P&L are rewritten.

## No progress after 45 minutes

Time starts at the actual option fill `entryAt`. Original risk is the absolute
distance between the initial stock trigger and stock stop. If the maximum
**observed** favourable stock movement has never reached 0.5 of that distance,
close the entire remaining position at/after 45 minutes. PE progress is downward;
CE progress is upward. Equality at 0.5R counts as progress and disables this timer
exit permanently. A trade that first progresses and then reverses is not a
no-progress trade.

The original entry/stop are frozen from the owned entry request when available,
otherwise from the untouched initial stock-stop trade. Observations must start
within 15 seconds of the fill. A gap longer than 15 seconds between stock receipts
permanently disables the no-progress exit for that position because an unseen
favourable excursion cannot safely be ruled out. It remains disabled after a
long restart gap or late adoption of a pre-existing position. This conservative
rule does not delay or disable the original stop or premium protection. State is
visible in `fno_mover_exit_state.last_message`.

## Early premium profit protection

Arm when a verified option **bid** is at least 10% above the actual entry premium
and exceeds the application-estimated round-trip cost floor.
Thereafter the floor is the higher of:

- entry premium plus 50% of the maximum observed premium gain; and
- the application's estimated net-profitable exit price for the remaining size.

The floor never decreases. A later verified bid at/below the floor closes the
entire remaining position with `FNO_EARLY_PROFIT_PROTECTION`. This is not a promise
of break-even or a guaranteed fill: a gap, slippage or actual broker charges can
produce a loss. The no-progress reason is `FNO_NO_PROGRESS_45M`.

## Data and order handling

New exits require a coherent, noncrossed, positive Shoonya book, matching exchange
and exact option token, a book timestamp within five seconds, and displayed bid
quantity sufficient for the entire remaining position. Unknown/stale/insufficient
books do not trigger these exits. Stock progress uses its independent fresh
receipt timestamp even if the option book is unavailable; it is not inferred from
option premium changes. Stock receipts may still be sampled/conflated.

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
paths had gaps. The 45-minute threshold is an explicitly user-selected live rule,
not a statistically proven optimum. Do not promise lower losses or profitability.

Take and fully decode the usual database backup before the additive migration;
retain the old image and state table for rollback. Rebuild/restart only the app,
preserving PostgreSQL, `.env`, data/log/dump mounts, paused subscriptions, and
other runtime overrides. Verify startup configuration, schema, packaged classes,
container state, `/login` and authentication guards without submitting test trades.
A production order trigger/fill is not proven by a healthy post-market startup.
