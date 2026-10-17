# Stocks 9:25 Mover Breakout

Template: `STOCK_0925_MOVER_ATR_BREAKOUT`. This is a separate buy-only cash-equity
strategy. The existing `FNO_0925_MOVER_ATR_BREAKOUT` option strategy is unchanged.

## Selection and entry

At 09:25 IST the subscription scheduler selects up to five distinct positive
MStock gainers from the existing F&O underlying universe with an NSE cash master
(`NC`). Unlike the option version, this template always chooses gainers: it does
not buy puts or short declining stocks. This follows the request to buy stocks.

The selection is frozen for that process/day. If no subscription runs at 09:25,
or the process restarts, the first eligible evaluation creates a fresh snapshot;
the original 09:25 snapshot is not persisted. An empty successful scan stays
empty for that day; a failed API call can retry on the next tick.

The shared `Fno925EntryQualificationService` supplies the same bullish completed
five-minute opening-range breakout and VWAP-reclaim/base-break checks, volume,
candle quality and ATR(75) checks. Internal `CE` denotes the bullish direction
for that shared qualifier only. Cash requests contain no option type, strike,
expiry, lot size or option feed lookup. There are no new entries from 15:20 IST.

## Investment per stock

Select **Stocks 9:25 Mover Breakout** in the dashboard and enter **Per stock (₹)**.
The symbol is automatic; the lots field is hidden. The investment is saved on
the subscription and replayed by every background evaluation. Starting the same
subscription with a new amount updates its budget for future entries; it does
not resize existing positions or reset the daily entry limit.

`investmentPerStock` is required, positive, at most 999999999.99, and accepts up
to two decimal places. Quantity is `floor(amount / rounded signal entry price)`
whole shares, with no F&O lot multiplier. Example: ₹10,000 / ₹750 = 13 shares,
₹9,750 signal-price notional. A budget below one share's price creates no order.
Quantities above the supported Java integer range are rejected.

The amount is per stock, so five qualifying stocks can use up to five times that
signal-price budget. Unused rupees are not redistributed. Brokerage, charges and
actual execution-price changes are outside this sizing calculation; this is not
a guaranteed cap on the final cash debit. The existing broker entry and fill
reconciliation logic remains authoritative.

Example `POST /api/strategies/start` (same sizing field works with `/apply`):

```json
{
  "templateId": "STOCK_0925_MOVER_ATR_BREAKOUT",
  "investmentPerStock": 10000.00,
  "intraday": true,
  "userId": 1,
  "brokerCredentialsId": 2
}
```

Use the authenticated user's own identifiers. This example documents the API;
it was not sent to a running trading application.

## Stops, targets and daily operation

The initial stop is the lower of the bullish structural stop and entry minus
1.5 ATR. Targets are entry plus 2, 3 and 4 ATR, with TSL enabled. Existing cash
execution splits available shares across target stages; a small position can
have fewer stages. The intraday checkbox is retained; unchecked delivery trades
are excluded from the cash intraday closer.

The subscription stays ACTIVE while other selected stocks are monitored. The
new strategy serializes local evaluations and checks persisted same-day requests
(including failed/rejected attempts) and executed setup history per user/source/
symbol before submitting. This suppresses daily repeats across restarts, while
the execution boundary's existing broker reconciliation remains responsible for
uncertain fills. It does not claim distributed exactly-once execution.

A cash-only closer attempts non-BIGTRADEPLUS NC/BC intraday exits at 15:21, 15:24
and 15:27 IST using existing square-off / same-order chase methods. Options keep
their existing closing schedule. This also covers other non-BIGTRADEPLUS cash
positions. Missing usable quotes, broker failures or unresolved fills can still
leave exits incomplete and require operator review; submission is not a fill.

## Storage and validation (2026-10-17)

Added nullable `strategy_subscriptions.investment_per_stock NUMERIC(19,2)` to JPA,
PostgreSQL schema initialization and trading-state backfill mappings. Legacy
option subscriptions keep nullable budgets and their existing lots. No production
schema was changed. A future authorized deployment must apply this additive
column through the configured JPA/schema initialization path before using it.

- 22 focused tests passed: sizing, bullish cash routing, ATR targets, validation,
  affordability, snapshot filtering, daily guard, entry window, subscription
  persistence/replay/update, H2 JPA round-trip, existing option behavior and
  cash-only closing.
- Filtered regression: 437 selected, 430 passed, 2 skipped, 5 failures, 0 errors.
  The six previously documented baseline/socket exclusions were retained.
  All five additional failures are in `PriceTriggerServiceTest` and reproduce
  on unchanged `main` at 308a257 (46 tests, same 5 failures). Those tests use
  the real date for market monitoring; October 17 is Saturday.
- Maven package succeeded separately with tests skipped after that regression.
- Mocked-API headless Chrome check passed: field visibility, missing-budget
  validation, amount/user/broker payload, saved-budget display and restoring
  option lots. Visual inspection corrected the hint-row layout. No page errors.
- JavaScript syntax and `git diff --check` passed. No live broker, server,
  production database or trade action. No rollout performed.

Logs: `/private/tmp/sharekhan-stock-focused.log`,
`/private/tmp/sharekhan-stock-regression.log`,
`/private/tmp/sharekhan-stock-baseline-price.log`,
`/private/tmp/sharekhan-stock-package.log`. These are local temporary artifacts.

## Onboarding branch integration (2026-10-17)

Merged the stock feature branch into `codex/user-onboarding-ui` at the user's
request. The merge also includes ancestor `main` stop-loss fix 308a257. The
customer portal now switches between lots and required rupees per stock, shows
saved budgets and preserves the automatic universe. The user/broker readiness
guard validates the stock budget in place of lots; owner scoping remains intact.
Budget creation and updates go through onboarding's existing account-row-locked
`StrategyEnrollmentService`, preserving concurrent enrollment protection.

Combined validation: 49 focused tests passed, including an authenticated HTTP
stock-subscription create/update/reject flow backed by H2 and the same-order exit
chase tests. The full filtered Maven package selected 439 tests: 437 passed,
2 skipped, zero failures/errors. Exclusions are the six documented prior
baseline/socket classes plus the Saturday-sensitive `PriceTriggerServiceTest`
whose five failures were reproduced on unchanged main earlier today. This is a
filtered pass, not an unqualified all-tests-pass claim.

Mocked-API Chrome checks passed for the customer portal's stock amount field,
required-budget validation, selected broker/amount POST, saved-budget display,
restoring option lots and a 390px-wide dialog. Visual inspection and both changed
JavaScript syntax checks passed. No live order, production schema update, server
restart or deployment was part of this merge. `main` was left unchanged.
Logs: `/private/tmp/sharekhan-stock-onboarding-focused.log` and
`/private/tmp/sharekhan-stock-onboarding-regression.log`.
