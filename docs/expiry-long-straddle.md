# Expiry long straddle

Implemented 2026-10-19 on `codex/expiry-long-straddle`, based on main `e6fc185`.
Not deployed, subscribed, or activated by this work.

## Trading behavior

Template ID `EXPIRY_LONG_STRADDLE` appears in the existing strategy catalog.
Inputs: one underlying symbol (default NIFTY), lots (default 1, positive integer
up to 1000, **per leg**) and the existing account/broker selection.

On a trading day with CE and PE contracts actually listed for today's expiry:

| Session | Buy | Sell both |
| --- | --- | --- |
| AM | 09:30 IST | 10:30 IST |
| PM | 13:30 IST | 14:30 IST |

At each entry, select the strike nearest the fresh spot price from strikes shared
by both contracts. An exact tie selects the lower strike. Buy equal lots of the
same strike/expiry CE and PE. PM selects ATM again, rather than reusing AM's strike.
Optional shared instance controls can close both legs before the scheduled exit:
profit target, maximum loss, profit trail and stepped trailing stop. See
[strategy-runs.md](strategy-runs.md) for current controls and grouped manual close.
Same-day lifecycle is compulsory;
the existing delivery/carry-forward broker-product policy remains effective.

Orders are separate legs, not an atomic exchange combination. Both premium
lookups and matching master lot sizes must pass before claiming a session.
Only the scheduled entry minute is eligible; a missed session is skipped.
A committed insert-only session claim per date/user/broker/symbol/slot blocks
repeated polls, restarts, concurrent instances, and cancel/re-enroll duplicates.
Its unique source is retained on all leg requests/executions so recovery does not
rely on successful return from broker submission. If submission fails, the session
aborts. If both full-sized legs are not confirmed by the end of the entry minute,
the session also aborts: cancel unfilled remainders and square off confirmed fills.
No automatic replacement or retry of the paired entry is allowed.

Exits run independently of subscription cancellation and onboarding readiness.
Pending orders stay under broker polling after cancellation; a cancellation call
is not treated as proof of no fill. Late confirmed fills are closed on subsequent
exit scans. Existing sell IDs remain authoritative and use the existing same-order
exit chase. PM is blocked while an earlier pair has unresolved exposure or an
incomplete/unknown submission. Manual retry/market buttons cannot extend timed
entries, and generic price-trigger ticks cannot rearm these strategy requests.

Timed exits are order submissions, not guaranteed executions. Missing prices,
broker outages, uncertain/partial status and existing exit-failure limitations
still require monitoring/reconciliation. Exits retry on market-time minute scans;
this does not guarantee fills at the exact clock time. Session history is retained.
The application must be running for either scheduled action.

### SENSEX spot mapping (2026-10-22)

Shoonya's BSE equity master can omit the SENSEX index. Spot resolution maps
`BC`/`BSE` SENSEX to provider `BSE`, token `1`, symbol `SENSEX` without requiring
a master row. This identity was confirmed using Shoonya GetIndexList and a
matching GetQuotes response. The application's local spot code (currently
`999901`) remains the cache key; it is not sent to Shoonya as its token.
The existing poller rejects quotes with missing or mismatched token/symbol.
SENSEX50, ETFs and option contracts retain their separate resolution paths.
The straddle's fresh-price gate, entry minutes and no-late-entry policy remain
unchanged. Deployment and an actual future session still require verification.

## Option quote recovery (2026-10-22)

Entry premium reads (paired preflight, immediate strategy validation and final
pre-placement lookup) retry Shoonya at most three times. An additional read starts
only within two seconds of that lookup's start and, for straddles, while its entry
minute is open. Existing HTTP timeouts still apply to each in-flight read. A
response arriving after the minute is discarded for entry, and the window is
checked again immediately before the first broker submission. Identity validation
remains mandatory; a spot/index quote cannot replace an option premium. Recovery
retries market-data reads only, never an order submission or an aborted session.

MStock derivative resolution requires matching exchange, underlying name, parsed
expiry, strike and CE/PE side (or future type). Attribute, direct-symbol, pattern
and cached lookups all validate metadata. Missing/stale contracts fail closed;
refresh the provider master separately to enable a fallback for contracts absent
from it. No guessed derivative symbol is used without a verified master row.
This release does not refresh or overwrite the production master.

A failed final live-option premium lookup records
`ENTRY_OPTION_QUOTE_UNAVAILABLE` and an explicit no-order explanation/audit.
Simulator no-order waits retain their existing reason and behavior. A claimed
straddle still aborts on incomplete entry, preserves uncertain exposure and never
replays the session. Today's already-aborted Vaibhav session remains closed.

## Release prerequisites

The additive DDL files are `src/main/resources/db/postgresql/expiry-straddle-schema.sql`
and, after it, `src/main/resources/db/postgresql/straddle-profit-target-schema.sql`,
then `src/main/resources/db/postgresql/strategy-run-schema.sql`.
It was NOT applied to production. Review existing Hibernate/schema configuration
before a separately authorized rollout. No production configuration was changed.

Read-only inspection on October 19 found today's four contracts in the Shoonya
master (23000 CE/PE tokens 47161/47162; 22850 CE/PE 47155/47156; lot size 65), but
not in the Sharekhan or MStock master tables. This strategy deliberately fails
closed when its execution master lacks the same-day contracts. Verify master
freshness and broker contract mapping before activation; no runtime master refresh
was performed in this task.

## Stored-data spot study

`POST /api/backtests/csv/datasets/{id}/expiry-straddle` uses the existing saved CSV
candles and admin-token authorization. Body:

```json
{"from":"2026-01-01","to":"2026-10-19","lots":1,"expiryDates":["2026-10-13","2026-10-19"]}
```

Omit `expiryDates` to use a nominal NIFTY schedule: monthly last Thursday before
weekly options launched on February 11, 2019; weekly Thursday thereafter through
August 2025; Tuesday from September 2025. Other symbols require explicit dates.
**This fallback is not a fully verified historical expiry calendar.** It does not
infer holiday shifts from missing candles. Supply verified actual dates for those
expiries. A range with no stored data is rejected. Missing exact entry/exit bars
are skipped; incomplete interior windows keep the endpoint move but withhold ranges.

The study uses the five-minute candle **open** at each scheduled boundary. It
reports signed change, absolute change, holding-window high/low and range; it
excludes the exit candle's later high/low. These are index points, never CE/PE
premium P&L, rupee profit, an option win rate, or multiplied by lots.

Offline runner, without Spring/broker access:

```sh
java -cp target/classes org.com.sharekhan.backtest.straddle.ExpiryStraddleBacktestMain INPUT_CSV OUTPUT_DIRECTORY [VERIFIED_EXTRA_EXPIRY_DATE]
```

On October 19 the read-only production export included 209744 imported NIFTY
candles (Jan 9, 2015–May 18, 2026), plus 675 archived candles (Oct 7–19, 2026).
The intervening gap is real coverage missing from these tables. Original imported
CSV vendor provenance was not independently verified. No complete intraday
option-premium dataset was found in these candle tables.

The nominal-calendar study has 408 dates / 816 full windows; 84 sessions on
42 nominal dates were excluded for missing endpoints (including holidays and the
data gap). AM mean absolute move 37.50 points, PM 32.75; medians 26.48 / 21.32;
mean holding ranges 73.17 / 65.25. These averages mix index price/volatility regimes.
Per-year results and data provenance are retained with the local report.

Today's actual expiry is Monday October 19 because Tuesday October 20 is Dussehra.
It is excluded by the nominal-only run and can be added explicitly. Stored spot
opens: AM 22981.65→23104.35 (+122.70); PM 22861.75→22856.55 (−5.20).
ATM strikes: 23000 in AM, 22850 in PM. A one-lot option calculation requires the
CE and PE premiums at both boundaries, with quantity 65 per leg. Closing snapshots
cannot substitute for 09:30/10:30/13:30/14:30 option prices.

Calendar sources:
- [NSE weekly option launch](https://nsearchives.nseindia.com/content/circulars/FAOP39894.pdf)
- [NSE expiry-day revision](https://nsearchives.nseindia.com/content/circulars/FAOP68747.pdf)
- [NSE holidays](https://www.nseindia.com/resources/exchange-communication-holidays?apiversion=v2)

## Validation

160 selected tests passed, no failures/errors/skips, covering new rules, paired
entry, independent PM session, missing/stale inputs, failure unwind, pending-order
cancellation, late fills, restart exits, SQL-backed claim persistence/concurrency,
CSV reuse, endpoint authorization, generic trigger isolation, subscription daily
continuation, and existing entry/exit regressions. Maven package passed.
No live order, broker login/refresh, deployment or production schema change.

## October 19 option-price replay (completed)

After the user authorized retrying the previously blocked broker-session read,
fetched four one-minute series from Shoonya TPSeries using the existing session.
No login/token refresh or trade was performed; key/session material stayed in
server process memory. Returned 375 candles for each of tokens 47161,47162,47155,
47156. Initial URL-encoded form and JSON requests returned HTTP400; raw `jData=`
with Bearer auth, matching the existing application integration, returned candles.

One lot = 65 units **of each leg**; fills use exact one-minute candle opens:

| Session | Strike | CE buy → sell | PE buy → sell | Pair points | Gross INR |
| --- | --- | --- | --- | --- | --- |
| 09:30–10:30 | 23000 | 61.80 → 131.10 | 100.95 → 26.10 | -5.55 | -360.75 |
| 13:30–14:30 | 22850 | 44.05 → 34.55 | 47.50 → 33.90 | -23.10 | -1501.50 |
| Total | | | | -28.65 | **-1862.25** |

These are hypothetical gross results; commissions, taxes, spreads and slippage
are excluded. No claim of executable fills or actual account performance.
The morning call gain (4504.50) was outweighed by put loss (-4865.25).

Reusable offline report calculator: `tools/expiry-straddle-option-report.py`.
It accepts the saved spot CSV and sanitized option-candle JSON, checks same-day
expiry, recomputes ATM for each session (NIFTY 50-point strike grid), requires both
exact option boundaries, and calculates decimal premium/quantity results. Six
Python tests pass, covering arithmetic, lot scaling and missing/invalid inputs.
No credentials or network capability are part of the calculator.

The secondary spot run adds today's independently verified holiday-adjusted expiry
to the nominal schedule: 409 dates / 818 sessions, AM mean absolute 37.71 and PM
32.68 points; nominal-only results above remain separately retained.


## October 19 update: combined profit booking per lot, per entry

Added `profitTargetPerLot` to strategy requests/subscriptions and snapshots in
`expiry_straddle_sessions`. It is optional, expressed in INR, and applies to the
**combined CE + PE position for each entry/session**. Example: enter 2000, choose
1 lot → target2000; 2lots →4000; 3lots →6000. AM and PM have independent targets;
one session's profit/loss does not change the other's target. Blank or zero disables
early profit booking. Negative values, more than two decimal places and amounts
above999999999.99 are rejected. The optional field appears only on the straddle
form in both personal and admin views; saved subscriptions show the scaled total.

Profit calculation is gross, before commissions/taxes and execution slippage:

```
combined profit = (CE sell bid - CE confirmed actual entry) * CE quantity
                + (PE sell bid - PE confirmed actual entry) * PE quantity
exit threshold = profitTargetPerLot * session lots
```

Both full legs must be broker-confirmed, quantities balanced, and actual entry
prices available. Requested prices are never used as fill substitutes. Each bid
requires a complete uncrossed book at most5seconds old, no future timestamp,
valid option-price guard, and no more than2seconds difference between the books.
Cached quotes are checked approximately once per second (subject to scheduler
availability). There are no high-frequency outbound quote requests. Missing/stale
books skip profit detection; they do not disable the10:30/14:30 timed exit.

When combined gross profit reaches or exceeds the threshold, an atomic database
update persists `profitExitRequested` BEFORE either sell. Both legs then use the
existing square-off/chase flow with `EXPIRY_STRADDLE_PROFIT_TARGET` reason. The
latched decision survives restart, subscription cancellation, a quote reversal,
one sell failure and one leg closing first. Existing sell-order IDs remain
preserved. Stale entry/abort updates cannot erase a recorded profit decision.
Target trigger is not a guaranteed realized rupee amount; actual fills/costs can
produce a smaller result. No intrabar historical profit claim was added to the
existing offline report, which retains the original timed-exit benchmark.

Enrollment stores the target and forwards it to subsequent evaluations. Changing
a repeated enrollment's lots/target updates future sessions only; open sessions
keep their captured settings. Legacy NULL target remains timed-only. New additive
DDL adds nullable NUMERIC(19,2) subscription/session columns and a defaultfalse
session exit-decision column. No production schema or setting was modified.

Validation for this update:191selected Java tests passed (zero failures/errors/
skips), including SQL reload/concurrent profit claims, combined gain/loss/netting,
per-lot scaling and equality threshold, actual fill requirement, unavailable/
stale/crossed/future/asynchronous books, persisted profit-exit retry after restart,
one failed sell not blocking the other, unchanged timed exits, subscription
persistence/update/validation and existing HTTP/security/entry/exit regressions.
Maven package succeeded. Isolated mock-only Chrome passed personal/admin form
payloads (2000with2/3lots), zero-disable, unrelated-template hiding, mobile layout,
and no page errors. No broker/server request, deployment or activation this turn.
