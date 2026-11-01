# Historical rolling option archive

Manual import and reader for NIFTY/BANKNIFTY, native one-minute bars, ATM and
ATM ±1/±2, CALL and PUT. The configured range is 1 November 2021 through
30 October 2026, the last completed session when this backfill was requested.
It uses the existing explicitly selected Dhan historical-data account.

`option_history_chunks` stores gzip source blocks in PostgreSQL, with indexed
underlying/side/strike-offset/date metadata, SHA-256, capture time, public request,
validation counts, and quality flags. This avoids millions of wide rows and
indexes on a server with limited free storage. The reader decompresses only
the requested dates. It emits a CSV directly from saved data with no broker call.
An additive, manually applied schema lives in `schema.sql`; there is no startup
migration or application restart requirement.

## Source semantics and limits

The [Dhan rolling history API](https://dhanhq.co/docs/v2/expired-options-data/)
documents five years, relative strikes, OHLC, volume, strike, spot, OI and IV.
Observed on 1 November 2026:

* One- and two-calendar-day requests worked. Three-day and longer samples
  returned HTTP 400 / DH-905, despite the documented 30-day maximum. The importer
  requests disjoint two-day blocks and splits DH-905 blocks into single days.
* Returned `toDate` was **inclusive**, contrary to the documentation. Planning
  and validation use this observed convention; boundary tests cover every date
  exactly once. Changing the convention requires a new version and coverage audit.
* Expiry code 1 produced front-expiry prices in controlled samples. Code 0 was
  rejected as missing. On 27 October 2026, out-of-money CALLs at 15:29 were ₹0.05
  for code 1, versus ₹154.70 (NIFTY) / ₹1,050.25 (BANKNIFTY) for code 2.
  Code 1 is retained as source metadata; no expiry date is invented.
* BANKNIFTY uses WEEK through 13 November 2024, then MONTH from 14 November,
  per [NSE/FAOP/64506](https://nsearchives.nseindia.com/content/circulars/FAOP64506.pdf).
  NIFTY uses WEEK throughout, including monthly-expiry weeks.
* Recent responses included 15:30–15:39 observations. They are retained and
  labelled `SPECIAL_OR_OUT_OF_HOURS_REVIEW`, along with weekend observations.
  `REGULAR` means only weekday 09:15–15:29 clock eligibility, not a verified
  historical exchange-session calendar or proof of continuous coverage.

The source provides **no actual expiry date or contract security ID**. `strike`
can change minute by minute: the 29 October 2026 NIFTY ATM CALL sample contained
six different strikes. Never calculate fixed-contract P&L by joining an ATM label
over time. Contract replay requires verified expiry identity, actual strike/side,
historical lot sizes, costs, and continuous coverage of the entered contract.
The ±2 band may lose that contract when the market moves. IV/volume/OI are retained
in source units; zero premium, volume and OI are allowed. This archive does not
establish historical lot counts or executable bid/ask prices.

## Validation and resumption

Arrays must have equal lengths and timestamps must be exact minute-aligned epoch
seconds within the inclusive request. Prices are nonnegative with valid OHLC
envelopes; strike and spot must be positive; volume and OI must be nonnegative
integers. Negative/absent/nonnumeric values are quarantined without price repair.
Identical duplicates collapse, but **all** conflicting observations at a timestamp
are excluded. Structural failures quarantine the whole source block. Raw source
remains compressed in PostgreSQL, including excluded rows.

Statuses: `OK`, `PARTIAL` (excluded invalid rows), `EMPTY` (200 with no bars),
`ERROR` (source error), `SPLIT` (parent replaced by single-day attempts),
`QUARANTINED` (invalid structure). An empty/error response does not establish
that the day was an exchange holiday. Check against independent session/index
coverage before calling an import complete. `finished/success` in process output
means the requested attempt queue completed, not that every session has data.

Each database commit is a durable checkpoint. Exact saved requests are reused;
different bytes for an existing key fail closed. Existing checkpointed errors are
retained, not silently retried or overwritten. An explicit repair procedure is
needed for source errors after reviewing coverage. Authentication/rate-limit errors
stop the process. Transient network/storage exceptions also stop resumably.
This tool never refreshes tokens or logs credential values or exception messages.

Three workers share a 350 ms request-start limiter (below three requests/second).
This leaves headroom under the documented [five data calls/second](https://dhanhq.co/docs/v2/)
for existing application history jobs. Only one archive process can run per database
(session advisory lock). Free space below 700 MiB on `/app/data` stops new work;
this guard assumes the database and that mount share the production filesystem.
Verify that assumption on another deployment. Compressed source bytes and database
relation/WAL growth must be monitored; no cleanup or service restarts are performed.

## Build and run

Java 21+, org.json and PostgreSQL JDBC are required. Compile against the matching
application runtime dependencies. There is no Spring context startup.

```sh
javac --release 21 -proc:none -cp 'runtime-lib/*' -d classes \
  tools/option-history/OptionHistoryArchive.java \
  tools/option-history/OptionHistoryArchiveTest.java
java -cp 'classes:runtime-lib/*' org.com.sharekhan.analysis.OptionHistoryArchiveTest
jar cf option-history-archive.jar -C classes org/com/sharekhan/analysis
```

Back up the database to its existing protected server location and validate the
dump before manually applying `schema.sql` with `psql -v ON_ERROR_STOP=1`.
Preserve existing schemas, volumes, application settings and trading state.

The standalone tool inherits the application's existing database environment and
`APP_STOCK_HISTORY_DHAN_CREDENTIAL_ID`. It decrypts the selected active account's
saved token/client ID in memory using existing crypto configuration. No credentials
belong in arguments, files or output. The optional seed folder contains only public
market-data checkpoints; it may be empty. The existing application JAR supplies
dependencies and crypto configuration via Spring Boot's `PropertiesLauncher`:

```sh
java -Xmx192m \
  -Dloader.path=/tmp/option-history-archive.jar \
  -Dloader.main=org.com.sharekhan.analysis.OptionHistoryArchive \
  -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher \
  import /app/data/dhan-option-history-20261101/format-v2 2021-11-01 2026-10-30
```

Use `replay` instead of `import` with the same dates to prohibit API calls. A final
optional integer limits the number of planned blocks, useful for a pilot. Resume
with the **same** original dates so partition boundaries remain stable. Smaller
overlapping ad-hoc runs can create duplicate coverage blocks; the reader deduplicates
identical values and fails on conflicts, but storage counts then include duplicates.

```sh
# Same Java launcher arguments; no token is read in export mode.
... export NIFTY 0 CALL 2026-10-29 > nifty-atm-call.csv
... export BANKNIFTY -2 PUT 2026-10-30 > banknifty-minus2-put.csv
```

CSV contains IST time, epoch, underlying, relative strike, side, OHLC, volume,
actual strike, spot, OI, IV, session flag and `expiry_identity=UNKNOWN`.
It verifies the source checksum and excludes invalid rows. It does not filter
special/out-of-hours rows automatically; researchers must select eligible sessions.
There is no order execution, live feed, strategy activation or recurring option
job in this manual archive utility. Existing daily index/stock updaters are separate.

Read-only progress:

```sql
SELECT underlying, option_side, strike_offset, status, count(*) AS blocks,
       sum(valid_bars) AS valid_bars, sum(regular_bars) AS regular_bars,
       sum(invalid_bars) AS excluded_invalid_bars,
       min(to_timestamp(first_epoch)) AS first_time,
       max(to_timestamp(last_epoch)) AS last_time
FROM option_history_chunks GROUP BY 1,2,3,4 ORDER BY 1,2,3,4;
SELECT pg_total_relation_size('option_history_chunks') AS archive_bytes,
       pg_database_size(current_database()) AS database_bytes;
```
