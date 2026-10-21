# Stop-loss exit with an existing target sell

When a stop loss triggers for TARGET_ORDER_PLACED, reuse its exitOrderId. Persist
EXIT_ORDER_PLACED / STOP_LOSS_HIT before modifying. If the broker modification is
rejected, times out, or lacks acknowledgement, retain that ID and schedule its
chase. Never infer that the original sell is inactive from a failed modification.
PriceTriggerService no longer falls back to new placement for such an order.

The stop chase polls the same broker order before each retry, waits on unknown
status, and reprices only at a fresh, uncrossed best bid. It preserves STOP_LOSS_HIT
through repricing. It stops on confirmed fill (normal polling records execution),
cancellation/rejection, changed ID/application terminal state, segment close or
five minutes. Unresolved terminal/deadline cases notify the owner. Modification
failures retry on subsequent ticks within those bounds. Partial fills remain on
the original order with its original total quantity; no second sell is submitted.

Square-off also routes existing sell IDs to modification/chase. The placement
lock re-reads the trade and refuses a new sell when an exit ID already exists.
Concurrent placement failures must not clear the winning sell or overwrite its
state with EXIT_FAILED. Automatic after-hours replacement is likewise blocked
while an old ID remains recorded: expiry alone is not broker reconciliation.
No automatic replacement for cancelled/rejected orders is introduced; an operator
must reconcile fills and remaining position before further action.

Durable chase controls now restore same-order repricing after restart within the
original budget. Expired budgets and inactive partial exits require attention;
see `exit-order-safety.md`.
Special-session hours are not implemented. This fix makes no broker calls in
tests and requires a separate reviewed rollout to affect production.

Regression coverage includes SL modification acceptance/rejection, retries and
same-ID fills, partial fills, unknown status, stale books, cancellation/deadline,
terminal application state, square-off reuse and stale-caller placement guarding.
