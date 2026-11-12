# Batman Ratio Spread

`BATMAN_MONTHLY` remains the stable template ID so existing subscriptions and run
history stay attached. The display name is **Batman Ratio Spread**. It accepts an
underlying symbol and an execution account independently; the current requested
subscription is NIFTY on Simulator with one base basket.

## Entries and next-day re-entry

- Consider every actually listed common CE/PE expiry, **weekly or monthly**, with
  **40–50 calendar DTE**. Choose the expiry closest to 45 DTE; prefer the earlier
  expiry if distances tie. Do not synthesize expiry dates or bypass the DTE range.
- Check entries during the **09:20 IST minute** on trading days. Missing data can
  retry within that minute, then on a later eligible morning. There is at most one
  entry attempt per account/broker/symbol/day, independent of which expiry wins.
- After a basket closes at its combined SL or target, a fresh basket can enter at
  09:20 on the next trading day. Cooldown starts from the actual confirmed close
  of the entire basket, not the time its TP/SL signal first appeared. If no expiry
  satisfies 40–50 DTE, continue waiting; do not reuse an ineligible expiry.
- Every previous basket in the same account/broker/symbol must have a CLOSED run
  and confirmed terminal trades/requests. A pending, partial, late or uncertain
  fill, or a missing run, blocks re-entry. No overlapping baskets or same-day
  re-entry, even if an early exit occurs inside the entry minute.
- Manual close, incomplete-entry abort and other non-TP/SL exits do not opt into
  automatic re-entry while that previous expiry remains unexpired. Once an old
  cycle has expired and is confirmed flat, a new initial entry may be admitted.
  Paused/cancelled subscriptions continue to block all new entries normally.

## Contract selection and execution

Buy one OTM call and one OTM put nearest absolute 0.20 delta; sell twice the lots
at farther OTM strikes nearest absolute 0.16 delta. Maximum deviation is 0.015.
Select fresh strikes on every new basket from the configured Dhan live chain.
No missing-Greeks, fixed-percentage-strike or synthetic-price fallback is used.

Cross-check the selected Dhan security IDs and lot sizes against its current
contract master and map to exact execution contracts by symbol, expiry, strike
and type. Fresh exact books come from the existing Sharekhan stream or verified
Shoonya reads. Four entry books need displayed quantity, at most a 5% spread,
five seconds of age and two seconds of inter-leg skew. Simulator buys at ask,
sells at bid and consumes displayed depth across orders.

The same standard trade-request/execution pipeline handles all four legs.
Confirm both long fills before submitting shorts. A durable daily claim and an
account lock serialize admission across retries, subscription copies and process
instances; the exposure gate is rechecked under that lock. Historical expiry-only
claims remain visible to the new admission gate. Restart never resubmits a
possibly placed leg.

## Basket risk and recovery

Actual confirmed fill credit sets equal combined gross rupee TP and SL:

`units × (2 × short call premium + 2 × short put premium − long call premium − long put premium)`

Base lots scale all four legs. Every re-entry recomputes this amount from its own
fills. The video's approximately 4% of margin is descriptive, not a substitute
for actual credit. Custom targets, loss limits and trailing overrides are rejected.

Run P&L uses bids to liquidate longs and asks to cover shorts. Exits latch and
recover through the existing run service. Shorts must be confirmed flat and
entries resolved before long hedges can be released. Incomplete entry, changed
membership or nonpositive actual credit requests an unwind. Positions carry
overnight with no individual SL/targets or adjustments. Expiry fallback requests
an exit at 15:20 IST. A stop is a trigger, not a guaranteed gap/outage loss cap.

## Availability and limits

The template and calculation are broker-independent. Execution adapters advertise
carry-forward option-short support; Simulator implements it, while unsupported
adapters reject configuration before any leg is submitted. No live subscription
is activated by this change. Existing ownership and onboarding controls remain.
Paper trading does not model exchange SPAN margin, financing or broker-reconciled
short-option charges. Historical Dhan strike coverage has not established a
backtest for this strategy. The existing NSE holiday calendar and fresh-book
checks are honored. No actual market fill is implied by code or smoke tests.
