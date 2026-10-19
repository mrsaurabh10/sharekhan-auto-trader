# Running P&L from last known prices

Open executed positions expose `lastKnownPrice`, `runningPnl`, `runningPnlAsOf`
and `runningPnlSource` on the existing scoped executions endpoint. The display
uses actual entry fills and unit quantity; sell strategies reverse the sign.
Old prices remain usable after market close or a disconnected feed, with their
observation time. Missing marks stay unavailable, not zero. Booked `pnl` and
trade state are unchanged.

When a cached quote is unavailable or older than 15 seconds, Dhan positions can
supply a read-only fallback using only the trade owner's exact saved credential
and unexpired token. This requires one unclosed application contract and an
unambiguous broker position matching account, contract, product, fill average,
quantity and unit multiplier, with no sells. Account totals are never allocated
across multiple trades. The implied last price comes from entry average plus
broker unrealized P&L divided by quantity. Its timestamp is when the position
snapshot was received, not an exchange tick timestamp. Failed refreshes retain
the previous snapshot; token refresh and broker writes are never attempted.

Portal and admin execution views refresh every 15 seconds while visible and
outside confirmation dialogs. Portal labels the last price/time; admin exposes
source/time on price and P&L tooltips. Closed trades retain booked P&L.
Remembered snapshots are bounded in-memory data, not persisted across application
restart; the feed or Dhan must supply a mark again after restart. These display
marks do not change freshness requirements for strategy risk/exit decisions.
