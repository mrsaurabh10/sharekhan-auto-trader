# VCP research in Stock Analysis

`GET /api/stock-analysis/vcp` is authenticated USER/ADMIN research, backed only by
saved PostgreSQL market data. Native Java adapts selected methods from the installed
MIT-licensed `vcp-screener`; it does not execute Python/FMP or claim exact upstream
replication. Upstream attribution: [TraderMonty trading skills](https://github.com/tradermonty/claude-trading-skills); see [MIT notice](licenses/tradermonty-vcp-MIT.txt). New classes have no broker, order or authentication dependencies.
The report runs in a read-only repeatable-read transaction and caches for60seconds.
No database schema, historical feed, trading setting or scheduler is added.

## Input and benchmark policy

Use the current saved Nifty200 snapshot and Dhan cash daily bars, INR/share and
IST dates. At least253 completed bars support all four RS horizons and the trend
checks. Identity, tick₹0.01–₹5, OHLCV, conflicting dates, recent20sessions, positive
recent volumes, nonregular2026sessions and >30% close discontinuities are checked.
Future bars are excluded before every indicator. The verified1Feb Budget session
is admitted in stock history; other unverified special sessions block the stock.
Unknown adjustment and historical constituent/calendar limits remain explicit.

The benchmark is the **derived last regular-minute NIFTY50 reference**, Dhan IDX_I
security13, not the official exchange EOD close and not the Nifty200 index. It
uses existing native1-minute data only; recent legacy MStock5-minute data are not
mixed in. A reference requires all375 unique clock slots09:15–15:29, valid OHLC,
source interval1, bar/expected counts1 and quality `NATIVE_5M_WINDOW_CONFIRMED` or
`NATIVE_PAIR_VALIDATED`. Flagged, partial, wrong-identity/provider/interval and
nonregular sessions are excluded. Missing whole dates are unavailable, not filled.
Older dates are checked for complete regular clocks, not a fully audited calendar.
Stock daily closing and index last-minute closing bases can differ.

RS equals weighted stock return minus benchmark return in percentage points over
63/126/189/252 stock sessions, weighted40/20/20/20. Benchmark endpoints must match
those actual stock dates and the current date. All horizons are mandatory; there
is no shortened-period fallback. The percentile uses eligible stocks in this saved
sample, with ties given the same midrank. It is not a vendor/all-market RS rating.

## Pattern and trend policy — NSE_VCP_CONFIRMED_SWINGS_V1

- Pattern window:120completed bars. Confirm swing highs/lows only after1.5ATR14
  reversals; ATR uses Wilder smoothing. Unconfirmed final extremes are omitted.
  The top3 confirmed highs are candidate starts; choose a valid pattern first,
  then the one with most contractions. No fixed-window fallback or upstream
  composite-score/ratings claim is made.
- Up to4 chronological high/low pairs, each lasting≥5sessions; no crossing an
  intermediate high to rescue a too-short contraction. Subsequent highs must
  lie within5% of the initial high.
- Valid structure:≥2contractions, first depth10–35%, every following depth≤70%
  of the preceding depth, totalspan≥15sessions, final depth≤10%, last low at most
  15sessions old. These additional conservative bounds differ from upstream.
- Seven trend checks: price>SMA150/SMA200, SMA150>SMA200, SMA200 above its value
  22sessions ago, price>SMA50, price≥25% above252-session low, price within25%
  of252-session high, and sample RS percentile>70. The252-session range is a
  proxy for52-week extremes. Developing trends require6/7; entry requires7/7.
- Pre-breakout means close within5% below pivot. Breakout means close at most2%
  above pivot with current volume≥1.5×prior50-session average. Above pivot without
  volume confirmation, extended prices or damaged structures never get entry plans.
- Prior10-session/prior50-session volume dry-up excludes the potential breakout
  bar and must be≤0.7. Entries additionally require positive weighted excess,
  bullish close and20-day average turnover≥₹20crore.

## Plan and report gates

Trigger=pivot+max(tick,0.1ATR), maximum entry=trigger+0.25ATR, rounded up. Stop=last
contraction low−0.25ATR rounded down. Risk uses maximum entry, must be positive and
≤8%, and maximum entry≤pivot×1.02. Prices already above maximum entry are blocked.
Projected sell exits T1/T2 are2R/3R from maximum entry, never short entries.
Entry expires next verified regular session15:15IST; skip gaps above maximum or
stop breach before entry and review holding after10completed sessions. News,
earnings, portfolio, costs and executions are separate; no fills/P&L are inferred.

Publishing plans requires200members, at least160valid data/RS stocks, current
benchmark reference, ≥50% of covered stocks aboveSMA50 and a verified next regular
session. Stock-qualified pre-market-gate plan counts are reported independently.
All plan prices are withheld under a failed report gate, while watch evidence stays
visible. A structural match with weak trend is not labelled a qualified trade.

The page has dedicated entry/sell-price and evidence tables, all-stock reasons,
swing dates/depths and each trend check. Jump links expose all research sections.
The skill inventory now lists selected methods from5skills;67remain unintegrated.

## Validation — 2 November2026

61focused Java tests passed, including benchmark completeness/source/quality,
confirmed contractions, aligned-horizon/no-fallback RS, tied ranks, future-price
isolation, bad inputs, cache, market/benchmark gates and authenticated reads.
20adapter and10Node sizing tests passed. Browser tests cover real saved-data
rendering,200-stock reasons, evidence, synthetic prices, expiry, missing benchmark,
failed reads, mobile layout, XSS-as-text and zero writes.

Read-only production preview on30Oct2026data:193/200stocks with253bars and all
RS horizons,45/193aboveSMA50 (23.32%),404complete benchmark sessions10Mar2025–
30Oct2026,2structural matches and32developing setups (34watch rows).0stock-level
entry candidates,0published plans, MARKET_CAUTION. Six short histories (GROWW,
ICICIAMC,LENSKART,MEESHO,TMCV,VAML) and POLICYBZR discontinuity remain unavailable.
These are dated observations, not evidence of profitability or an execution record.
