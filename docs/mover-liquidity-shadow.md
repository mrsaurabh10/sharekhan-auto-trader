# F&O mover entry observations

The observer records FNO_0925_MOVER_ATR_BREAKOUT entry-check attempts from live
and simulator accounts. It has no broker execution client or actual-trade table
writer. Spread/spot/slippage rules and subscriptions are unchanged. The prepared
candle-boundary fix is included in this release.

## Viewing results

My workspace → Strategies → **View F&O mover observations and shadow comparisons**.
The page is `/portal/mover-shadow.html`; data requires an authenticated session.
Ordinary users see their own account. Admins select an explicit account ID. JSON
exports include individual attempts, guard reasons, quote ages/provenance, exact
contract, quantity, effective runtime thresholds, actual signal candle timestamp
and shared decision IDs. `/api/strategies/mover-shadow/report?day=YYYY-MM-DD` and
`/history` enforce ownership before any data read. No endpoint places orders.

Each day compares CURRENT, IGNORE_SOFT_SPREAD and IGNORE_BOTH_SPREADS. These are
fixed observational policies, not instructions to change live guards. Other
recorded freshness, trigger, window, slippage and sizing checks still apply.
Repeated attempts and changing strikes are chronological alternatives; only the
first eligible entry per account/broker/symbol/day is modeled for each policy.
A passing entry check does not establish that a broker accepted or filled it.

Models use the recorded ask for entry and later bid for exits, with application
estimated charges. One lot exits at target 1; three lots book one at each target,
move the remaining stop to option entry cost after T1 and to the T1 option mark
after T2. Initial stock stops require a completed-minute close plus confirmation
from current spot. Target exits also require a net-profitable option premium.
Unknown depth, insufficient displayed quantity, stale/crossed/incoherent books,
unknown provider identity and missing intervals are reported explicitly. Known
insufficient depth never becomes a full synthetic fill. Even adequate displayed
depth cannot establish actual fills. Models with gaps are excluded from resolved
quote-model totals. Unknown sizes remain explicitly QUOTE_ONLY.

The current application option closer runs at 15:35, after the regular market
close. This feature does not change it or invent after-close fills. A model still
open without a supported exit shows an unresolved closing-quote status and null
net. Spot point changes are never converted to option rupee profits. No backfill
of October 23 or historical quotes is implied. Five-second snapshots can miss
brief target/stop touches: results are sampled estimates, never exact tick replay.
The 70/30 chronological history split is descriptive; at least 20 sessions and
50 unique setups is a review milestone, not proof or automatic policy tuning.

## Recording and limits

`app.strategy.mover-shadow.enabled` defaults false; deployment enables it in the
runtime Compose override. The independent daemon has a 1,024-decision bounded
queue and writes up to 100 events per batch with a two-second JDBC query timeout.
The order path only copies a bounded immutable observation into this queue.
The observer directly reads caches; it never calls REST quote/authentication APIs.
It can retain reference-counted ownership only of routes already subscribed by
normal market processing. It does not create new standalone feed routes. Retained
routes remain subject to the application's unchanged polling/rate limits.
Observer-only routes are explicitly excluded from fallback REST polling, so
retaining an observation cannot consume live quote slots or initiate authentication.
When no live route or websocket updates remain, the report records a data gap.
Provider quantities are optional: Shoonya bq1/sq1 are copied when present; missing
fields are unknown. Mixed-provider LTP/book values are not used for model exits.

Capacity inspection on October 25 found approximately 1.6 GB free on production.
Accordingly this deployment uses more conservative limits than the proposed plan:

| Configuration suffix (prefix app.strategy.mover-shadow) | Default |
| --- | ---: |
| sample-ms | 5000 (minimum enforced 5000) |
| max-rows-per-day | 50000 |
| daily-bytes | 33554432 (32 MiB payload) |
| total-bytes | 262144000 (250 MiB physical tables and indexes on PostgreSQL) |

Maximum ten exact option contracts and five underlying instruments are watched.
Market capture runs 09:25–15:35 on trading days. It releases only its own leases;
feed reference resets invalidate observer leases before any live routes restart.
Day rollover restores persisted current-day watch identities, not missing quotes.
Failures/drop counts, stale-book gaps and quota stops are visible in report health.
Free filesystem space below 512 MiB or storage quota stops only the observer.
Physical storage is checked every 30 seconds; bounded in-flight batches may cause
a small overshoot. Rows and payload limits apply per day across all accounts.
Daily health is persisted separately so a stopped capture remains diagnosable.

At 16:05 IST, per-account compact reports are archived and raw events older than
seven trading days and summaries older than 90 calendar days are deleted from
these research tables only. No database vacuum/full-table rewrite is performed.
A physical quota can remain stopped after deletion until capacity is reviewed;
PostgreSQL allocated pages/indexes do not automatically shrink. A missed archive
or capture interval is not reconstructed. Archive errors are observable in logs.

## Release and rollback

Apply `src/main/resources/db/postgresql/mover-shadow-schema.sql` transactionally
with bounded lock/statement timeouts after a decoded database backup, before app
restart. It creates only mover_shadow_events, its indexes, and mover_shadow_reports.
The app does not create production schema at startup. Keep these additive tables
on application rollback. Deploy the exact tested local JAR with the existing
runtime-only Compose process, then restart only the app. Enable the observer with
APP_STRATEGY_MOVER_SHADOW_ENABLED=true in that release's runtime override, not .env.
Disable it with false and an app-only restart, or restore the previous image.
Never delete PostgreSQL volumes or existing runtime artifacts. No test trades,
strategy enrollment/resumption, order replay or manual broker login are required.

Verification covers storage/schema repeatability, ownership isolation, per-policy
chronology, entry/exit quotes and charges, partial stops, ambiguity, quota/queue
limits, restart watches, provider metadata and feed leases. Future market capture
must still be observed after deployment; an after-hours health check proves only
readiness, not data coverage or profitable fills.
