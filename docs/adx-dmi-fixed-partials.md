# ADX / DMI fixed partials — Simulator

Template `ADX_DMI_FIXED_PARTIALS` accepts the existing required `symbol` input.
There is no NIFTY/SENSEX symbol whitelist or forced symbol value. Resolve the
actual underlying from the instrument master; NC spot maps to NF options and
BC spot to BF options. A listed matching option contract is required. The
underlying tick size and option lot size come from their respective metadata.
Select an active owned Simulator account. Configure two separate subscriptions
for the requested NIFTY and SENSEX instances, three lots each, intraday. These
are subscription inputs, not special strategy cases. Symbols have independent
position locks and can trade concurrently. CE/PE share one position per
account/symbol across subscriptions. No live broker order interface is used.

## Frozen rules

- Standard completed regular-session five-minute spot candles, IST start stamps.
- Wilder DMI(14), ADX smoothing 14: ADX >25, dominant DI >25, opposite DI <20.
- CE: next candle crosses signal high +one metadata tick; PE: signal low −one metadata tick. Signal expires
  after that next five-minute candle. Repeated qualifying candles may re-arm it.
- Initial stop is the opposite signal extreme. Actual observed spot at entry
  determines R; targets rounded to the underlying metadata tick at 1R, 2R, 3R. One option lot each.
- The original spot stop stays unchanged after every partial exit.
- Morning entries only, 09:20 <= time <12:00 IST, matching the selected Pine
  default. Replay can disable morning-only to test the separate 14:50 cutoff.
- No new signal from a candle containing an entry/exit fill, or completed while
  another position was open. Persisted signal key prevents duplicate entries.
- Remaining positions trigger square-off at 15:20. Exit triggers persist until
  a fresh executable option book is available; missing books can delay actual
  exits past 15:20. No fabricated prices or guaranteed execution deadline.

Simulator entries buy the nearest listed expiry's actual ATM contract at ask,
requiring displayed depth for three metadata-sized lots. Each persisted leg
sells bid at its own target/stop. Closed legs are never recreated. A database
account lock serializes admissions; subscription/run locks prevent entry after
cancellation or Close All. Manual/run exits route through the same paper path.
Generic entry/exit paths cannot submit orders for this reserved source.
Displayed bid depth already used by these strategy legs since that book update
is deducted before another leg fills. There is no exchange queue simulation.

The stop and targets are **underlying spot levels**, matching Pine. `0.5 × R`
is recorded as an option-risk estimate, not an actual premium stop. Option
prices, spreads, IV, theta and fees make option P&L different. The existing
application fee model is an estimate and may allocate entry fees across the
three paper records differently from a broker's combined entry ticket.

## Indicator history and data identity

The existing application's history service retains up to 250 completed bars
and 14 calendar days. A minimum of 200 bars is required. The simulator and
replay default use the same rolling 250-bar RMA initialization; no daily reset.
Insufficient/stale history or wrong exchange/provider identity waits for data.
The shared existing weekday/configured-holiday calendar is used, including
for SENSEX; actual fresh completed session bars and quotes are still required.
No new data source, broker session refresh, schema, import or background job is
introduced beyond this template's evaluation loop.

Pine uses all chart-loaded history, including TradingView index pre-open bars.
The application history intentionally filters before 09:15. Saved DHAN spot
history is a different feed from application MStock/Sharekhan and TradingView.
Numerical/entry parity must not be inferred from matching indicator formulas.

## Backtesting

Existing admin-token protected endpoint:
`POST /api/backtests/csv/datasets/{id}/adx-dmi-fixed-partials`
with JSON `{"from":"2026-06-01","to":"2026-10-31","morningOnly":true,"tickSize":0.05}`.
Existing dataset upload accepts a symbol input too; this does not make other
strategy engines support every symbol. The endpoint returns an in-memory underlying proxy
report/leg ledger and does not create orders/subscriptions. Standard session
security and CSRF behavior remain unchanged; no-store response header.

Offline CLI (Java 21, no Spring/database/broker):

```
java -cp "target/classes:<dependency-classpath>" \
  org.com.sharekhan.strategy.adxdmi.AdxDmiBacktestMain \
  --file nifty-minute.csv --input-minutes 1 --symbol NIFTY \
  --from 2026-06-01 --to 2026-10-31 --execution tv5 \
  --indicator-history rolling250 --tick-size 0.05 --output-dir results
```

Replay tick size defaults to 0.05 and must be supplied correctly for the input
instrument; live Simulator evaluation uses the underlying master tick instead.
`--execution minute` uses the saved one-minute sub-bars, still five-minute
signals. `--indicator-history continuous` uses all prior input bars for the
Pine-style seed. OHLC execution follows TradingView's documented emulator path:
open/high/low/close if open is nearer high, otherwise open/low/high/close;
equal distances use low first. Gaps fill at open, targets re-anchor to entry.
Missing square-off bars cannot roll an intraday position into a new session.
Open legs at dataset end are reported, not silently discarded or closed.

All P&L is the sum of three **underlying proxy units**, not option rupees.
Divide by three for P&L per original total unit; this is not actual lot sizing.
Drawdowns are labelled realized-cycle and marked-equity, not claimed identical
to TradingView's drawdown convention. Output includes every leg and decision.

## June–October 2026 evidence

See `outputs/adx-dmi-autotrader-20261110/report.html` in the parent workspace.
106 complete saved sessions, 39,750 scored minute bars, prior May warmup,
no overlapping source conflicts. Continuous and rolling-250 default trade
ledgers match on this sample; this does not prove parity for other datasets.
Initial full period gross: 165 entries, +617.75 points, PF 1.078 with five-minute
OHLC execution; minute execution 167 entries, +470.85 points, PF 1.059.
June loses and October supplies most profits. No parameter optimization here.

TradingView Basic exposes August 3–November 9 only. With entries restricted to
June–October, its August/September/October cycles are 22/28/29 and gross results
+503.45/+107.00/+2074.45. Saved regular-session replay gives 33/32/37 and
+233.15/−95.60/+1446.50. **Not matched.** On October 1 at 09:15, OHLC differs
(open 22558.20 vs 22543.70; close 22556.45 vs 22556.05), and +DI 24.152556 vs
13.638007 changes the PE admission test. TradingView also starts with a 09:05
bar and includes pre-open history, absent from saved/app regular-session bars.
These verified differences preclude a same-data fill-parity claim. TradingView
June/July and complete historical option results are unavailable here.

## Release state

Prepared locally on `codex/nifty-adx-dmi-fixed-partials`, based on main
`d3c9024`. No deployment or subscription activation. Production SSH preflight
was rejected by automatic approval review as lacking explicit server access
authorization. No retry/bypass or production changes occurred. Release must
verify current main/server and exact NIFTY/SENSEX masters, snapshot settings
and backup, publish/review the branch, deploy only the app, verify health, then
create only the two intended owned Simulator subscriptions through normal UI.
SENSEX historical profitability and forward paper execution remain unverified.
