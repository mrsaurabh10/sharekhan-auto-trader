# ADX / DMI Fixed Partials

`ADX_DMI_FIXED_PARTIALS` is a broker-independent template. Symbol is an input;
there is no symbol whitelist. The selected owned account determines execution
through the normal broker adapter, including Simulator. Market-data providers
and the execution broker are independent. Current NIFTY/SENSEX subscriptions are
Simulator test instances; this change does not activate any live subscriptions.

## Entry and requests

- Completed regular-session five-minute underlying candles, IST. Wilder
  DMI(14)/ADX(14), ADX >25, dominant DI >25, opposite DI <20. Carry indicator
  history across sessions; require 200 bars and use the existing rolling history.
- Skip only 09:15–09:20 as a signal; retain it in indicator history. Earliest
  qualifying signal closes09:25. Entries must occur before12:00.
- Next-bar breakout: signal high plus one underlying tick for CE, low minus one
  tick for PE. Signal expires after that next five-minute candle.
- Use exact fresh underlying quotes and an exact fresh ATM option book. Choose
  the nearest listed strike in the nearest unexpired expiry, ties to lower strike.
  NC spot maps to NF options and BC spot to BF options. Option lot/tick metadata
  must be usable. If the local option tick is absent, read it from the exact
  provider-mapped contract in today's master, validating underlying, exchange,
  expiry, side, strike and matching lot size. Do not guess an option tick or
  update the local script master. Invalid local ticks and missing lots still wait.
  Absent underlying tick uses configured0.05 fallback.
- Submit **three separate normal one-lot BUY requests**, same contract, account,
  planned option entry and original fixed index SL. Each has only target1 set,
  respectively index1R/2R/3R; target2/3 unset, TSL false. No staged child orders
  or separate strategy fill ledger. Standard admission, routing, confirmation,
  manual exit, reconciliation and exit-order chasing remain responsible for orders.

## Index exits and 15-minute timeout

New requests carry server-owned `ADX_EXIT=INDEX_15M_V1` metadata. Their entryPrice
is the planned option premium (tick-rounded executable ask); their stopLoss and
target1 are **underlying levels**. `useSpotForEntry=false`, `useSpotForSl=true`,
`useSpotForTarget=true`. Freeze the reference underlying price from the final
confirmed breakout quote; R is its distance from the original signal extreme.
Actual option fills are stored separately and never move those index levels.
The engine validates prices using the entry flag: enabling spot SL/target does
not turn a premium entry into an index reference. The Simulator similarly compares
its ask with a stop only when that stop is denominated in option premium. Both
paths retain exact book, entry-limit, depth and genuine spot-price rejection.

New requests/trades store server-owned metadata in immutable nullable
`strategy_decision` columns, separately from outcome comments. Admission, entry
deadlines and index exits prefer that field; older rows fall back to their
original comment. Rejection/cancellation comments cannot erase new signal IDs
or cause duplicate batches. Already-processed signals return a waiting response.
Simulator request IDs use UUIDs so separate immediate lots cannot collide.

The new index policy observes exact fresh underlying quotes every scheduler
poll (default2s). It exits each independent request on its fixed index SL or
respective index target. Stops do not wait for a candle close; targets do not
wait for the option to be profitable. The common candle-stop/profit-floor
monitor skips this explicit version. Older premium-bracket requests retain
their old behavior; legacy paper records retain their legacy manager.

Start the timeout from the earliest confirmed entry fill in that signal's three
requests. If indexT1 has not been observed **before15minutes**, exit all remaining
lots. At the exact deadline, timeout takes precedence over a newly observedT1.
Once T1 was observed, disable this timeout for the remaining lots, with no SL
movement. No maximum holding time is added after T1. All remaining intraday
positions still trigger square-off at15:20; unavailable quotes or pending orders
can delay actual fills. No guaranteed exit price or deadline is implied.

An additive Hibernate-managed `adx_dmi_exit_state` table stores the first-fill
clock, T1 observation and timeout for each owner/account/symbol/signal. Account
locking serializes all three requests' observations. It is observation state,
not a custom execution lifecycle. Persist exit intent on each existing trade
before asking the normal engine to square off, so a missing option quote or an
index reversal cannot erase a trigger. Continue management after subscription
pause, entry cutoff and restart. Never restart the clock from process startup.
An observation cannot be reconstructed for periods when the application had no
fresh quotes; an unobservedT1 does not disable the timeout after an outage.

API absolute delta (fallback0.5) and other Greeks are retained as audit context
only. Delta does not set an option SL/target in this version. An index SL cannot
cap the option loss at its initial delta-derived estimate. Fees and P&L use
actual option fills via the ordinary engine. The app's shared fee estimator is
unchanged; the offline research model has explicit separate2026fee assumptions.

## Evaluation and limitations

June–October2026 option replay uses106 complete sessions per symbol, fixed entry
strike throughout each trade, three lots total (NIFTY65units/lot, SENSEX20).
No missing selected-path minutes or censored positions remain in the final32
exit-policy comparison scenarios. The implementation's pure Java exit policy
was checked against all2,316 exit decisions and29,790 minute observations in
four selected-rule runs, including both symbols and0/0.5%per-fill friction.
This verifies exit-policy parity conditional on the replay entries, not broker
fills or end-to-end TradingView parity.

| Selected index-exit + noT1-by15 rule | NIFTY net | SENSEX net |
|---|---:|---:|
| Reference fees, no assumed slippage | +40,314.40 | +31,960.50 |
| Reference fees,0.5% adverse execution per fill | -2,306.87 | -9,907.44 |

Amounts are estimated INR, not actual broker bills. The stress assumption is
not a measured spread. Historical replay observes minute-open breakouts and
minute-open index exit conditions, fills the held option at its minute open,
and uses fallback delta only for the previous premium-bracket comparisons.
The running strategy uses fresh quote observations, so intraminute decisions
can differ. Rolling option archives identify fixed strikes in the front-weekly
family but do not certify exact expired-contract securityIDs. No independent
holdout or measured live fills is included. June–October is an exploratory
selection sample; validate fresh data and bid/ask execution in Simulator.

The existing CSV backtest endpoint and `AdxDmiBacktestMain` still report an
**underlying proxy**, not this actual-option replay. Do not present their P&L as
option INR or claim TradingView parity. TradingView index pre-open bars and
provider feed/history differences remain relevant. Raw option research and
conditional Java verification artifacts are in the parent workspace under
`outputs/adx-dmi-option-timing-20261111/`; they are not bundled into the app.
