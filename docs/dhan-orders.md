# Dhan order execution

Implemented alongside Dhan authentication in the customer portal. After verifying
a Dhan connection, explicitly enable trading. Register the application's outbound
static IP in Dhan first: placement, modification and cancellation require it.
No live orders or account configuration changes are part of automated tests.

## Supported execution

Manual Dhan orders in the user portal default to delivery for cash (`CNC`) or
carry-forward for options (`MARGIN`). Selecting Intraday explicitly uses
`INTRADAY`. The review dialog shows the selected product. Refreshing account data
preserves an explicit product choice for the same account; selecting a different
account applies its default. Strategy subscription settings are unchanged.
This default applies only to future requests: existing broker orders and positions
retain their original product and cannot be converted by relabelling database rows.

- Long entries and sell exits for standard NSE/BSE cash equities and NSE/BSE
  equity/index options, using the application's NC/BC/NF/BF exchange mapping.
- DAY limit orders, and buy stop-limit entries (`STOP_LOSS`) held by Dhan.
- INTRADAY, cash CNC delivery and option MARGIN carry-forward, derived from the
  application's existing intraday setting. Quantities are shares/units already;
  the adapter does not multiply them by lot size again.
- Entry/exit modification of the same broker order, cancellation requests and
  order polling through the existing BrokerService interfaces, with Dhan live
  order updates and bounded REST recovery described in `dhan-order-updates.md`. A cancel request
  acknowledgement is not confirmation of cancellation or absence of fills.
- Existing target/SL and chase logic remains in the application. Dhan acknowledgements
  never mark trades executed: polling requires full filled quantity, zero remaining
  quantity and a positive actual average fill price before confirming a full fill.

An explicit HTTP 4xx refusal (except 408/429) marks the entry request REJECTED
with its diagnostic reason and disables the local trigger fallback. A REJECTED
placement acknowledgement with an order ID becomes terminal after an owned-order
lookup confirms zero fills. Unavailable lookups, partial fills, timeouts and other
uncertain delivery outcomes retain their tracking ID for reconciliation. These
outcomes never authorize a second POST for the same submission.

Excluded: futures/currency/commodity contracts, short entries, market/AMO orders,
MTF, Dhan Super/BO/CO products and Sharekhan BIGTRADEPLUS strategies. Orders over
the published freeze quantity are rejected rather than split into independent
orders. Non-whole option lots and prices off the Dhan tick grid are rejected before
HTTP. Prices are not silently moved away from the strategy's requested limit.
Dhan market-data transport is not added; existing configured application market-data
providers still supply signals, prices and books. A Dhan token alone does not
establish availability of those feeds or guarantee strategy execution.

## Exact instrument identity

The first order lookup each IST day downloads Dhan's public detailed CSV into an
in-memory snapshot. The downloaded CSV is not stored in the repository/database.
Mapping uses exchange segment and underlying symbol; options additionally require
exact expiry, strike and CE/PE. It never assumes Sharekhan scripCode equals Dhan
securityId and never picks the first ambiguous contract. Unsupported/missing/
expired/ambiguous contracts or an unavailable current master prevent placement.

Master TICK_SIZE values are paise and are divided by 100 for rupee order prices.
Cash NSE EQ series and BSE single-unit equities are supported. BSE cash's published
freeze=-1 is treated as unspecified; the integer API quantity limit still applies.
Other positive freeze limits and option lot sizes are enforced. Polling continues
without downloading the master; modification and cancellation validate instrument
identity against the current master before mutating a broker order.

## Lost acknowledgements and duplicate prevention

`dhan_order_submissions` records a unique send-once claim in a separate committed
transaction before each POST. Its correlation ID is deterministic per credential,
submission identity and side. Entries use saved request ID and gap-reentry count
when available (the engine may not yet have a trade row); exits use persisted trade
ID. The journal must stay with trading state during backup/restore.

On a timeout, malformed acknowledgement or uncertain HTTP outcome, the adapter
queries `/orders/external/{correlationId}`. If still unconfirmed, it returns a
`DHAN-CORR-...` tracking handle as pending. Polling/modification/cancellation resolve
that handle to the actual Dhan order. The next attempt/restart cannot issue another
POST with the same submission identity, even if the earlier call's outcome is unknown.
A failed database claim sends no broker request. No global/other-account token or
implicit login/HTTP retry is used. New buys require active, trading-enabled owned
credentials; existing exits and reconciliation remain available when new trading
is disabled, provided a valid scoped token exists.

The journal intentionally does not automatically release claims for rejected,
cancelled or expired orders. A confirmed unsuccessful entry needs a fresh request;
replacement exits/overnight target rearming for the same trade require operator
reconciliation before a new submission generation can be introduced. Do not erase
a journal row merely because an API lookup failed or because a target expired.
Partly filled inactive exits retain their original order ID and become EXIT_FAILED
with a reconciliation-required reason and notification attempt. Reconcile fills
and the remaining position manually before another sell. Periodic status checks
are bounded as documented in `dhan-order-updates.md`; late stream updates remain
available. Existing bounded chase notifications can also report incomplete exits.
These are conservative limits, not guarantees of exactly-once execution across
manual broker actions or database restoration.

## Schema and rollout

One additive table is required on the primary JPA datasource. Existing deployments
using Hibernate ddl-auto=update create it on an authorized rollout. For managed
PostgreSQL schema deployments, review `db/postgresql/dhan-order-submissions.sql`.
It has no credentials, foreign-key dependency or existing trading-row changes.
No production schema changes, deployment or real order tests have been run here.

Before release verify public Dhan authentication/callback, a valid persistent crypto
key, master access from the application JVM, configured market feeds, the outbound
static IP registration and effective primary schema. Local mocks are not evidence
of successful live Dhan order execution. Never use real trades as routine smoke tests.

Sources reviewed 2026-10-17:
- https://dhanhq.co/docs/v2/orders/
- https://dhanhq.co/docs/v2/annexure/
- https://dhanhq.co/docs/v2/instruments/
- https://dhan.co/support/platforms/dhanhq-api/how-should-i-interpret-the-tick-size-value-from-the-scrip-master/
