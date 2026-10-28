# Funds-aware option trigger orders

Live long-option requests with entry, stop and target based on option premium now
check the selected broker account's available funds before submitting a broker
stop-limit entry. This covers Sharekhan, Dhan and mStock. Simulator, spot-based
setups, cash/BIGTRADEPLUS entries and specialised Natural Gas/straddle execution
retain their existing admission rules.

The required amount is the complete quantity at the submitted limit price plus
the greater of ₹100 or 1% of that premium outlay. This reserve is a conservative
admission allowance, not a verified brokerage calculation. Quantities are not
reduced to fit the available balance.

If funds are insufficient, the existing request stays
`PLACED_PENDING_CONFIRMATION`, with reason `WAITING_FOR_BROKER_FUNDS`. If the
balance cannot be verified, it stays pending with `BROKER_FUNDS_UNAVAILABLE`.
Neither case creates a trade row or submits an order. Price ticks and the queued
admin execution path cannot bypass this gate.

Pending requests are considered oldest first every five seconds during weekdays
09:20–15:20 IST, with at most one ordinary funds attempt per request every 30
seconds. A confirmed persisted exit wakes that broker account for the next scan,
including normal polling, immediate exits and broker bracket reconciliation.
Every attempt fetches funds again: an exit never implies that funds are already
available. The periodic path also handles delayed release, deposits and restart.

Retries require a fresh positive cached option price (at most 15 seconds old),
an unexpired identifiable contract, a price below the first target and within
the existing 10% entry tolerance. Prior-day intraday requests are excluded.
Onboarding, run closure, status claims and existing cutoffs still apply. A
persisted trade for the request blocks another submission. A smaller subsequent
request may fit even if an earlier request is still waiting.

Submission is serialised per credential account within the application process.
A 30-second local shadow reserve prevents sequential submissions from reusing a
balance before the broker reflects a new debit. It may temporarily double-count
a debit already reflected by the broker. It is not a durable or distributed
capital reservation. External orders and changes after the snapshot remain
subject to the broker's final acceptance. Definitive broker refusals retain the
existing terminal handling; uncertain submission outcomes retain their claim
for reconciliation and are never automatically replayed by the funds queue.

Funds reads use only a valid saved session for the selected credential account,
without authentication, token refresh or global-account fallback:

- [Dhan funds API](https://dhanhq.co/docs/v2/funds/): `GET /fundlimit`, validate
  `dhanClientId`, read the provider's `availabelBalance` spelling.
- [Sharekhan funds API](https://www.sharekhan.com/trading-api/documentation): SDK
  `getFunds(exchange, customerId)`, validate the returned customer. For the
  documented equity/FNO schema, use current cash minus absolute withdrawal,
  exposure, margin, premium, BPL and held-fund fields. This conservative lower
  bound excludes collateral and profit credits; it is not an exact reproduction
  of the broker's displayed total margin.
- [mStock fund summary](https://tradingapi.mstock.com/docs/v1/typeA/User/#fund-summary):
  use the single documented `SEG=A`, `LIMIT_TYPE=CAPITAL` row's
  `AVAILABLE_BALANCE`, excluding MTF borrowing capacity. Other segment layouts
  remain unavailable until explicitly supported.

Missing required fields, wrong identities, ambiguous rows, invalid numeric
values, expired sessions and provider failures fail closed. Different broker
schemas, including undocumented segment-specific responses, can therefore keep
a request waiting. No live funds lookup or real order was used to validate this
implementation; tests use mocked broker responses.
