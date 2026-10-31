# Mover entry readiness and cash cost corrections

## Entry behavior

Simulator entries keep a directional traded-price gate: BUY requires LTP at or
above entry; cash SELL requires LTP at or below entry. Buying a PE is a BUY and
continues to use its existing option/spot entry rules. Immediate no-order waits
remain terminal rejected request records with ENTRY_NOT_READY_NO_ORDER and are
reported as waiting; they do not create a fake position or consume the daily cash
entry allowance. Queued readiness waits remain pending, as before.

If a mover's shared decision expires before the first broker call, execution
returns the same explicit no-order wait. The next evaluation must calculate a
fresh decision; the 30-second TTL and candle boundary checks are unchanged. No
broker call is made using the expired decision. Existing exact legacy reason
`ENTRY_WINDOW_ELAPSED: no order submitted` is also exempted from the cash daily
guard. Other errors, unknown outcomes, actual entries and ambiguous submissions
remain blocking. No blanket exception is made for rejected requests.

## Cash cost estimate

Cash NC/NSE/BC/BSE shares use a separate reference estimate, independent of `lots`.
For ordinary intraday equity it uses 0.03% brokerage capped at INR20 per executed
order, sell-side STT0.025%, buy-side stamp0.003%, SEBI turnover0.0001%, GST18%
on brokerage/exchange/SEBI, and published reference transaction/IPFT rates. NSE
transaction plus IPFT is0.0030700%; standard-group BSE transaction is0.00375%.
A staged group shares the single original entry charge in proportion to filled
shares; each closed target leg has one exit charge. Per-leg rounding can differ
by a few paise from a single contract-note calculation.

The delivery reference uses zero brokerage, STT0.1% on both sides and buy stamp
0.015%. DP fees, MTF interest, special BSE groups, exempt ETFs, negotiated broker
plans, exchange/broker changes and broker-initiated square-off fees are not modeled.
These are estimates, not broker-reconciled bills. In particular the published
reference is Dhan's standard tariff; it does not establish the account-specific
Sharekhan/MStock tariff. The simulator uses the same explicit reference model.
Existing option-lot fee rules and the MCX no-estimate policy are unchanged.

Reference reviewed 26 October 2026: https://dhan.co/pricing/ (cash equity table).
Historical2024NSE cash reference differs from today's published tariff; the new
model is explicitly versioned `cash-order-reference-estimate-2026-10-26` and does
not claim to reproduce each earlier contract note's historical tax schedule.

## Historical display

Order/execution responses, monitoring snapshots, analytics summaries and run
views recalculate cash estimates from recorded prices/quantities/direction. Run
position views use detached display copies. Stored fills, gross P&L, previous fee
columns and request/audit history are not rewritten. New closed rows use the
corrected model when normal persistence calculates estimated costs. Order tables
label costs and net P&L as estimates; execution responses expose the model name.

For the 26 October BANKINDIA short (3982 shares, 125.65 entry, 126.50 cover, three target
legs), the test fixture preserves gross loss 3384.70 and replaces the false 47919.27
fee estimate with a per-order estimate below 300. The exact new estimate is saved
in validation evidence; it is not an independent broker contract note.

## Release

No schema migration or production data backfill is needed. This change is not
deployed yet. Preserve paused subscriptions and live risk/spread/freshness limits.
The older stock-short release's rollback constraint remains: do not run BUY-only
binaries while cash short requests or positions remain pending/open. No live
order smoke tests are required or authorized by the test workflow.
