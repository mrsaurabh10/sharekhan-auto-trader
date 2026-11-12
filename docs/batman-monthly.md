# Batman Monthly Ratio Spread

`BATMAN_MONTHLY` is a broker-independent strategy template. The subscription selects
an execution account and symbol. One **base lot** buys one call and one put and
sells two calls and two puts. The requested initial subscription is NIFTY with
one base lot on the existing Simulator account.

## Entry and contract selection

- Monthly expiry is the last actually listed common CE/PE expiry in its month.
- Entry checks start at 45 calendar DTE and retry eligible mornings through
  40 DTE, within the specified 40–50 DTE range. The entry window is 09:20–09:25
  IST. Missing quotes do not consume the monthly claim; an attempted basket does. This timing is an implementation default; the video summary
  specified the 40–50 DTE interval rather than a time of day.
- Use the configured Dhan data account's live exact-expiry option chain. Buy the
  OTM call/put nearest absolute 0.20 delta; sell twice the lots at the farther OTM
  strikes nearest absolute 0.16 delta. Maximum deviation is 0.015 per leg.
- Missing/signed-invalid Greeks, contract mappings, matching lot sizes or an
  insufficient positive executable net credit cause a waiting result. There is
  no fixed-percent-strike or synthetic-Greeks fallback.
- Cross-check Dhan security IDs and lot sizes against its current contract master,
  and map to the exact execution master symbol/expiry/strike/type. Fresh exact
  option books come from the existing Sharekhan stream or verified Shoonya reads.
  All four entry books must have sufficient displayed quantity, at most a 5%
  bid/ask spread, at most five seconds of age and two seconds of inter-leg skew.
- Confirm both long fills before submitting short legs. Each of the four legs
  uses the normal trade-request/execution service. A durable account/broker/symbol/
  expiry claim prevents duplicate entries after concurrency, retries or restart.

## Exits and recovery

Center profit is the actual net credit after all four fills:

`2 × short call premium + 2 × short put premium − long call premium − long put premium`

Multiply by contract units and base lots. The run-level gross rupee profit target
and maximum loss are both that positive amount. The video's approximately 4% of
margin is descriptive; it is not a replacement for actual center credit or a
claim about measured historical margin. Custom profit, loss and trailing overrides
are rejected for this template.

Run P&L uses executable bids to liquidate longs and asks to cover shorts. A latched
TP/SL closes the entire basket through the existing run recovery service. Shorts
must be confirmed flat and all entry requests resolved before long hedges can be
released. Incomplete/uncertain entry, changed membership or nonpositive actual
credit requests an unwind. Restart recovery never resubmits a possibly placed leg.

Positions are carry-forward (`intraday=false`) with no individual targets, stops
or trailing rules. The expiry fallback requests a close at 15:20 IST on actual
expiry; fresh quotes and normal recovery remain required. A stop is an exit trigger,
not a guaranteed loss cap through gaps, outages or missing market depth. There are
no adjustments or re-entries for an attempted expiry. Monthly baskets can overlap.

## Execution support and limitations

The strategy has one calculation and request path for all subscriptions. Execution
adapters explicitly advertise carry-forward option-short capability. Simulator
currently implements it; other adapters reject configuration before any long leg
is submitted until their option-short product/margin handling is supported. This
capability does not activate any live subscription.

Simulator fills buys at ask and sells at bid, observes displayed quantity and
consumes a book's displayed depth across orders. It never substitutes LTP for a
missing Batman book. Paper trading does not model exchange SPAN margin or financing;
net P&L may be unavailable because the application's fees for short options are
not broker-reconciled. Dhan remains only the Greeks provider, independently of the
execution adapter. Existing account ownership and onboarding checks remain active.

The existing `NSE_HOLIDAYS` calendar is honored. Listed expiries handle
expiry-day holiday shifts. If an entry-day holiday is missing from configuration,
absent fresh books block entry and the following eligible morning can retry
through 40 DTE. The previous fixed-percent, held-to-expiry research does not validate these
live strategy rules. The historical Dhan strike-coverage limitation does not apply
to the live option-chain endpoint, but live Greeks and books must still be present.
