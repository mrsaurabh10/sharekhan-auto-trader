# FNO mover: fresh retest after a risk rejection

Implemented after the 29 October 2026 review. The day's five candidates generated
this hypothesis; they do not establish a profitable edge or validate the new rule.
This is an explicitly user-authorized live implementation, shared by Simulator
and live FNO mover accounts. Stock mover and manually curated FNO entry methods
retain their existing behavior. Existing morning ORB and VWAP reclaim signals take
precedence. Afternoon breakouts are not added.

## Fixed entry rules

1. The running FNO mover must actually observe a completed, directional morning
   ORB rejected at its structural-risk check, after its volume, VWAP-side and candle
   filters passed. Baseline VWAP reclaim must also have failed. Historical bars
   alone cannot create an anchor; restarting forgets anchors and requires a new
   observed rejection. No old signals are replayed.
2. Only the next three completed five-minute candles (15 minutes) can provide a
   retest and a *subsequent* confirmation. Bars from the rejection through the new
   signal must be consecutive. Any touch of the original structural stop invalidates
   the anchor. An expired/invalidated anchor can be replaced only by a new observed
   risk rejection.
3. For CE, the immediately preceding retest candle must trade to within 0.35 ATR
   above the opening-range high (or below that level), close at/above the range high,
   and close below the anchor close. The latest completed confirmation must be
   bullish and close strictly above both the retest high and opening-range high.
   PE mirrors these conditions around the opening-range low.
4. Apply existing ORB volume, VWAP-side, candle-body and wick filters to the new
   confirmation. ATR(75) for retest checks uses completed regular-session bars only.
   New structural stop is the lower low of retest/confirmation for CE, higher high
   for PE. Entry reference is the latest confirmation close; round both to 2 decimals.
   Positive structural risk must fit the smaller of the configured cap and **2 ATR**.
5. Use only the latest expected completed candle, at most 30 seconds after evaluation,
   before the existing morning ORB cutoff (11:30 signal candle; completed 11:35).
   Existing scheduler-cycle expiry may be stricter. Never backfill missed entries.

Market state is isolated by date, exchange, spot scrip, symbol and side, capped at
256 keys. Cached current-candle results are shared across account evaluations;
requests and account state are not shared. Existing per-account daily entry limits
remain. Normal FNO trigger construction still applies its minimum 1.5 ATR stop
distance and existing ATR targets; a tighter research structural stop does not
bypass that order-level minimum.

## Orders and exits

A retest signal uses `MORNING_ORB_FRESH_RETEST` and the normal FNO source. It proceeds
through the existing contract resolution, quote identity/freshness/spread/slippage,
entry deadline, funds, sizing and duplicate safeguards. No special broker route,
authentication, order replay or risk-budget increase is added. Existing early profit
protection and 45-minute no-progress exit policy apply through the same FNO source.
No change to subscriptions, lots, paused strategies or 07:00 IST master refresh.

`app.strategy.fno-0925-mover.fresh-retest-enabled` defaults true. Set false and restart
the app to disable only this entry path; existing ORB/VWAP paths remain available.
Baseline qualification does not depend on retest input availability. Public metadata
lists the new entry path. Audits record the new setup and its signal/stop; market-only
`FNO_RETEST_ANCHOR` and `FNO_FRESH_RETEST` log events identify anchor and confirmation.

## Validation and review

Deterministic code tests cover chronology, CE/PE symmetry, risk, volume/VWAP/candle
filters, original-stop invalidation, gaps, cutoff, date/identity isolation, restart,
shared account results and normal order routing. These establish implementation
behavior, not returns. The 29 October option histories contain traded OHLC, not
historical executable bid/ask/depth; do not call their peaks attainable profits.

Collect distinct setups across sessions, including losers and no-entry sessions.
Compare against unchanged baseline rules on time-ordered held-out sessions; include
actual costs, ask/bid and depth, slippage stress, both 45-minute/profit-protection
exits, and coverage gaps. Repeated account evaluations are not independent samples.
Existing shadow quote-policy comparisons do **not** independently replay the current
profit-protection/no-progress policy and are insufficient to establish this edge.
No automatic parameter optimization or profitability claim is made.
