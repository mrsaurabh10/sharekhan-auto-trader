# F&O and cash opening selection and restart recovery

`FNO_0925_MOVER_ATR_BREAKOUT` and `STOCK_0925_MOVER_ATR_BREAKOUT` select movers at **09:25 IST**.
Each template persists its first opening shortlist to the primary JPA database before
qualifying candidates or creating requests. All users and broker subscriptions
share the same selection for that strategy and trading day. The existing side,
ranking, top-five limit, directional candle, sizing and exit rules are unchanged.
Cash retains its buy-only positive-gainer selection and per-stock rupee sizing.
Each template saves its own independent shortlist; cash never reuses the F&O
option-side list.

The immutable `mover_selection_snapshot` row stores the ordered symbols, CE/PE
side and spot instrument identities, with capture start/completion timestamps and
a schema version. Its key is `<template-id>@<ISO-trading-date>`. Restarted
instances read that row and do not call the current top-gainers/losers endpoint.
Concurrent instances use the first committed insert; they cannot overwrite the
winner. Even an empty opening shortlist remains frozen for that day. Existing
request checks continue to prevent duplicate daily symbol entries after restart.

The existing 09:25 subscription scheduler initiates the scan. A new capture may
start and finish only during **09:25:00–09:25:59 IST**. This bounds the live feed
capture; the endpoint does not supply a historical 09:25 ranking. A feed failure
may retry within that window. A slow response completing at/after 09:26 is not
saved. The recorded timestamps describe the actual capture, not an assertion
that the provider sampled precisely at 09:25:00.

If no original snapshot exists after the opening window (late server start,
late activation, missed scan, failed feed or first release during the day), the
strategy returns a visible waiting message and creates no new entry requests.
It never substitutes later movers or silently copies yesterday's list. Missing,
unsupported or corrupt stored data fails closed. This change cannot reconstruct
an opening list that the old application never durably saved. Any historical
recovery would need separate verified evidence and review; no inferred shortlist
or automatic import is included.

## Schema and release

This adds one small table to the **primary JPA datasource**, with no changes to
existing trading rows. A reviewed PostgreSQL DDL file is available at
`src/main/resources/db/postgresql/mover-selection-snapshot-schema.sql`.
Existing `ddl-auto=update` deployments create the entity table on startup. For
managed schemas, apply the additive DDL through the normal approved release
process before enabling this version. Do not delete snapshot rows during the
trading day or seed today's row from a current leaderboard. No retention cleanup
or production data change is part of this fix.

## Five-minute indicator history timestamps

Sharekhan historical bars may carry the final trade time within the interval's
last minute (for example `09:19:57` or `09:24:52`), rather than exactly `:59`.
The normalized five-minute history path maps those final-minute stamps to the
interval start. Already aligned starts, raw-history responses and other intervals
retain their existing behavior. Other off-grid stamps are left for consumers to
validate; this is not a general timestamp-rounding rule.

The completed-history cache still rejects unfinished, off-session, invalid OHLC
and off-grid candles and deduplicates intervals. FNO readiness still requires 76
completed candles for ATR75 plus the preceding close. Under-sized caches bootstrap
historical bars before qualification; no gate or entry rule is relaxed.
`FNO_INDICATOR_HISTORY` logs downloaded, usable, rejected, before/merged and required
counts on each bootstrap attempt. No credentials or provider response bodies are
included. Accepted history persists through the existing PostgreSQL cache.
