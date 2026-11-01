# Daily NIFTY and BANKNIFTY history

The existing `DailyIndexHistoryScheduler` still captures five-minute candles at15:31 and reconciles at19:00IST (`app.market-data.daily-history-cron`). Its completed capture now requests `IndexHistoryDailyUpdater`. The updater processes only session dates completed by19:00IST; the15:31 trigger can catch up earlier days but never requests the current session. No strategy subscriptions are required.

The same worker retries at20:15,21:15,22:15,23:15IST and08:50the next morning, including weekends for catch-up and verified special sessions. Application startup requests catch-up. Enablement defaults to the existing daily-history flag; `app.index-history.enabled=false` can disable the Dhan extension alone. No Codex/OS job, live feed or order route is added.

For each index, both intervals use `market_history_candles` with uniqueness `(symbol,interval_minutes,candle_time)`. Complete sessions require every expected slot (normally375one-minute and75five-minute bars), not merely a latest timestamp. Complete dates make zero HTTP calls. Existing providers and prices are retained; validated new data fills missing rows without overwriting existing archive records. Existing exports remain five-minute only. No30-minute data is derived.

Missing dates are persisted in `market_history_update_sessions` before advancing through a bounded catch-up batch, so a restart/newer session cannot silently skip an older backlog. It also stores raw public-market-data responses, successful-parse flags, lease/retry times, completion counts and sanitized errors. A valid5-minute response survives a delayed1-minute request and is reused on retry. Failed/partial/malformed responses cannot mark a session complete or replace saved candles. Native interval disagreements invalidate both cached responses for a later refetch. Credentials/tokens never enter the ledger or logs.

Dhan requests share the stock EOD client's explicit configured credential (`app.stock-history.dhan-credential-id`), decrypted scoped token and1.1-second rate limiter. Exact NSE INDEX identities are checked in the official instrument master: IDX_I,NIFTY13,BANKNIFTY25. Requests are for one session at interval1 or5, with25-second timeout/2MB cap and no redirects. Auth/access/rate/network/master failures stop the current run; existing token-refresh mechanisms are unchanged. No broker login or refresh is initiated by this worker.

Strict validation checks equal response arrays, exact integer epochs/volume, IST day/interval alignment, finite positive OHLC with valid envelope, duplicate conflicts, complete slot coverage and consistency between native1/5-minute OHLC (0.01point tolerance). Missing/zero index volume is allowed. Out-of-session observations are retained only in raw responses. Both intervals are committed atomically; partial runs retain existing prices. Persistent30-minute leases and a20-session/10-minute batch limit coordinate retries and bound resource use. Bad stored review flags continue to require explicit review, rather than automatic price replacement.

2026regular holidays use the existing verified `SwingSessions` calendar. The8Nov2026 Muhurat normal non-CAS window14:20–15:30 is supported (70/14bars), based on [NSE/CMTR/76941,19Oct2026](https://nsearchives.nseindia.com/content/circulars/CMTR76941.pdf). Other years use the existing configured NSE weekday/holiday calendar, so the worker does not stop at year-end. Keep `app.market-data.nse-holidays` current; unconfigured weekday holidays remain incomplete/retry rather than falsely current. Additional verified special windows can be supplied as `app.index-history.special-sessions=YYYY-MM-DD=HH:mm-HH:mm,...`. Future exceptional weekend sessions require their official windows to be configured. No assumptions about unpublished special-session timing.

Useful operational checks:

```sql
SELECT symbol,interval_minutes,count(*),max(candle_time)
FROM market_history_candles GROUP BY symbol,interval_minutes;
SELECT symbol,session_date,status,one_count,five_count,completed_at,problem
FROM market_history_update_sessions ORDER BY session_date DESC,symbol;
```

Runtime logs use the sanitized `INDEX_HISTORY_DAILY` summary. The currently imported history through30Oct2026 is complete for the recent sessions, so enabling the worker on1Nov should skip both intervals without HTTP calls. The first new regular session is2Nov2026; a successful future scheduled fetch can only be confirmed after that session completes and Dhan publishes its history.
