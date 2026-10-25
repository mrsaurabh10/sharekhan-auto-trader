# Stock mover direction and buy-back exits

Stocks and F&O use one persisted FNO_0925_MOVER_ATR_BREAKOUT opening snapshot,
with the existing returned-list count rule (ties choose losers). Both qualify
underlying five-minute signals with the same directional service. Stocks maps
CE to cash BUY and PE to cash SELL. F&O continues to buy options. The common
selection is frozen at 09:25; missing late snapshots never rescan current movers.
Legacy stock-only snapshots remain historical and are not rewritten.

Cash quantities remain floor(investment per stock / signal entry). Shorts are
always intraday; stops sit above entry and targets below it. The new entry_action
on requests and setups persists BUY/SELL independently of option type. Null old
rows retain BUY semantics except the existing BTP short source. Source/account
identity and daily attempt guards remain intact. BUY covers close only the saved
remaining quantity through the existing exit claim, submission journal and fill
reconciliation. Manual, stop, target and intraday exits all use saved direction.
Reported running/booked P&L reverses for shorts. Cash target booking retains the
existing staged-share allocation and moves remaining stops toward entry and prior
target levels as stages close. All child legs retain SELL direction.

Adapters: Sharekhan short cash uses BIGTRADE; Dhan uses INTRADAY and verifies
negative SHORT exposure and existing pending BUY reservations before a cover;
MStock uses MIS. Entries and covers retain the original broker product. Entry
permission and exit submission identity are based on entry/exit intent, rather
than equating BUY with entry. Existing option buys and delivery longs keep their
products. Order tables show the persisted entry side.

## Rollout

This change has NOT been deployed. Apply the additive
src/main/resources/db/postgresql/stock-mover-direction-schema.sql before starting
the new application during an authorized rollout. No backfill of historical rows.
Prefer rollout outside market hours before the next 09:25 snapshot. Existing
cash longs preserve BUY direction. Never roll back to an older binary while a
new SELL cash request or short position is pending/open: older code assumes BUY
entry and SELL exit. Reconcile/cancel pending shorts and close/reconcile all short
positions first. Retain both additive columns on rollback. No live order smoke test.

Broker product reference: https://github.com/Sharekhan-API/shareconnectpython/blob/main/README.md
