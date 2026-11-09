# Supertrend Simulator risk policy

The existing `ST_RSI_EMA_ADX_CE` and `ST_RSI_EMA_ADX_PE` templates enforce the
following policy for owner-matched Simulator broker accounts. Live broker
accounts and other strategy templates keep their existing behavior.

- On NIFTY expiry day, resolve the next actually listed expiry instead of buying
  today's contract. Other days retain the nearest listing.
- On BANKNIFTY expiry day, use the monthly listing in the **next calendar month**,
  including December-to-January. Resolve the last listed expiry within that
  month, never add a fixed number of days. If unavailable, fail the evaluation
  without creating an order; do not use today's contract or skip to a later month.
- Skip an otherwise qualified signal when the initial signal-candle spot stop
  distance exceeds **1.5 × five-minute Wilder ATR(14)**. Equality passes. Use
  completed bars only, the last 250 observations within 14 calendar days, and
  require at least 50 observations. Invalid/zero ATR or OHLC waits for valid data.
  Rejections appear as `ATR_STOP_WIDTH` or `ATR_STOP_DATA` in strategy diagnostics.
- New strategy runs get a maximum gross loss of **₹3,000 per original lot**.
  Three lots means ₹9,000 across booked and remaining position P&L. Existing
  stricter configured stops are retained; other total/per-lot controls retain
  their original units. No existing subscription record is rewritten. Settings
  are captured when a new run is created; already-created runs are not backfilled.

Stops and targets remain the original spot levels, with existing partial
bookings and target-triggered trailing unchanged. The rupee loss trigger is an
additional run close condition. It uses fresh executable book marks and the
existing run monitor; fees, gaps, stale quotes and execution latency can cause
realized loss to exceed the trigger. This is not an account-wide daily cap.

The policy defaults enabled and logs `SUPERTREND_SIMULATOR_POLICY` at startup.
`app.strategy.supertrend.simulator-policy-enabled=false` disables this Simulator
policy for rollback; normal generic run risk settings still apply.

## Validation boundary

The retrospective ATR screen removed eight of 47 recorded October 1–November 9
entries, including both major losses and the November 6 ₹20,353 CE winner. It is
not proof of profitability. A complete three-rule option backtest remains
unverified because next-expiry exact-contract price paths were not archived.
Application unit/regression tests verify implementation behavior, not returns.
