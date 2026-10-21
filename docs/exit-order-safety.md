# Exit order safety

This release addresses the October 21 Dhan exit investigation and shared exit
recovery findings. Local tests do not certify broker connectivity or guarantee
execution of a limit order. Applying these changes requires a separate rollout.

## Contract and position checks

Dhan requests use the exact detailed-master contract, unit quantity, published tick
and original broker execution product. The app's `intraday` flag controls same-day
exit intent; it does not choose a legacy position's product. Before a new sell, a
scoped position GET must show a sufficient LONG position in the exact contract and
product. Missing legacy product is obtained from one unambiguous positive position.
A scoped order-book GET reserves quantities already covered by pending sells.
Unavailable, ambiguous, fractional or insufficient quantities prevent submission.
These are conservative snapshots, not an atomic reduce-only broker operation;
concurrent manual trading and multiple application deployments require operator
coordination.

`broker_security_id` is persisted with the trade and copied to split legs. Status
REST responses and stream events must match account, order/correlation, segment,
security ID, total quantity and product. Internal filled/remaining arithmetic and a
positive average are also required. A legacy row without a broker security ID must
bootstrap its exact identity from the current master. Once persisted, ordinary
status checks do not require another master download. A mismatch never closes the
app's full position. Inactive partial fills require manual remainder reconciliation.

## Submission generations

Dhan's journal is preserved. New exits use `TRADE:<id>:EXIT:<generation>`; old
`TRADE:<id>` sell rows remain generation zero. Each generation commits a unique
claim before broker POST. Only an explicit HTTP refusal (excluding ambiguous
408/429 outcomes), or a validated terminal order with zero fills, permits a later
generation. Existing IDs must first go through the checked reset path. A missing
correlation, timeout, restart or a new calendar date never authorizes another sell.
Partially filled or filled prior orders remain attached to reconciliation.

Standard Sharekhan sells also claim a durable generation before the SDK call. An
ambiguous acknowledgement returns an `SK-UNKNOWN-...` handle; the adapter does not
repeat the NEW SELL. Known order IDs are retained. A matching inactive, zero-fill
broker order can be retired by the checked reset before another generation. An
unresolved handle requires operator mapping/reconciliation; it cannot be used as a
real broker order ID or automatically released. Standard buy retry behavior and
BIGTRADEPLUS broker-managed bracket execution are outside this change.

## Price chase and restart

Every price-chase iteration reads the existing order and requires a fresh uncrossed
bid/ask book before repricing. Neither SL levels nor cached LTP substitute for an
executable bid. Premium target exits retain their target-price floor; spot targets
retain the charge-aware profitable floor. `EXIT_CHASE` does not overwrite the
original closing intent. Decimal tick arithmetic prevents skipping the first valid
profitable tick because of floating-point rounding.

`exit_chase_control` persists order ID, original intent and start time separately
from mutable trade snapshots. Recovery runs every 15 seconds, resumes the same
order and retains its original five-minute/session deadline. Expired budgets are
not restarted automatically. A new protective intent (target to stop/EOD close)
may start its own budget; repeating the same intent cannot extend it. Legacy rows
without a saved budget use the original placement time conservatively; missing
placement time requires attention. Partial inactive exits cannot be repriced.

## Attention and recovery

Unresolved exits retain exposure and an actionable reason. Personal and admin
trade tables show **Needs attention**; the Audit action records shared execution
attention events. Dhan error handling retains only allowlisted broker codes and
HTTP status, never free-form response bodies or credentials.

Durable attention records retry notification delivery only after failure, with
exponential backoff (at most one hour between attempts) and a maximum of eight
attempts. Delivery is acknowledged only on an `ok` response. Lack of Telegram
configuration is not reported as successful delivery. The visible app flag remains
even when delivery is unavailable. A newly acknowledged replacement clears the
old generic attention flag; terminal trades do not retain an open-position alert.

Carry-forward `EXIT_FAILED` trades have a guarded Reset to Executed control. An
existing order must be verified inactive with explicit zero fills, original total
quantity and remaining quantity. Partial, active, filled or unverifiable responses
refuse reset. A failed trade without an exit ID also requires a durable explicit
broker refusal; an absent ID alone is insufficient. Ordinary manual reset
permission checks still apply. Reset does not place a broker order.

## Schema and verification

`db/postgresql/exit-order-safety-schema.sql` is additive: a broker contract column,
Dhan generation/outcome columns, `broker_exit_submissions`, and
`exit_chase_control`. Existing `ddl-auto=update` installations create these from
JPA on an authorized rollout. Keep all submission/control tables in backups. Do
not delete old claims or backfill unknown outcomes as refusals.

Tests cover scoped payloads and recovery, foreign/wrong-contract/quantity/product
fills, zero/partial/unknown outcomes, committed claims across rollback, simultaneous
senders, legacy generation discovery, fresh-book target pricing, original restart
budgets, alert retry acknowledgement and owner/security guards. Browser regressions
use mocked APIs only. Known baseline failures in
`TradeExecutionServiceRequestStatusSyncTest` concern unchanged linked-entry request
status expectations; they are recorded separately from exit regression results.

No production trade correction, broker request, token refresh, restart or schema
application is part of implementation validation. Vaibhav's earlier authorized
reconciliation is separate from this release.
