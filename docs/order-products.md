# Broker products and app-managed exits

All new standard broker entries use delivery/carry-forward regardless of the
request's `intraday` flag, source, user or strategy. The app's same-day exit flag
continues to control its existing entry cutoff, square-off and exit chase. It
does not select an intraday broker product. Existing strategy schedules and
saved subscription settings are unchanged.

| Broker | Cash entry | Options entry |
| --- | --- | --- |
| Dhan | CNC | MARGIN |
| MStock | CNC | NRML |
| Sharekhan | INVESTMENT | INVESTMENT |

BIGTRADEPLUS remains an explicit Sharekhan-only bracket product. Dhan and MStock
reject its placement rather than silently substituting a standard order. Its
existing bracket/short-side rules remain in place.

Portal and admin order forms label `intraday` as app-managed same-day exit.
The portal review shows the broker product and app-managed exit setting
separately. Checking the same-day exit box cannot select INTRADAY/MIS on a new
standard entry.

The actual entry product is saved as nullable `broker_execution_product` on
`triggered_trade_setups`. Normal entry persistence, staged target copies and
partial-position copies retain it. Subsequent exits and MStock modifications
use the saved product even after restart or changes to the app's exit setting.
Dhan order-status/correlation recovery records the broker-reported entry product.

Legacy rows remain NULL, without a backfill. Their exits retain the previous
broker mapping so existing INTRADAY/MIS positions close in the correct product;
the new policy does not convert those positions. A recovered old Dhan submission
also keeps its broker-reported original product. Product conversion at the broker
requires separate reconciliation; changing database labels is insufficient.

The additive primary-datasource SQL is
`src/main/resources/db/postgresql/broker-execution-product-schema.sql`.
Apply only on an authorized rollout (or let the existing Hibernate update setting
create the column). Do not backfill or relabel existing positions. No production
schema change, deployment or broker trade was performed while preparing this fix.
