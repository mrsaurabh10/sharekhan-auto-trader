# MCX Natural Gas ATM breakout strategy

Template: `MCX_NATURALGAS_ATM_BREAKOUT`. Source:
`strategy:MCX_NATURALGAS_ATM_BREAKOUT`.

This is a new unvalidated trading hypothesis, initially intended for simulator
observation. It is selectable for an owned Simulator or Sharekhan account. Other
broker adapters are refused before enrollment. No subscription is automatically
started and this change does not deploy or submit a test order.

## Apply

In Strategies, choose **MCX Natural Gas · ATM CE / PE breakout**, select the broker,
set lots (1–10), and explicitly set the run's **maximum loss in rupees**. The
instrument is fixed to NATURALGAS and intraday is mandatory. The same controls
work in the customer portal and admin strategy form. No NYMEX service, subscription,
key, TradingView webhook or new paid data provider is required.

The selected account still needs normal onboarding/connection/trading readiness.
A simulator execution account does not supply market data: the existing Sharekhan
market-data connection must provide exact MCX futures history and fresh option
books. Failures appear as waiting/error messages; missing inputs never bypass a
filter. Actual MCX feed coverage and fills have not been verified by this change.

## Signal

* Use the nearest available non-expiry-day NATURALGAS option series in the MCX
  master. Match its underlying to the unique MCX futures contract in the same
  delivery month with expiry on/after the option expiry. Missing/ambiguous mapping
  prevents entry. NATGASMINI and NSE instruments are not substituted.
* Only completed five-minute candles from that exact futures scrip are used.
  Aggregate contiguous triplets into fifteen-minute closes for EMA20/EMA50; EMA20
  must also slope in the signal direction versus three completed fifteen-minute
  bars earlier. Warmup requires at least 53 valid fifteen-minute groups.
* Session VWAP uses typical price times volume from 09:00 IST. Today's candles
  must cover every five-minute interval from 09:00 without gaps and have positive
  volume. Zero/missing volume prevents signals; there is no unweighted substitute.
  Historical JSON `volume`/`Volume`/`qty`, or OHLCV array volume, is retained. The
  provider's MCX volume semantics remain to be confirmed against a live response.
* A candle closes beyond the preceding 20 same-session candles' high/low.
  Within the next three candles, the latest completed bar must retest that boundary
  and close directionally back beyond it. An intervening close through the boundary
  invalidates the setup.
* A fresh futures price must cross the rejection bar extreme plus/minus one
  futures tick. Do not chase more than 0.5 times the setup risk. Risk is the larger
  of ATR14 (simple mean true range on five-minute bars) and structure plus 0.2 ATR;
  skip above two ATR. This risk bounds entry extension; it is not the option stop.
* The latest signal close must be no more than 75 seconds old, futures receipt no
  more than 15 seconds old. The schedule evaluates at second 5 of every minute;
  incomplete or missing bars never create a historical fill.

Bullish confirmation buys CE; bearish confirmation buys PE. Both enter BUY and
close SELL. Select the nearest **listed** strike to the current MCX futures price;
a tie chooses the lower strike. Save that exact expiry/strike once submitted.
Require unique contract, positive lot/tick metadata, coherent uncrossed
Sharekhan-websocket bid/ask no older than 15 seconds, exact scrip provenance, and
spread at most 2% of ask. Neither a fresh LTP nor a quote from another contract
refreshes an old book. Depth quantities are currently unverified.

## Option risk and exits

The entry reference is ask rounded up to the option tick. The initial stop is 25%
below that reference, rounded down; one full-position target is 50% above, rounded
up. Stops/targets reference **option premium**, not the futures price. Existing
fill adjustment, order status, slippage, exit ownership and reconciliation paths
remain in force. There are no staged targets, automatic reversals, averaging or
gap reentries. A real/ambiguous request or filled position consumes the broker's
one daily entry even after exit/restart; explicit proven-no-order waits can retry.
Unresolved positions or unknown submissions from prior days also block new entries.
The in-process evaluator serializes concurrent Apply calls; horizontal deployment
would need a cross-instance atomic day-claim before allowing multiple app replicas.

Admission checks `(entry premium - stop) × lot size × lots` against the selected
run loss budget. Run-level risk continues throughout the evening and can close
sooner. Limits use gross rupee P&L, exclude fees, and cannot bound losses through
price gaps, stale feeds or broker/exchange failures. No percentage-of-capital
sizing is inferred. For example, premium 5 and stop 3.75 with 1,250 units requires
at least 1,562.50 gross risk budget for one lot; margin/premium availability remains
a separate broker check.

Simulator entry uses fresh ask and refuses a book above its execution limit;
simulator exit uses fresh bid. This is full-quantity top-of-book estimation with
unknown depth, not queue/partial-fill simulation or guaranteed live execution.
Existing premium SL/target trigger evaluation is sampled from received ticks;
no claim is made that every intra-tick touch is captured. MCX fees are not modeled:
this source returns unknown fees/net rather than reusing equity STT/fee constants.
Gross P&L and premium fills remain available.

## Session and recovery

Normal requested window: 09:00–23:30 IST on weekdays. New entries stop at 23:00;
pending entries are canceled/reconciled from then. Filled positions continue until
23:20, when the run latches CLOSING and retries its owned exits until before 23:30.
Existing exit IDs and uncertain/partial outcomes are retained. Sharekhan entry,
exit, modification and cancellation payloads use regular-session flags for this
source; orders outside the window cannot silently become AMO orders. Cancellation
acknowledgment is never proof of flatness. Without a usable exit quote or broker
confirmation the run stays unresolved; no artificial close at 23:30 is recorded.
An unresolved prior-day run is closed/reconciled at the next normal session open.

The new source is excluded from the equity 15:35 closer, generic equity entry
cancellation, and destructive nightly request purge. Other strategies retain their
session schedules. Evening recovery queries this source only outside equity hours.
Natural Gas requests are retained as audit evidence, including unresolved/no-order
attempts; no new retention cleanup is installed. Existing risk/run persistence is
reused, so no new database table or migration is introduced.

This version uses the explicitly requested normal session times, with weekday and
fresh-data checks. It does not fetch MCX holiday/short-session calendars or broker
special cutoffs. Do not activate on special sessions without reviewing those times.
The expiry-day series is skipped to avoid carrying it into exercise/devolvement.

## Verification and release

Tests cover bullish/bearish signals, completed bars, stale/gapped/volume-less data,
exact futures/ATM mapping, broker ownership/capabilities, quote provenance/spread,
rupee admission, day limits, simulator spread fills, evening run risk, buffered
close and pending/late-fill handling, plus existing equity entry/exit regressions.
See project handoff for the actual final test counts and commit/publication state.
No historical option replay, profitability/win-rate result or production fill is
claimed. Deploy only the reviewed, tested commit through the normal app-only
release procedure. Deployment and activation remain separate user actions.

## Current-session candles and diagnostics (26 October 2026)

Sharekhan's historical endpoint explicitly supplies candles **up to the last traded
 day**. It cannot supply today's session. For this strategy only, historical
raw `tradeTime` values use right-closed `(start,end]` intervals: `09:04:57`
becomes `09:00:00`, and an exact `13:00:00` belongs to `12:55:00`. The strategy
uses the raw history service to avoid the shared legacy five-minute rewriting. The incomplete stub at/after 23:30 is excluded. Equity candle
normalization is unchanged. Duplicate intervals and invalid OHLC still block signals.

An active subscription now retains its exact resolved MCX futures full feed before
09:00. The WebSocket connection can open from 08:55 for active Natural Gas
subscriptions; the normal equity connection start stays 09:10. The collector accepts
only armed scrip codes and `exchangeCode=MX`, parses the documented
`lastUpdatedTime`, and rejects timestamps older than 15 seconds or in the future.
Five-minute candles use observed prices and differences of the provider's cumulative
`qty`. Repeated snapshots do not add volume. Session-wide OHLC is used **only** to
seed the first interval when the first valid snapshot arrives within 15 seconds of
09:00. It is never reused as a later interval's OHLC.

Only completed intervals with observations on both sides of the close and no unexplained
gaps longer than 15 seconds are retained (verified quiet-gap accounting is described below). Reconnects, decreasing quantity counters,
malformed observations and late subscriptions invalidate partial intervals. No
missing interval is manufactured. Sampled/conflated quotes cannot guarantee every
exchange trade or intrabar extreme was observed; Simulator evaluation must be
reviewed against the broker chart before live use.

Completed live intervals survive restarts in the existing `strategy_candle_history`
table, under `SHAREKHAN_MCX_LIVE:<scrip>`. Retain at most 1,000 loaded bars and prune
older than 14 days as candles complete. No new schema is required. An incomplete
bar is deliberately discarded on restart. Live completed candles take precedence
at matching historical timestamps and are read without the five-minute historical
cache delay.

Waiting messages distinguish absent current-session data, missing 09:00 capture,
gaps, stale candles, warmup, missing volume, trend disagreement and an absent retest.
Data waits also show the latest valid live observation time and cumulative quantity.
`MCX_LIVE_CANDLES` / `MCX_LIVE_CANDLE` logs provide market-data-only capture evidence.

Installing after the open cannot recover missed current-day intervals from this
historical endpoint. The strategy continues to refuse entry until it has a complete
session from 09:00, sufficient prior history, valid volume and a qualifying setup.
The 24-bar warmup, one-attempt/day guard, max-loss settings and order/exit rules are
unchanged. NYMEX is not part of this repair.

Live verification found `lastUpdatedTime=10/26/2026 11:30:56` with cumulative
`qty=11378`. WebSocket dates therefore use **MM/dd/yyyy**, independently of
historical `tradeDate` (dd/MM/yyyy). The live parser prioritizes month/day for
ambiguous slash dates; ISO offsets are converted to IST and Unix timestamps are
accepted. The 15-second freshness check remains mandatory for every format.

## Provider verification, 26 October 2026

Repeated actual Sharekhan requests returned 7,922 one-minute, 1,654 five-minute
and 558 fifteen-minute records, ending 23 October; none contained today. Applying
right-closed minute buckets, all 1,643 in-session five-minute and all 548
fifteen-minute records matched aggregated minute OHLCV exactly. Six apparent
five-minute duplicate intervals were boundary assignments, not duplicate trades.
For example, 19 October 13:00:00 volume 65 belongs to 12:55; 13:04:56 volume 38
belongs to 13:00. Prices and volumes are preserved; conflicting bars are not summed
or silently discarded. Equity/shared consumers retain their prior normalization.

Admin-only GET diagnostics under `/api/sharekhan/historical`: `raw-candles`
accepts `scripCode` and `interval`; `mcx-capture` accepts `scripCode` and reports
rejection counts, current incomplete-bar reason and at most 20 market-field
samples (receipt/message/provider clocks, last price and cumulative quantity).
Neither endpoint arms a feed or submits orders. The initial diagnostic release preserved all rejection rules; the subsequent
observed quiet-trade correction is described below.

Live verification at 15:56:30–15:56:59 found a 29-second gap but cumulative
quantity increased by exactly one contract (23,230 → 23,231), with a fresh matching
last-trade clock. Quiet gaps can be accounted for when the current last-trade
quantity equals the entire positive cumulative delta; if `ltq` is absent, only
a one-contract delta is sufficient. `ltt` must equal the fresh provider timestamp.
An unchanged counter also accounts for silence only when price is unchanged and
last-trade time has not advanced. This permits a known late first trade across a
bar boundary without assigning its price/volume to the preceding bar. Conflicting
or missing trade clocks, unexplained multi-trade deltas, stale snapshots, resets,
disconnects and genuinely missing intervals still invalidate capture. The shared
feed is sampled; this accounting does not certify exchange-complete intrabar OHLC.

Post-rollout raw-history verification matched all **1,644** in-session five-minute
records, including 14 October's valid 23:30:00 end stamp (volume 121), which maps
to 23:25. The initial 1,643-record comparison had excluded that exact close before
normalization. Zero duplicate intervals remain.
