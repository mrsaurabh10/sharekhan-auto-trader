# Nifty Three Candles simulator trial

Template: `NIFTY_THREE_CANDLE_ATM`; source: `strategy:NIFTY_THREE_CANDLE_ATM`.

This template accepts only an active Simulator account owned by the requesting user.
One lot is taken from the selected contract's current master metadata. CE and PE
share one position limit and a ₹5,000 daily paper loss control for this strategy
and broker account. No subscription is created by installing this code.

## Frozen signals and exits

- Nifty spot, regular 5-minute candles. At least 200 completed indicator candles
  are required, and today's candles must be complete from 09:15 through C3.
- CE: three consecutive green candles; EMA20 at C3 close exceeds EMA20 three
  observed bars earlier. Enter during the fourth candle only, above C3 high.
- PE: three consecutive red candles; EMA20 at C3 close is below EMA20 three bars
  earlier. Enter during the fourth candle only, below C3 low.
- Strict breaks use the 0.05-point grid increment. Doji bars do not qualify. No
  volume, body/wick, ADX, sharp-fall or overextension conditions apply.
- CE stop is C3 low; PE stop is C3 high. The target is 1R from the observed spot
  entry. Levels remain fixed. No target splitting or automatic trailing.
- One position at a time across both sides and all subscriptions on this account.
  Patterns completed while the previous position was open cannot be entered later.
  A persisted signal identity prevents reentry after a restart or an early exit.
- Open positions are exited from 15:30 IST. Management continues after subscription
  pause or cancellation. A triggered exit remains pending if quotes/depth are
  unavailable and is retried without inventing a fill.

## Contract and execution model

Select the nearest listed unexpired NSE Nifty option expiry for the signal side,
then the strike nearest the current spot. Equal strike distances choose the lower
strike deterministically. Recheck the ATM identity after acquiring the entry lock.
Expiry-day contracts are eligible, matching the research's unrestricted session.

The paper broker writes ordinary request/trade rows with `SIM-N3C-` order IDs;
it does not invoke any real broker order interface. This strategy has its own
paper execution path so generic candle-close stop rules, partial targets, and
entry cutoffs cannot silently replace the frozen research rules.

Require a fresh (at most five seconds), coherent Sharekhan websocket option book
for the exact contract and exchange. Entries buy the ask with a full lot displayed
there; exits sell the bid with full displayed quantity. Unknown or insufficient
depth waits. The websocket adapter passes quantities only from explicit direct
top-of-book fields or a matching side/price in a depth object; total buy/sell volume
and side-ambiguous depth arrays are not treated as executable quantity.

Nifty entry and price exits also need a fresh exact NSE spot quote from the shared spot feed. Regular-session
candles currently use the existing MStock intraday/Sharekhan history adapter. That
is a different live feed from the saved DHAN research archive. No tick-level or
provider-parity claim is made. Polling is every two seconds, not exchange matching.
Depth consumption and latency across several independent strategies are not
modeled. Observe actual feed formats during the first regular-market session;
unsupported or unidentifiable formats must wait, not fall back to LTP fills.

## Daily loss and reporting

Daily loss uses this strategy's realized paper net P&L plus the open option marked
at its fresh bid, including the application's existing `legacy-option-lot-estimate`
charges. That tariff is an estimate, not reconciled broker contract-note charges.
An overdue position closed today counts in today's ledger. The daily-loss trigger
is persisted and prevents further CE/PE entries that day even if the eventual exit
quote improves. Mark-to-bid losses, gaps and stale/insufficient books can make final
loss exceed ₹5,000; the limit is reactive, not a guaranteed cap.

Stored trades distinguish spot entry/stop/target from actual option entry/exit
premiums. Audit events record spot R separately from option gross and estimated
net P&L. The PE rule is a mirrored simulator trial; the historical CE results do
not establish PE performance.

## Activation configuration

Use the normal authenticated strategy-start flow with an owned Simulator account:

```json
{
  "templateId": "NIFTY_THREE_CANDLE_ATM",
  "symbol": "NIFTY",
  "brokerCredentialsId": "<owned Simulator ID>",
  "lots": 1,
  "intraday": true,
  "riskSettings": {"amountMode": "TOTAL", "maxLoss": 5000}
}
```

The portal pre-fills these trial settings. Server validation rejects real broker
accounts, multiple lots and additional run profit/trailing controls. Generic/manual
entry routes reject this reserved strategy source. Close All and ordinary manual
square-off route back to the paper bid-fill handler.

The first review point is 50 completed forward paper trades with no parameter
changes; it is a diagnostic checkpoint, not statistical proof. Review CE and PE
separately, quote/depth waits, stop latency, missed fourth-candle entries, actual
paper net, daily-limit overshoots and signal/source differences.

## Verification

Focused signal/contract/quote/paper tests, an H2 round-trip persistence test and a
concurrent-entry test cover the new behavior. Shared strategy, run, price-trigger,
simulator and broker-entry tests cover the integration. Java 21 Maven offline build,
JavaScript syntax checks and a manual code review are required before rollout.
No SonarQube scan is requested for this project.

## Entry diagnostics and quote timing

Quote freshness uses a clock sampled after reading the quote snapshots, including
paper entry and exit. A tick received during candle loading is eligible under the
same five-second limit. The fourth-candle deadline and snapshot freshness are
checked again after entry locks; slow data or locking never extends that window.

The subscription displays separate warmup counts, candle gaps, mixed candle
colors, EMA direction, expired-window, spot-quote and option-book blockers.
`trade_audit_events` retains `ENTRY_EVALUATION` events for this template when the
status/reason changes and at the first evaluation in each five-minute interval.
Events include subscription and broker IDs; unchanged two-second polls do not
create duplicate audit rows. Audit failure does not block evaluation. Historical
candle replay alone does not establish that executable depth was available.

## Shared spot feed

Nifty Three Candles, ADX/DMI spot reads, normal cash/spot entry-reference reads,
and the existing active-scrip polling dispatcher use `SpotQuoteService`.
Execution accounts and order adapters stay separate from data providers.

The service retains the existing Sharekhan LTP-only subscription and reads the
source-specific websocket snapshot, so another provider cannot overwrite its
provenance. If unavailable, it uses an exact Shoonya quote; a current provider
snapshot is eligible even when the last price change is older. MStock's existing
read-only LTP adapter is the next fallback: resolved instrument key, exchange and
returned instrument token must match its master row or the existing verified index mapping. MStock LTP has no last-change
timestamp; freshness describes the current response observation and bounded request
latency, not a claim that a new trade occurred. These fallbacks use existing REST
adapters; this change does not introduce MStock or Shoonya websocket clients.

All accepted observations retain price, provider, identity and receive time with a
five-second limit. Fallback calls are shared/throttled per spot, do not authenticate
or refresh credentials, and never relabel another provider as Sharekhan. Accepted
spot observations reach the normal entry/SL/target callbacks with their original
observation time. No synthetic price tick is created from an old cache read.

Admin GET `/api/strategies/spot-quote?scripCode=20000&exchange=NC` inspects source
snapshots and rejection reasons only; it does not subscribe, fetch, evaluate, or
place orders. Strategy signals, option-book execution gates and risk settings are
unchanged. A spot-data correction alone does not establish executable option depth.
