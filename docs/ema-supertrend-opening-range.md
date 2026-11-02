# EMA 10/20 + SuperTrend opening range (intraday research)

Source: user-supplied summary of Dhan's video
https://www.youtube.com/watch?v=UX1T-G93g-s (Dr. Priyanka Sharma), 2 November 2026.
Playback required sign-in; the summary, rather than a recovered transcript, is the
implementation source. This is a separate research strategy, not a change to the
existing ORB, ST/RSI/ADX, or live subscription rules.

Hypothesis: a fresh opening-range breakout with aligned EMA and SuperTrend
momentum may follow through sufficiently to offset a stop at the opposite range edge.

## Exact rules and assumptions

* Asia/Kolkata, start-labelled input bars. Aggregate complete five-minute groups to
  15-minute candles. The opening range is exactly 09:15–09:30, including wicks.
* EMA10/EMA20 use SMA seeds and close prices. An observed crossover establishes
  direction until the next opposite crossover. Seeded alignment alone is insufficient.
  Equality is neutral. Indicators may retain prior-session history; positions never do.
* SuperTrend defaults to Wilder ATR10 ×3, matching the existing IndicatorService.
  Period/multiplier are configurable. The video summary does not specify these settings.
* After the opening range, require a fresh completed 15-minute close above its high
  (long), or below its low (short), EMA crossover direction and ordering aligned, and
  close strictly on the matching side of SuperTrend. Conflict means no trade.
* Execution assumption: close confirmation, then the next contiguous five-minute open;
  skip if that open is back inside the range. Earliest possible entry 09:45 IST.
  This differs from an unconfirmed intrabar stop-entry interpretation of "above/below".
* Stop = opposite opening-range edge, fixed. Target = entry ± at least 2 × entry-to-stop
  risk. Default 2R; no partial exits, breakeven, signal-flip exit or trailing stop is inferred.
* One position per index at a time; default maximum two entries per index per day,
  shared across long/short, configurable to one. Re-entry requires a fresh range
  crossing after a close returns inside. No entry at/after square-off.
* Purely intraday: default square-off at the **15:20 IST open**, and daily entry counts
  reset each session. Configurable five-minute square-off only 09:45–15:25.
  No position survives the session; there is no overnight/following-day exit fallback.
* Exits checked in five-minute OHLC: square-off open, stop gap at open, target gap at
  target (no favourable improvement), stop touch, then target touch. Stop first when
  both touched. Entry-bar extremes belong after its opening fill; exit timestamps
  identify the bar, not a known intrabar fill time.
* Slippage is adverse on both fills; target risk uses slipped entry. Round-trip costs
  subtract once. These point assumptions are not dated broker fees or bid/ask evidence.

Only exact full 75-bar weekday sessions (09:15–15:25) are accepted. Rejected observed
sessions reset indicator history; gaps over seven calendar days also reset it. Missing
unobserved holidays/trading sessions are not inferred. The complete-day criterion is
an ex-post research data-quality selection, not a live availability claim. Causal
indicator recurrences are precomputed per day but each decision consumes only the
completed prefix. All input OHLC/grid/order/duplicate validation fails closed.

## Use

Offline main class: `org.com.sharekhan.backtest.openingrange.OpeningRangeBacktestMain`.
Pass `--file PATH --symbol NIFTY|BANKNIFTY --input-minutes 1|5 --output-dir PATH`.
CSV header: `date,open,high,low,close,volume`; timestamps `yyyy-MM-dd HH:mm:ss` IST.
Optional: `--from`, `--to`, `--st-period`, `--st-multiplier`, `--target-r` (>=2),
`--max-trades` (1 or 2), `--slippage-points`, `--cost-points`, `--square-off`.
Outputs are JSON report and CSV trade ledger with frozen range/indicator evidence.

App endpoint, reusing an already imported CSV dataset:
`POST /api/backtests/csv/datasets/{id}/ema-supertrend-opening-range` with JSON `{}`
for defaults. Optional JSON fields: `from`, `to`, `supertrendPeriod`,
`supertrendMultiplier`, `targetR`, `maxTradesPerDay`, `slippagePoints`, `costPoints`,
`squareOff`. Existing X-Admin-Token guard is required; unset/incorrect tokens fail closed.
This reads the dataset in a read-only transaction and does not persist replay trades.
The existing dataset import endpoint writes datasets only when explicitly invoked.
No schema, UI, strategy catalog, live evaluator, scheduler, broker order path, deployment
or subscription is added. An offline study is not an activated live futures strategy.

## Validation on retained project data

Reused the saved 2 November native Dhan **spot index** export, IDs IDX_I 13/25;
no new broker requests or substitutions. Full sessions accepted: Nifty1174,
Bank Nifty1184, observed November2021–October2026 with exclusions/gaps. These are
spot-proxy price points per unit, not futures/option fills or rupee/account P&L.
Futures expiry, roll, basis, lot size, margin, exchange tick and dated fee assumptions
are not available. No futures profitability conclusion follows from this proxy.

Default gross: Nifty915 trades,51.15% wins,+4850.40points,PF1.161;
Bank Nifty893,46.81%,+843.41points,PF1.010. Nifty friction (1point/fill+2cost)
+1336.75points, stress (2+4)−2167.00. Bank friction (3+6)−9933.69,
stress (6+12)−20410.47. Both have negative gross2026 results. Ten declared variants
per index cover friction,one-trade limit,STperiod7/14,multiplier2/4,target2.5/3.
They are sensitivity checks without optimization or an out-of-sample claim.
The result does not support live-futures adoption; validate contract-level data first.

23 focused Java tests pass:12engine,4controller,3DBservice,2CSVreader,2existingSTreplay.
They cover direction symmetry,exact2R,first-range stops,stop-first/gap handling,
friction,cutoff/daily limits/reset,incomplete sessions,conflicts,causal indicator prefixes,
API token/default/error controls,and stored-data reuse without bar changes.
Detailed local study: parent `outputs/ema-opening-range-20261102/report.html`,
trade/report JSON/CSVs,yearly/sensitivity tables,input hashes,and independent audit.
Build on this machine uses the existing cached Maven repository and
`-Dlombok.version=1.18.42` for its JDK25; target bytecode remains Java21.
