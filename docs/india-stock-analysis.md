# Indian Stock Analysis

`/stock-analysis` is a separate research page for every signed-in `USER` and `ADMIN`, linked from both workspaces. It needs no trading activation or personal broker connection. Its server reads the application's existing shared Sharekhan history integration; no credentials or account records are returned to the page. Anonymous requests must sign in; analysis API requests return401 without authentication. Existing admin-only historical API authorization is unchanged.

## Scope and user flow

Select NSE cash (`NC`) or BSE cash (`BC`), type at least two symbol characters, and choose an exact scrip match. The server verifies exchange identity, no option type, no expiry and zero/absent strike before requesting history. Broker IDs are never reused across exchanges. Cash instruments in the current master can include ETFs; a matching metadata row is not proof of trading eligibility.

Choose Intraday (five-minute bars) or Delivery/Swing (daily bars). Research checks describe observed prices and do not submit orders or evaluate the full live mover strategy. No AI provider, model key, Python runtime or new database table is required. This implements the compatible price-analysis/sizing methods natively; it does not run all72 upstream skills inside the application.

Endpoints:

- `GET /api/stock-analysis/instruments?exchange=NC&q=RELIANCE`: at most25 matching cash instruments, exact symbol matches first. Reads the current master; never refreshes it.
- `GET /api/stock-analysis/report?exchange=NC&scripCode=...&mode=INTRADAY`: public market-data report; mode can also be `SWING`. No user/account identifier is accepted or returned.

The browser can download a JSON report and revisit up to six in-memory snapshots during this visit. Snapshots are not written to browser storage or the application database. Cash sizing inputs remain in the current page and are excluded from report downloads. No order controls appear on this page.

## Sharekhan data and bounded work

History calls use `SharekhanHistoricalService.getRecentRawHistoricalCandles`, with `5minute` or `daily`. The upstream [Java SDK example](https://github.com/Sharekhan-API/shareconnectjava#historical) documents the `daily` interval. The application uses existing valid sessions and does not initiate login or broker/master refreshes for this page. An empty response cannot distinguish expiry, unsupported history and no returned data, so the UI reports those possibilities without claiming a specific cause.

Reports are shared, read-only market data, cached for30seconds (empty reports10seconds), with at most64 entries and one fail-fast provider slot. Busy requests receive429 rather than queueing behind a slow fetch on servlet threads. Uncached starts closer than500ms receive429. The chart and calculation window is bounded to1,500 intraday or500 daily bars. Browser search is debounced and result sets are limited to25. These limits apply to this feature; they are not a global rate limiter for unrelated history consumers. HTTP responses use `no-store`; broker credentials and risk inputs never enter the report cache.

Available recent history is not an arbitrary-range archive. The UI reports parsed input rows, retained bars, invalid bars, duplicates, intraday gaps, actual first/last bar and retrieval time. It never implies that200 daily bars or a full requested year are available. Source parsing can discard unusable upstream rows before this feature receives them, so received-row counts refer to parsed candles, not raw provider payloads.

## Calculation and timestamp rules

- All times are `Asia/Kolkata`; no US calendar is used. Intraday analysis covers the normal09:15–15:30 cash session only. Special sessions are not modelled; exchange holiday completeness is not inferred. Daily analysis excludes the entire current IST date, including after close.
- Known provider five-minute end stamps such as09:19:59 normalize to09:15. Aligned start stamps remain unchanged. Off-grid/subsecond stamps are rejected. Forming/future bars are excluded before sorting, deduplication and indicators. Last completed close is labelled as historical, never LTP.
- Prices must be finite, positive, OHLC-consistent and at most₹1trillion. Conflicting duplicates are removed; identical duplicates collapse. Invalid bars, conflicting duplicates or within-session five-minute gaps suppress technical indicators/conclusions. There is no inferred fill for a missing bar.
- SMA20/50/200 are simple completed-close means. Bullish alignment means close>SMA20>SMA50; bearish is the inverse; other complete alignments are mixed. Fewer than50 usable bars cannot classify this alignment.
- RSI14 and ATR14 use Wilder smoothing, seeded from14 changes/true ranges, requiring15 usable bars. Flat RSI is50; rising-only is100. ATR uses the previous close and includes observed overnight price gaps.
- Session VWAP uses typical price `(high+low+close)/3` times bar volume. It requires a gap-free latest returned session starting09:15, known nonnegative volumes and positive total volume. It is a bar-derived approximation, not a tick-exact broker VWAP.
- The15-minute opening range requires completed09:15,09:20,09:25 bars. It refers to the latest returned session, not necessarily today. Volume confirmation compares the latest bar with the preceding20 bars in that same session;1.5times the prior average is labelled HIGH. These descriptive thresholds do not reproduce the entire automated strategy.
- Swing range levels are the lowest low/highest high of the previous20 available daily bars, excluding the latest bar. These are observed levels, not generated targets. The prior close means the last available earlier-session close, not an official adjusted reference close.
- RECENT intraday status requires the latest bar end within15minutes of retrieval. Older data is HISTORICAL. Daily data is historical; invalid or gapped data is INCOMPLETE. No usable bars means UNAVAILABLE. Current corporate-action adjustment is not established by this source.

The canvas chart is local code without a CDN dependency and includes volume, SMA20 and an accessible OHLCV table. It uses returned-bar spacing (not elapsed-time spacing); hover/table labels carry the actual dates/times. Missing indicators show Unavailable.

## INR cash-share sizing

The calculator supports long, fully funded cash shares. It parses up to two decimal places into integer paise, then computes:

`quantity = min(floor(riskBudget / (entry - stop)), floor(capital / entry))`

Stop must be below entry, risk budget cannot exceed capital, and amounts must be positive and within the documented input bound. Zero shares is valid when one share is unaffordable. There is no implicit margin, derivative lot conversion, guarantee of stop execution, or fee/tax/slippage estimate. Inputs are user assumptions, not account balances read from a broker.

## Validation

Focused Java service/controller/security and existing portal/history suites verify both roles, anonymous denial, identity isolation, cache reuse, completed-bar boundaries, provider timestamp normalization, duplicates, gaps, Wilder calculations and missing data. Node tests exercise integer-paise sizing, funding limits and invalid inputs. Fixture-only Playwright checks desktop/mobile rendering, NSE/BSE selection, daily/intraday mode, chart/table, error/empty states, stale-response isolation, literal untrusted text, and no write requests. No real provider request, order, application startup with production configuration or deployment is part of these checks.

Recorded validation on2026-10-29:59 tests in9 selected Java suites passed with0 failures/errors/skips;6 Node sizing tests passed; fixture-only Playwright desktop/mobile smoke passed with0 unexpected page errors or writes. Maven offline package succeeded using the existing ngas-m2-20261026 cache. This is not a full application-suite or live Sharekhan/provider validation.
