# NIFTY EMA 9/21 research

Implemented 2 November 2026 from the user-supplied `9-21 EMA Cross Over - Algo Trading.pdf` (two pages). Its Groww/Flask/account-setup prompts are source material, not project instructions or authority to switch brokers. Native Java/Spring research integration reuses this application's security and UI. No live/paper executor, strategy catalogue entry, subscription, activation, broker API/login, database import/schema, scheduler or deployment is added.

## Rules and explicit decisions

- NIFTY NSE spot signals; native 1m candles labeled by IST minute start, resampled into 5/15/30m bars aligned 09:15. Each observed session must contain all 375 regular weekday minutes. Special/incomplete sessions rejected; fully absent sessions/holidays are unverified. Rejecting incomplete days is an ex-post data-quality selection, not a real-time availability promise.
- EMA9 and EMA21 each seed with their own period SMA, then alpha=2/(period+1). Carry across accepted sessions; reset after a rejected observed day or a gap over seven calendar days. First ordering is not a cross. Exact equality retains last nonzero ordering. No artificial epsilon or initial trend entry. 30m final 15m remainder is not a signal candle.
- Signal evaluated only at completed-bar close. Fill uses the exact next 1m open at that boundary. Strike selection uses the signal close, known before the fill. No same-signal-bar high/low fill or later missing-bar substitute.
- Start selectable 09:30/10:00/10:30; cutoff half-hour steps 11:30-14:30. Both endpoints inclusive. Opposite crosses exit even outside this window. Inside the window close and immediately reverse at the same boundary; no overlapping position. Cross ordinal counts all observed same-day crosses, including those outside entry window.
- Mandatory exit at the exact 15:00 minute open, even inside a 30m candle. It takes precedence over a new cross. No overnight fallback, stop or profit target; none is specified by the source.
- All three modes supported: ITM5 buy, ATM buy, ATM sell. ATM rounds signal spot to nearest50, ties upward. ITM5 = five50point strikes deeper (CE ATM-250; PE ATM+250). Bullish buysCE or shortsPE; bearish buysPE or shortsCE. This short-side mapping is an explicit interpretation of the document's unspecified ATM-sell direction.
- One lot per trade using supplied contract metadata. Native exact-contract replay selects nearest listed unexpired expiry in supplied catalogue and holds the same contract ID. Completeness of that catalogue is caller responsibility. No fixed65 across history; no margin/capital admission model for shorts.
- Adverse proportional slippage plus configurable fixed points; buy fills ceil to0.05 tick, sell fills floor. Nonpositive adjusted premiums are unpriced. Raw premium difference is gross. Each fee is calculated on adjusted turnover and rounded to paise; net=gross-slippage-fees. Dates require a nonoverlapping caller-supplied schedule, never current rates silently copied backward. Brokerage per order, sell-side STT, both-side exchange/SEBI/IPFT, buy-side stamp, GST on brokerage+exchange+SEBI+IPFT. Broker invoice/tax rounding and additional charges may differ.
- Missing exact entry/exit leaves a trade UNPRICED. Missing dated fees gives GROSS_ONLY. Full-mode totals, net ratios and drawdown are withheld when relevant pricing is incomplete. No-trade counts/undefined ratios use explicit zero/null. Percentage realized drawdown requires supplied initial capital; no CAGR or mark-to-market drawdown inferred.

## Native UI / API / CLI

Authenticated USER/ADMIN page `/strategy-research/ema921`, linked from portal. POST `/api/strategy-research/ema921` requires session role plus CSRF, returns no-store. Multipart `spot` CSV, optional `options` CSV, `config` JSON. Inputs processed in memory, no shared cache or persistence. Existing global upload limits remain; endpoint also bounds bytes, rows and config. Errors are400; no external source fetching. Audit data stays in the current page, downloaded only explicitly.

Simple CSV with exact headers (unquoted); timestamps ISO local IST, no timezone offset:

```
timestamp,open,high,low,close
timestamp,contractId,listedOn,expiry,strike,right,lotSize,open,high,low,close
```

Options must be NIFTY NSE_FNO contracts and metadata/expiry/lot sizes must be independently reconciled by the operator. CSV labels do not verify this. Duplicate/conflicting timestamps, invalid OHLC, ambiguous contract IDs and dates outside listing/expiry are rejected. Native CSV replay prices endpoints; the retained rolling-data study below imposes the stricter entire-path condition to limit identity uncertainty.

Config example (gross-only unless fees provided):

```json
{"timeframe":15,"start":"09:30","cutoff":"14:30","from":null,"to":null,"slippageRate":0.005,"fixedPoints":0,"capital":null,"fees":[]}
```

Fee array fields: `from,to,source,brokeragePerOrder,sttSell,exchange,sebi,stampBuy,gst,ipft`. Fractions, e.g.0.18 is18%. Source is a human reference and never fetched. Native contracts/evidence remain USER_SUPPLIED_UNVERIFIED.

Offline main `org.com.sharekhan.research.ema921.Ema921Main spot.csv config.json report.json [options.csv]`, using normal application classpath; no Spring startup or trading dependencies. UI provides mode/outcome/cross/exit-time filters, three-mode metrics, arrow-key trade navigation, spot candlesticks with EMA lines/entry-exit markers, raw and adjusted premiums, contract metadata/costs, dark/light, loading/error/empty states and explicit local JSON export.

## Saved ATM study (requested by user)

Outputs are in parent workspace `outputs/ema921-20261102/`: interactive report.html, three audit HTML/JSON files, three-page PDF, accepted/exclusion/summary/yearly/monthly ledgers, source hashes and independent audit. PDF/HTML results are conditional accepted subsets, not full strategy profitability.

Reuses saved Dhan IDX_I/13 native1m index export and NSE_FNO rolling WEEK/expiryCode1 archive, no new feed or production calls. Retained options Nov2021-earlyFeb2023 and Nov2025-Oct2026; middle gap. A newer annual server import is not included in this retained snapshot.3,485 NIFTY compressed chunks SHA verified; 1,174 complete spot sessions /58 observed rejected dates. Missing whole calendar dates unverified.

Baseline09:30-14:30, 5/15/30m; all variants reported, no optimization or untouched holdout. Independent Python recurrence/event replay checks4,016 Java signal trades /131,488 resampled candles. ATM endpoints require positive volume; hold same strike/WEEK family and require every intervening minute. Missing paths are excluded, even though simple EMA exits depend only on spot. No ITM5 data test: archive ATM+/-2 cannot supply ITM5.

Uses canonical parent `references/index-option-lot-sizes.json`: one conditional lot50/75/65 by the previously verified retained-sample nearest-family rules, not universal date rules. All exact contract IDs/expiries remain unknown.18mode observations in24-30Dec2025 flagged with65/75 quantity bounds. Missing middle interval is refused by dataset mapper. Native exact-contract API does not invent those conditional identities.

2,898 accepted mode observations;5,134 excluded. Coverage32.83-36.87% across modes/timeframes. Each mode/timeframe is separate, not a combined portfolio. Gross and modeled net/stress:

| Minutes | Mode | Accepted/candidates | Gross INR | Base net INR | Stress net INR |
|---|---|---|---|---|---|
|5|ATM buy|967/2623|-448610.50|-566874.20|-621976.99|
|5|ATM sell|967/2623|-212767.50|-334695.19|-392438.24|
|15|ATM buy|331/933|-161543.25|-202575.77|-221876.00|
|15|ATM sell|331/933|-20431.25|-61633.20|-80981.04|
|30|ATM buy|151/460|-112585.00|-131356.51|-140233.97|
|30|ATM sell|151/460|22066.25|3122.45|-5794.20|

Base0.5% / stress1% adverse slippage/fill, fixed points0. Hypothetical full cost stack for both:20/order, STT0.15%sell, exchange0.05%both, SEBI0.0001%both, stamp0.003%buy, IPFT0.0005%both, GST18%. These are fixed conservative assumptions across years, NOT historical tariffs or the user's brokerage plan. Primary current categories checked at [NSE STT](https://www.nseindia.com/static/products-services/equity-derivatives-securities-transaction-tax), [NSE levies](https://www.nseindia.com/static/invest/first-time-investor-sebi-turnover-fees-stt-other-levies), broker formula reference [charges](https://zerodha.com/charges/). No other broker data or account used. Percentage drawdown unavailable (no defensible initial margin capital).

Assessment VALIDATION_REQUIRED: sparse/selected coverage, missing exact contract IDs, no margin feasibility or holdout, 30m sell loses under slippage stress. No robust tradable edge established. Do not activate from these results.

## Reproduce and verify

`tools/ema921-study/` contains retained-data reproduction scripts. Each takes the absolute parent workspace as its first argument, expects the saved inputs under `outputs/index-strategy-backtests-20261102`, and outputs `outputs/ema921-20261102`. No API/login/order path. Run sequentially: `run_signals.py`, `price_atm.py`, `verify.py`, `make_report.py`, `make_pdf.py`. The lot mapper is dataset-scoped; canonical reference must remain present/reviewed. Run with Python3.12; PDF requires reportlab. Java classpath uses existing cached Maven artifacts in the parent outputs folder. Build this checkout before replay; do not recompile target/classes concurrently with CLI execution.

25 focused tests passed (18 new engine/parser/MVC,7 existing CSV regressions). Tests cover seeded EMA, no-lookahead, all timeframes, exact boundary fills, post-cutoff exits/reversals, 15:00 priority, incomplete-day reset, no-trade days, fees/lots/modes/tick rounding/missing-contract exits, CSV bad timezone/headers/OHLC, roles/CSRF and valid/error no-store responses. A bad timezone initially escaped as DateTimeParseException; now normalized to400. Full application suite not run. Desktop report filter/audit/chart/keyboard/theme/empty/missing states inspected; mobile checks recorded separately when run. PDF all3pages rendered and visually reviewed. Package and Git checks recorded in project notes.
