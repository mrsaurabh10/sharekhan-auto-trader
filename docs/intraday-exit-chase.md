# Intraday target-order closure

The 15:35 IST closer changes existing intraday target orders to
`EXIT_ORDER_PLACED` with `INTRADAY_CLOSE`, then starts exit-order chasing.
Each iteration checks the broker status and modifies the existing sell order
at a fresh best bid. It does not create another sell order. Missing, stale or
crossed books wait for another iteration; cached LTP and the stop-loss level
are not closure chase prices. An unchanged bid does not cause another modify.

Closure intent remains `INTRADAY_CLOSE` throughout repricing so a closure fill
cannot be mistaken for a staged profit target. Sharekhan modifications require
an acknowledgement for the expected order ID, without an error status/payload.
An acknowledgement is not treated as a confirmed fill: existing order polling
continues to record final execution and P&L.

Chasing stops on confirmed fill, cancelled/rejected order, failed modification,
changed order ID, terminal application state, five minutes elapsed, or the
segment deadline (NF/NFO 15:40, cash 15:30, MX 23:30 IST). An unresolved deadline
or rejected modification logs `INTRADAY_EXIT_INCOMPLETE` and attempts to notify
the trade's owner. The trade is not marked closed merely because the chase ends.
These are the application's supported session bounds; special sessions require
separate configuration work. The existing 15:35 scheduler time is unchanged.

Scheduled tasks are restored from durable exit controls after restart, keeping
the original order, intent and five-minute/session budget. Expired/unknown budgets
require attention; recovery never sends a replacement sell. See `exit-order-safety.md`.
This change does not reconcile old live positions or place corrective trades.

Validation (2026-10-15): 413 tests selected, 411 passed, two skipped, no failures
or errors. Four previously failing baseline classes and two sandbox-local-socket
classes were excluded. New coverage exercises repeated bid repricing, stopping
on fill/cancellation/rejection/deadline/order replacement, stale quote handling,
unknown broker status, Sharekhan acknowledgement validation, unchanged bids and
closure dispatch for both target legs with no cached LTP. No real broker calls.

Run the filtered regression suite with:

```sh
mvn -o -Dmaven.repo.local=/private/tmp/sharekhan-m2 -Dmaven.compiler.proc=full \
  '-Dtest=*,!TradeExecutionServiceExitPriceValidationTest,!TradeExecutionServiceRequestStatusSyncTest,!AtrPreviousDayBreakoutQualificationServiceTest,!MarketauxSentimentSwingAtrStrategyTest,!ShoonyaOAuthServiceTest,!ShoonyaQuoteServiceTest' test
```

The POM explicitly configures Java 21 output and Lombok annotation processing
with Lombok 1.18.42; it also removes the duplicate Mockito dependency rejected by
Maven 3.10. Tests were run with Java 25.0.4.1 and Maven 3.10.0.
