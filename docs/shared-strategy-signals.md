# Shared strategy market decisions

Matching subscribers share market calculations and contract selection; each keeps
separate account eligibility, quantities, orders, fills, risk controls and exits.
A shared signal is never a shared order or a guarantee of identical execution.

## Grouping and coverage

`SharedMarketDecisions` coalesces concurrent calculations by calculation kind,
normalized symbol, effective entry settings and a 30-second evaluation bucket.
User IDs, credential IDs, lots, investment budget and personal exit risk settings
are excluded from market keys. ATR entry offset/minimum-distance values are
included by effective value; ATR re-entry groups include previous exit time and
entry level. News groups include the observation time and option direction.
Different settings that affect entry produce separate decisions.

| Family | Shared work | Subscriber-specific work |
| --- | --- | --- |
| Expiry long straddle | Persisted date/symbol/AM-or-PM expiry, strike and exact CE/PE identities | Session claim, lots, risk settings, premium validation and each leg's execution/recovery |
| ORB CE/PE | Opening range, breakout/filter result and prepared contract/levels | Fresh spot/deviation check, ownership, quantity and execution |
| Supertrend CE/PE | Candle snapshot, indicators, signal-rule result and contract selection | Diagnostics, daily limits, lots and execution |
| F&O and cash movers | Existing persisted opening shortlist plus shared directional qualification | Account duplicate guards, investment sizing/contracts and execution |
| Manual F&O reclaim | Qualification and preferred contract | Account limits, lots and pending requests |
| ATR prior-day F&O and spot/BIGTRADEPLUS | Qualification grouped by effective thresholds or re-entry inputs | Eligibility/history checks, quantities, target legs and requests |
| Marketaux sentiment | Candidate list and post-news qualification | Daily limits, lots, contract routing and requests |

Pure, immutable qualification records are shared. ORB prepared responses make a
new request copy for each subscriber; they cannot contain an executed request.
No credentials, account orders, mutable subscriber state or risk P&L is cached.
Preferred-option warm-up identity is market-specific rather than account-specific.
Final option premiums and broker books are still checked during each execution;
quotes are not frozen or reused as historical fill prices.

## Dispatch, freshness and isolation

All scheduled templates use bounded live/simulator worker pools. The existing
`MoverStrategyEvaluationDispatcher` class name/configuration is retained, but
`dispatchAll` now handles every template. Each worker reloads the subscription,
checks status, owner, broker, template and symbol, and retains the evaluation cycle.
Same account/template/symbol work cannot overlap across cron/poll dispatches.
A failure releases that subscriber's work key and does not consume other users'
signals. Shared preparation has no broker submissions inside its synchronization.

The cache holds at most approximately 2,048 active keys, with expired keys removed
on subsequent use. Calculations run outside map locks and are coalesced through
futures, including nested preparation. Failures are not cached. Waits and cycles
are bounded to 30 seconds and cannot cross a completed five-minute candle or day.
A subscriber cannot extend a shared signal's execution deadline by reading it
again. New broker submissions check freshness; existing order/exit management
continues independently. Saturated queues defer to the next normal evaluation,
with no unbounded queue or scheduler-thread execution fallback.

A calculation that expires at a candle boundary produces a typed freshness wait,
not an application error. F&O and cash movers handle this per candidate, retain
any requests already created in the basket, and leave unsubmitted candidates
eligible for the next pass. Expired cycles skip further feed warm-up and entry
work. Feed/calculation failures remain errors; expiry handling never retries a
broker submission within the same evaluation.

An additional mover-only pass runs at five seconds after every five-minute
boundary during the normal entry monitoring window. It loads active subscriptions
and uses the same bounded dispatcher, subscription reload, overlap lock and daily
entry guards as polling. It does not change the frozen opening shortlist or
activate paused subscriptions. A still-running pass may defer this pass through
the overlap guard; ordinary polling remains the fallback. Freshness, spot trigger,
spread, sizing and execution rules remain in force.

Ordinary market calculations are process-local and recomputed in a fresh cycle
following restart. The mover opening shortlist retains its existing persistence.
For expiry straddles, `shared_straddle_signals` stores the first committed contract
selection for a date/symbol/session. Concurrent processes read the same committed
winner. A changed or missing frozen contract blocks entry rather than selecting
another strike. Each `expiry_straddle_sessions` row links to that shared signal
and retains its independent claim and outcome. Pausing/cancelling one subscription
does not remove the common signal or alter anyone else's positions. Existing
pre-upgrade sessions remain supported with a null shared signal ID.

Responses expose `sharedSignalIds`; logs and created-request audit events associate
them with the subscriber. These describe the decision, not a broker fill.

## Release and verification

Apply `src/main/resources/db/postgresql/shared-strategy-signals-schema.sql` during
a separately reviewed deployment. It adds one signal table and one nullable column;
existing rows and claims are preserved. Apply before restarting the new JAR.
Retain the table/column on rollback. No backfill, strategy activation, current-day
replay, broker authentication change or order is needed to install this change.

Tests cover single calculation across concurrent subscribers, different parameter
groups, defensive request copies, signal expiry, thread-context cleanup, failed
calculation recovery, account-specific sizing, first-subscriber failure, slow
broker isolation, exact frozen contracts, restart persistence and concurrent DB
claims. Existing entry, exit, pause/cancel and simulator protections remain required.
A future market-session test is still needed to observe actual subscriber fills.
