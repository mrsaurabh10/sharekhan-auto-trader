# Scalping — independent MCX Natural Gas Simulator strategy

Template `MCX_NATURALGAS_SCALPING`, display name **Scalping**; trades use
`strategy:MCX_NATURALGAS_SCALPING`. The original Natural Gas evaluator, rules,
subscription and source remain unchanged. Starting Scalping is a separate
subscription action. This implementation does not enrol or activate it.

- Simulator only, one lot. Owned broker validation applies to direct and scheduled requests.
- Entry: completed native Shoonya three-minute close beyond the previous ten bars'
  high (CE) or low (PE), plus one futures tick. Directional candle required;
  no mandatory retest. Futures execution quote must still satisfy the boundary.
- Trend: latest completed five-minute EMA20 above EMA50 and rising over three bars
  for CE; below and falling for PE. Three-minute close must agree with a rolling
  volume-weighted typical price over 40 consecutive three-minute bars (two hours).
- Warmup: 40 current-day three-minute bars and 53 completed five-minute observations
  including available prior-session seed. Earliest three-minute warmup is 11:00 IST.
- Reject a signal candle wider than twice ATR14. Do not chase a price farther than
  one ATR from the breakout trigger. Only a fresh completed three-minute signal
  (75 seconds) and the expected latest five-minute close qualify.
- Buy the exact ATM CE or PE at a tick-rounded fresh ask, with a 10% premium stop
  and 15% premium target. Both are tick-rounded. Sharekhan exact-contract LTP and
  coherent book must be at most 15 seconds old, non-future, and spread at most 2%.
  Target gross amount must cover at least twice displayed spread cost plus the
  ₹100 paper roundtrip reserve. Top-of-book full-size fills remain a Simulator assumption.
- One open Scalping position per user/broker; uncertain entries block more entries.
  At most five persisted attempts per day, six-minute cooldown after attempt/exit,
  and a new completed signal after the last activity. Explicit pre-placement
  `ENTRY_NOT_READY_NO_ORDER` waits do not consume an attempt.
- Total daily loss allowance is at most ₹2,500, or a smaller configured amount.
  Persisted confirmed fills determine spent allowance across Scalping subscriptions
  for the same user/broker/day. Each fill reserves at least ₹100 paper costs; higher
  recorded costs take precedence. Winning fills do not replenish the loss allowance.
  Admit only if proposed stop risk plus the reserve fits the remaining allowance.
  A separate monitor requests run closure when loss-side realized/open P&L plus
  reserves reaches the allowance, using fresh exit bids for open positions.
- Entries 09:00–before 23:00 IST, pending entries cancelled from 23:00 and square-off
  requested from 23:20 within the configured 23:30 session. Confirmed exits still
  depend on the normal execution/reconciliation flow.

Three-minute and five-minute candles use separate cache/persistence identities;
no synthetic three-minute candles are made from five-minute history. Five-minute
trend uses prior Sharekhan seed and current Shoonya bars. Existing saved Shoonya
session is read without automatic login/refresh.

The ₹100 reserve is a Simulator allowance, not a verified MCX fee calculation.
Stops and the daily budget cannot guarantee an execution loss ceiling under gaps,
slippage or missing quotes. The rules are a trial hypothesis; no profitability or
historical option-fill backtest has been established. Other strategies have separate
budgets: this is not an account-wide ₹2,500 combined loss cap.
