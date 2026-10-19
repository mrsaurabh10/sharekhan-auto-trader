# Simulator mover pre-placement waits

Both mover strategies evaluate signals for Simulator accounts as well as real brokers.
A qualified signal is not itself a fill: execution still needs a usable traded
price and the simulator's buy-above threshold. Ordinary queued simulator requests
continue to wait below that threshold and never fill below it.

Previously, an immediate strategy request that hit a pre-placement wait returned
null. Its caller labeled that `ENTRY_EXECUTION_UNAVAILABLE`; cash daily-attempt
protection then blocked that symbol for the rest of the day. Exceptions could
also interrupt the remaining candidates in that evaluation.

Known simulator waits now throw a dedicated `EntryNotReadyException` **before
any broker placement**, for missing/unusable traded prices or an unmet buy trigger.
If a request was already saved, it is atomically finalized with
`REJECTED / ENTRY_NOT_READY_NO_ORDER` and an `ENTRY_WAITING` audit. This terminal
record cannot execute later through the ordinary queued-price trigger. Both mover
strategies display the wait, keep checking other candidates and may requalify the
symbol on a later evaluation. Missing option prices detected before creation
leave no request row.

Only the explicit REJECTED/no-order marker is excluded from cash's daily-request
check. Existing trade rows, broker rejections, submitted/ambiguous attempts and
historical `ENTRY_EXECUTION_UNAVAILABLE` records still block it. This fix neither
relabels historical records nor replays their orders. No automatic retry is added
after an order may have reached a broker. Real-broker placement, spread checks,
signal invalidation, sizing, exits and the separate 09:25 snapshot policy remain.

On October 19, read-only production evidence showed Simulator user 3 did fill
ULTRACEMCO cash, POLYCAB PE and AXISBANK PE. UNIONBANK request 13262 had the generic
execution-unavailable outcome and no trade row. The precise runtime cause of that
old outcome is unverified because the relevant logs rotated; the reproducible
pre-placement-wait bug is fixed without claiming all historical failures share it.
No production deployment or historical reconciliation accompanies this change.
