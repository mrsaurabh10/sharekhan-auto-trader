# Mover evaluation and simulator timing

Applies to STOCK_0925_MOVER_ATR_BREAKOUT and FNO_0925_MOVER_ATR_BREAKOUT.

On October 21, user 1 submitted WIPRO at 10:35:39 with reference LTP 189.50;
the broker confirmed 10 shares executed at 10:35:44. The simulator was checked
at 10:36:05 with LTP 189.45, below the 189.50 trigger, and correctly did not fill.
The broker average fill price was absent. Do not describe the submitted limit
as a verified average or assume live and simulated fills must be identical.

The old subscription loop ran evaluation and broker calls serially. The stock
mover additionally synchronized its entire apply method, holding a singleton
lock through order submission, fill polling, and target placement. Those paths
could block other accounts even though the market signal is shared. The exact
26-second historical timing is not a complete profiler trace.

## Scheduling

Both scheduled polling and the 09:25 snapshot job dispatch mover subscriptions
before evaluating other templates. A dedicated bounded live pool and a separate
simulator pool perform account evaluations. No broker HTTP call runs on the
scheduler as a queue-overflow fallback. Saturation defers work to the next poll.
An in-flight account/broker/template key prevents overlapping cron/poll tasks.
Workers reload subscriptions and ignore cancellation, deletion, or changed
ownership/broker/template. Current settings are used. The existing strategy-run
coordinator executes inside each worker, retaining its thread context and its
transactional cancellation/open-run gates.

Each dispatch creates an ephemeral evaluation cycle. Directional qualification
is calculated once per instrument/side in that cycle and shared between accounts
and both templates when their candidates match. Sizing, contract selection,
duplicate checks, ownership, risk settings, quote checks and actual orders remain
account scoped. Each strategy serializes only its own account/broker/day/source
run; a blocking order cannot hold a singleton strategy lock. Direct/manual apply
uses current-time qualification and the same account locks.

Cached signals expire after 30 seconds (configurable), on a new completed
five-minute candle boundary, or on another trading date. Checks run before and
after market-data loading and again before requesting execution. Late queued
work is skipped, and an expired qualification creates no order request. This is
in-memory coordination for one application instance, not distributed execution.
The durable existing entry guards remain authoritative across restarts.

Defaults under app.strategy.mover-evaluation:

| Environment variable | Default |
|---|---:|
| MOVER_EVALUATION_LIVE_POOL_SIZE | 4 |
| MOVER_EVALUATION_SIMULATOR_POOL_SIZE | 2 |
| MOVER_EVALUATION_QUEUE_CAPACITY | 50 per pool |
| MOVER_EVALUATION_MAX_CYCLE_AGE_MS | 30000 |

## Readiness and display

Simulator cash entry still requires current traded price at or above the buy
trigger. F&O spot revalidation, quote freshness, slippage, spread, account/run
checks and broker fill confirmation remain in force. Independent execution-time
quotes may still produce different results; no simulator trade is backfilled
from a live user's execution.

A terminal no-order simulator attempt retains REJECTED plus
ENTRY_NOT_READY_NO_ORDER in the database so it cannot become a latent executable
request. Personal/admin request tables display that exact combination as
“Waiting for entry,” with a no-order explanation. Broker/other rejection reasons
keep their rejection styling. The subscription may qualify again; this display
does not reactivate that old request or alter historic data.

Diagnostics include MOVER_EVALUATION_START/SKIPPED/DEFERRED, cycle ID, signal
evaluation time/delay, MOVER_ENTRY_CHECK with signal candle timestamp,
MOVER_ENTRY_READINESS with request and execution times/prices, and
ENTRY_QUOTE_TIMING with book timestamp/age. These logs record local receipt time,
not guaranteed exchange-origin time. No credentials or raw broker bodies added.

No schema change. The release includes the already deployed exit-safety changes. Deployment requires
an explicitly requested, reviewed rollout. All tests use mocked brokers and market data.
