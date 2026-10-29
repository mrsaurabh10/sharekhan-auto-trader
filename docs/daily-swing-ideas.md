# Daily Swing Ideas — India technical research v1

Every signed-in USER/ADMIN can view this section on `/stock-analysis`, read saved reports and request a bounded shared scan. Anonymous access is denied. Scan requests use POST and CSRF protection; GET requests only read reports. No order, broker-login, credential-refresh, account-state or external-message dependency exists.

## Skill methods and limits

The implementation is native Java; it does not execute the entire installed skill pack inside the server. The compatible methods were reviewed against the installed `breakout-trade-planner`, `position-sizer` and `backtest-expert` skills from TraderMonty's MIT-licensed trading-skills repository.

| Skill | Included method | Explicit boundary |
|---|---|---|
| breakout-trade-planner | Separate trigger/maximum entry, risk measured from maximum entry, maximum 8% structural risk, 2% breakout chase ceiling, 2R/3R projected targets | This is a 20-day breakout/pullback preset, not a VCP detector. Full Minervini trend/VCP ratings, intraday volume confirmation and Alpaca order templates are not included. |
| position-sizer | Whole-share fixed-risk sizing, maximum position value, remaining portfolio stop-risk cap | INR/paise arithmetic; capital and existing risk are user assumptions. No holdings/sector exposure are read, no Kelly sizing without measured win/loss inputs. Quantities are for one idea at a time, not a combined portfolio allocation. |
| backtest-expert | Explicit versioned rules, historical as-of filtering, conservative daily-outcome ambiguity, immutable published plans | Backtest/performance status is NOT VALIDATED: no 5-year point-in-time NSE universe, survivorship analysis, multi-regime cost/slippage simulation or walk-forward results are available. No profitability/confidence score or live strategy eligibility is claimed. |

The installed position-sizer was executed as a reference check with hypothetical currency-neutral inputs: capital500000, entry207, stop201, risk0.5%, maximum position10%. Its result241shares/value49887/risk1446 matches the browser calculator. Upstream dollar-formatted artifacts are test evidence only; the application uses INR.

## Universe, provider and sessions

Default universe: a named 30-stock NSE cash research list, including RELIANCE/TCS/HDFCBANK/JIOFIN. It is **not** represented as the current Nifty50 membership or the whole exchange. `app.swing.symbols` can supply up to60 unique symbols. The complete configured list and each stock's outcome/rejection reason are visible. BSE single-stock analysis remains available separately.

Each symbol must resolve uniquely to NSE cash in the Sharekhan master and match Shoonya's exchange, native token and trading symbol at GetSecurityInfo. The feed uses only the existing current-day global Shoonya session and daily EODChartData; it never substitutes personal Shoonya credentials or mixes providers. Existing Stock Analysis fallback is unchanged; this scanner is Shoonya-only. Daily price and volume checks are mandatory. If less than80% of the research universe is covered, no plans are published; insufficient coverage is DATA_INCOMPLETE, distinct from NO_SETUPS. (The actual coverage rule is covered/universe >=80%.)

Session calendar is isolated from trading strategies and verified for 2026 using NSE's cash holiday listing: https://www.nseindia.com/resources/exchange-communication-holidays . It excludes weekends and listed holidays; entry plans crossing the verified8Nov2026special session, and outcomes spanning it, are withheld. Unknown next-year dates fail closed. Extend and verify this calendar before2027. Daily indicators may contain provider bars from special sessions; the entry date itself is regular-session only.

Before18:00IST, the required data date is the previous regular session. At/after18:00IST, it is today's regular session (or preceding regular day on holidays/weekends). An EOD bar must actually be present; clock time never creates a synthetic close. The existing single-stock page's current-date exclusion remains unchanged. Last20 regular sessions must be present and220 daily bars are required. A >30% adjacent close discontinuity blocks the stock pending corporate-action review; this does not establish adjusted history.

## Exact technical preset

- Prices: positive finite OHLC-consistent bars; conflicting dates fail closed. Future-dated prices/volume are excluded before calculations. Last20 daily volumes must be positive; average(close × volume) must be at least₹20crore.
- Trend: last close>SMA50, SMA20>SMA50>SMA200, and SMA50 is above its value20bars earlier. RSI14/ATR14 reuse the tested Wilder calculations.
- Pullback, tested first: last low<=SMA20+0.5ATR; last close>=SMA20 and>open; RSI45–65. Trigger=last high+max(tick,0.1ATR).
- Otherwise breakout: close within3% below/equal the preceding20-bar high, RSI50–70. Trigger=that high+max(tick,0.1ATR).
- Maximum entry=trigger+0.25ATR, rounded up to the cash tick from the same verified Shoonya security identity. Breakout maximum entry must not exceed pivot×1.02. Stop=last5-bar low−0.25ATR, rounded down. No generic option tick fallback is allowed for cash; absent/unsupported tick blocks the idea.
- Worst-entry risk must be1–2.5ATR and<=8%. T1=maxEntry+2R; T2=maxEntry+3R, rounded up. A pullback with observed20-day resistance above maxEntry but below T1 is rejected. Targets are projections, not forecasts or guaranteed fills.
- Market-condition proxy: at least50% of covered research stocks must be above SMA50. This is a declared sample breadth gate, not a Nifty index regime model. With insufficient coverage or a blocked gate, no report plans are actionable. At most10 candidates, sorted by structural risk percent then symbol; ordering is not a probability of success.
- Entry is valid only on the next verified regular session,09:15–15:15IST (a conservative research cutoff before closing periods). Skip an opening gap above maxEntry or stop breach before entry. Review earnings, news, corporate actions and portfolio exposure manually; these are not screened. Review holding after10 completed sessions.

## Archives, observations and schedule

One report per required data date is written atomically beneath `app.swing.directory` (default `/app/data/swing-ideas`, on the existing persistent mount). Published reports are immutable; DATA_INCOMPLETE can be replaced by a later retry. History reads at most90 reports; retention removes only this feature's date-named report files older than180calendar days. Report/observation writes are capped at1MB. No database schema change is needed.

A dedicated single daemon worker runs scans; servlet and strategy-scheduler threads do not wait for network calls. One scan at a time, minimum10minutes between attempts,60-symbol maximum,8-minute between-symbol time budget (an in-flight bounded HTTP call can finish after the deadline) and900ms spacing between symbols, plus1100ms between same-instrument identity/history calls. On completion the worker revisits the most recent30 reports using already-fetched data. Read endpoints never initiate a scan.

The app requests an initial scan after startup, then retries at19:15,20:15,21:15,22:15,23:15IST and08:55IST on regular weekdays. Completed daily reports skip repeat scans. A missing current-day shared session or delayed EOD publication produces explicit unavailable rows; the feature does not log in automatically. The existing08:40Shoonya login belongs to the existing broker system, not this feature.

Observations use only completed EOD data through the required session. They describe ENTRY_LEVEL_TOUCHED, TARGET_1/2_TOUCHED, STOP_LEVEL_TOUCHED, GAP_SKIP, EXPIRED, INVALIDATED_BEFORE_ENTRY, REVIEW_DUE or UNAVAILABLE. A candle containing both stop and target, or an unknown stop-before-entry ordering, is AMBIGUOUS. These are not fills, realised returns, broker positions or performance statistics. Missing required session data blocks inference. Prices gaps can exceed planned stop risk.

The page polls saved status while visible (5seconds during a scan,60seconds otherwise), announces updates in an accessible status area, and preserves downloadable daily reports. Alerts are **in-app only**, not Telegram/email/push. Budget inputs remain in page memory and never enter report downloads or the server.

Endpoints under `/api/stock-analysis/swing`: GET root/status, GET `/history`, GET `/reports/{ISO-date}`, POST `/scan`. No order endpoints are exposed by the feature.
