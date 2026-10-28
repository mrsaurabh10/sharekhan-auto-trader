# NG Trend Averaging — Simulator trial

Independent template `MCX_NATURALGAS_TREND_AVERAGING`. Existing NG breakout and
Scalping settings are unchanged. Simulator only; no live broker execution.

## Fixed trial settings

- ₹60,000 capital ceiling, including cost reserves and prior conservative daily losses.
- Standard MCX NATURALGAS ATM CE for bullish trend, ATM PE for bearish trend.
- Buy one lot initially, then at most three additional lots. Four is a ceiling,
  not an obligation: expensive ATM contracts can allow fewer lots or no entry.
- ₹1,250 paper-net target for the entire basket, after a provisional ₹100 round-trip
  reserve per filled lot or higher recorded costs. This is not verified MCX brokerage.
- ₹3,000 total daily loss allowance per user/Simulator account, shared across this
  template's subscriptions. Profits do not replenish previous loss consumption.

## Trend and entry

Use completed futures candles. Aggregate 15-minute candles only from three
contiguous 5-minute candles. Prior sessions seed EMA calculations; no synthetic
candles bridge missing data. Need 24 complete 15-minute observations and the
latest expected completed 15-minute candle from today.

Bullish: 15m EMA9 above EMA21, EMA21 rising over three observations, close above
EMA21. Bearish is the mirror image. The completed 5m EMA9/21, EMA21 slope and
close must agree. Only act within 75 seconds of the completed 5m bar.

First entry selects ATM using fresh futures price and the resolved option expiry.
Keep that exact option contract throughout the basket, even when ATM moves.
One entry per completed 5m decision; pending or uncertain fills block additions.
No opposite-side entry until the current basket is confirmed flat.

## Add on a pullback

All original trend/alignment requirements must still hold. The completed 5m
candle touches EMA9 and closes on the trend side of EMA21. Futures must have
moved adversely by at least max(one futures tick, 0.5 ATR14) from the previous
fill's completed 5m close. Option ask must be at least one tick below both the
basket average and the previous fill premium. Recheck capital, executable spread,
daily allowance and the proposed stop before submitting exactly one additional lot.

## Exit and safety

Close the entire basket on its paper-net target, daily allowance, premium stop,
15m EMA9/21 reversal, or intraday square-off starting 23:20 IST. New entries stop
at 23:00 IST. After closure, another basket needs a newer completed 5m signal.
A weakening EMA21 slope pauses additions; the EMA9/21 reversal closes the basket.

Premium stop is rounded upward to the option tick and cannot move lower after an
addition. Reconstruct it from persisted fill prefixes after restart. Daily loss
consumption sums each basket's gross loss (minimum zero) plus cost reserves;
winning baskets do not offset losing baskets. Missing or inconsistent fill/quote
information blocks additions; trend exits can be requested without an option quote.

Use exact Sharekhan WS futures marks at most 15 seconds old and coherent option
books at most five seconds old. Entry spread must be at most 2%; exit risk checks
still work with a wider spread. Averaging execution submits the current ask instead of the generic midpoint,
rounded upward to the option tick and capped by the original admitted price.
Known pre-order quote waits retain the explicit no-order outcome. Paper entries
fill at executable ask, never above
the admitted ask; exits use executable bid. Missing quotes do not create fills.
The ₹3,000 guard is an exit trigger, not a guaranteed final loss cap; gaps, stale
feeds, execution delays and underestimated costs can produce a larger loss.

## Validation scope

Deterministic tests cover both directions, distinct additions, restart reconstruction,
affordability, pending/uncertain fills, risk exits, session cutoffs, ownership and
Simulator isolation. These rules are an unvalidated hypothesis: prior NG research
found no robust edge across its tested configurations. This strategy needs a
separate Simulator forward trial and realistic costs before live consideration.
No schema migration, subscription activation or production deployment is part of
this implementation.
