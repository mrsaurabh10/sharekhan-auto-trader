# Weekly technical context with a 200-day reference

Stock Analysis → Weekly technical provides saved Nifty 200 stock selection,
weekly OHLC/volume SVG chart and accessible data table, 20/50-week SMAs and a
**200-day SMA**. The user explicitly requested replacing the upstream 200-week
reference with 200 days. This shorter horizon is independently labelled and
calculated; it is not presented as an equivalent to 200 weeks.

Authenticated USER/ADMIN GET `/api/stock-analysis/weekly?symbol=RELIANCE` and
`/api/stock-analysis/weekly/symbols` are no-store. Reports use read-only,
repeatable-read transactions and a per-symbol 60-second cache. Only current saved
Nifty 200 symbols and Dhan NSE_EQ candles are accepted. No live feed, broker call,
order, new job, setting or schema is introduced. Dhan IDs are not order identities.

## Selected methods and implementation boundaries

This is a deterministic adaptation of selected methods from TraderMonty's
MIT-licensed `technical-analyst` framework ([license](licenses/tradermonty-technical-MIT.txt)).
It generates charts from saved candles rather than claiming an AI/manual image
interpretation. Trend, averages, horizontal zones, volume and conditional scenario
boundaries follow declared rules. It does not detect every chart pattern, draw
subjective trend lines, estimate calibrated probabilities or provide trade plans.

- Weekly buckets run Monday–Sunday, including the verified 1 February 2026 Budget
  Sunday in that week's bar. First archive week is always excluded to avoid
  treating a partial listing/import week as complete. Unfinished weeks are omitted.
- Every expected 2026 session in a closed week must exist, or that week is excluded.
  Gaps break weekly moving-average continuity rather than compressing time.
  Research calendar covers 2026 only. Older weeks aggregate observed sessions;
  missing historical sessions/holidays cannot be certified. Their averages inherit
  this limitation, explicitly shown in the app. Unsupported specials fail closed.
- All latest indicators share `weeklyThrough`: weekly averages use exactly 20/50
  consecutive weekly closes; daily SMA200 uses exactly the last 200 saved daily
  closes ending on that week's final session. Unfinished-week daily prices are
  excluded from all weekly context. Insufficient history yields null, never a
  shorter substitute. Four-week changes provide 20/50-week slopes.
- Trends require two confirmed higher highs AND lows (or lower highs AND lows),
  weekly close position versus all three averages, 20/50-week ordering and slope
  direction. Mixed/range means criteria disagree or are incomplete, not a signal.
  Confirmed pivots use strict highs/lows with two consecutive closed weekly bars
  on both sides over the last 52 weeks. Latest two bars cannot confirm a pivot.
- Zones cluster pivot prices within 1% of the lowest cluster price; bounds use
  observed minimum/maximum expanded by 0.5%. Touches count distinct pivot weeks;
  a single touch is explicitly weak. Latest price determines support, resistance
  or testing role; nearest fully below/above zones are shown. No invented fallback
  levels. Range/upside/downside scenarios use these boundaries without assigned
  probabilities or buy/sell instructions.
- Total weekly volume is shown; context ratio compares volume per observed session
  with the previous ten consecutive weeks, reducing holiday-length distortion.
  This is share volume, not institutional buying/selling flow.
- Valid/current OHLCV, cash identity/tick, positive recent volume and all latest
  20 sessions required. Conflicting duplicates, >30% discontinuities and malformed
  input fail closed. Future bars are excluded before aggregation. Missing chart
  data does not fabricate levels. Corporate-action adjustment remains UNKNOWN;
  current membership is not a point-in-time historical universe.

Company/news/earnings, portfolio, costs and real-time execution review remain
separate from pure chart context. Existing Momentum/VCP/Hammer risk and market
entry gates are unchanged; weekly context does not activate a strategy or place
orders. Selected-method inventory advances to seven of 72 skills.

## Verification

Tests cover exact daily/weekly average bases and cutoffs, holiday and Budget
session aggregation, unfinished weeks, known gaps, short listings, no lookahead,
OHLCV/stale/conflicting/discontinuity failures, pivot-zone evidence, per-session
volume, aligned up/down trends, symbol validation/cache and HTTP security.
Browser fixtures replay saved RELIANCE/VAML data and check labelled averages,
chart/table, 200 symbols, unavailable history, mobile, safe text, failures and zero
writes. Release evidence records actual runtime results separately from local UI
checks; no historical profitability or full upstream-skill certification is claimed.
