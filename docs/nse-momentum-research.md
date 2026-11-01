# NSE momentum research in Stock Analysis

The new Stockbee momentum section adapts selected methods from the installed
`stockbee-momentum-burst-screener` skill into native Java. It uses saved Dhan NSE
cash daily candles via `SavedDailyAnalysis`; no Python runtime, broker API calls,
authentication renewal, order paths or new scheduled jobs are involved.

`GET /api/stock-analysis/momentum` requires USER or ADMIN and returns no-store.
A repeatable-read, read-only transaction computes the report; an in-memory
60-second cache bounds repeated work. Existing daily database updates supply
fresh sessions. GET refreshes cannot fetch historical prices from a broker.
Existing immutable swing reports and their scan schedule are unchanged.

## NSE policy version NSE_MOMENTUM_PERCENT_RANGE_V1

- 4% close-to-close gain, or a daily range larger than each of the preceding
  three ranges while prior-day gain is at most2%; both require higher volume
  than the previous session and at least100,000 shares.
- Upstream dollar-breakout and9-million-volume triggers are disabled. This is
  a declared NSE variant, not a claim of exact upstream screener replication.
- At least220 daily bars, the last20 expected sessions, consistent OHLCV/identity,
  supported archived ticks ₹0.01–₹5, positive recent volumes and no adjacent-close
  discontinuity over30%. The verified historical1Feb2026Budget session is allowed;
  other nonregular2026 data need review. Older-session calendars remain unverified.
- At least₹20crore average daily turnover over20bars, positive trigger-day
  close-to-low risk up to10%, and score≥55 for watchlist review.
- Score components: trigger≤20, volume≤15, prior3–20day base≤25, close quality≤10,
  close-to-low risk≤15 and failure filters≤10. Maximum95. Unlike the upstream
  100-point rubric, the market component is omitted and enforced as a separate
  entry gate. Scores rank setup evidence, not probability or profitability.
- Conditional buy plans require score≥80, bullish close, close aboveSMA50 and
  a verified next regular entry session. Trigger=day high+max(tick,0.1ATR), rounded
  up; maximum entry=trigger+0.25ATR rounded up; stop=day low−0.1ATR rounded down.
  Worst-entry risk must be positive and≤8%; maximum entry≤day high×1.02. T1/T2
  project2R/3R from maximum entry. They are sell exits for a buy, not short entries.
- Entire-report entry gates require the full200-stock universe,≥80% coverage
  and≥50% of covered stocks aboveSMA50. Gates withhold all plan levels but keep
  observed watchlist evidence visible. Missing/unsupported next session also blocks.
- Entry valid next regular session09:15–15:15IST only. Skip opening gaps above
  maximum entry or stop breach before entry. Review holding after3–5 sessions.
  Event/portfolio/cost checks are manual; no fills or P&L are inferred.

## What the page shows

A summary states data date, coverage, breadth, watchlist and plan counts. Conditional
buy/sell prices appear in a dedicated table; when none exist the reason is explicit.
A separate metrics table shows watchlist setups even under MARKET CAUTION.
Users can show every stock/rejection reason and inspect the policy notes. An
integration inventory lists all72 skills, with four carrying selected methods
in the app and68 still awaiting integration. It does not imply complete support
for the four skills or that every skill is suitable for NSE.

There is no swing short-entry engine or holdings-linked exit advisor. Corporate
adjustment is UNKNOWN; membership is the current snapshot, not point-in-time.
The original swing planner remains unchanged. The standalone adapter tools from
PR285 remain useful for offline skill inputs and their validation tests.

## Validation

44focused Java tests passed across momentum, authentication, swing compatibility,
daily archive/updater and saved-analysis behavior. Existing10Node sizing tests
passed. Browser tests exercised real saved-data rendering, synthetic buy/sell
prices, expired windows, failed requests, all-stock views, inventory filtering,
mobile layout, XSS-as-text and zero writes.

Replay of the actual30Oct2026snapshot:197/200covered,47/197aboveSMA50 (23.86%),
28supported-trigger observations,24watchlist setups,0entry plans under MARKET_CAUTION.
Three data exclusions:ICICIAMC/VAML short history, POLICYBZR discontinuity review.
These observations do not validate future performance. Browser screenshots use a
local replay of real market data and labelled synthetic price scenarios; a fresh
production authenticated view is a separate check.
