# Marketaux India news

The application exposes Marketaux news through an authenticated, server-side endpoint. The
provider token is never returned to the browser and must be supplied by the deployment
environment:

```sh
export MARKETAUX_API_TOKEN='replace-with-your-token'
```

Request Indian English-language market news after logging in:

```text
GET /api/market-news/india?limit=20
```

The endpoint always sends `countries=in`, `language=en`, and `filter_entities=true` to
Marketaux. It supports these optional query parameters:

| Parameter | Example | Notes |
| --- | --- | --- |
| `symbols` | `symbols=RELIANCE.NS&symbols=TCS.NS` | Repeat the parameter for each provider symbol. |
| `entities` | `entities=Reliance%20Industries` | Repeat for each Marketaux entity filter. |
| `publishedAfter` | `publishedAfter=2026-08-01` | ISO date, inclusive provider filter. |
| `publishedBefore` | `publishedBefore=2026-08-16` | ISO date, inclusive provider filter. |
| `limit` | `limit=20` | Clamped to 1–50. |
| `page` | `page=1` | Pages below 1 resolve to 1. |

The response includes only `country`, Marketaux `meta`, and `data`; the API token is omitted.

## Scheduled India sentiment collection

When `MARKETAUX_API_TOKEN` is set, the application schedules **100 calls per NSE trading day**.
The calls start at **09:20 IST** and are evenly spaced every 216 seconds; the final call is at
15:16:24 IST. Weekends and the configured `NSE_HOLIDAYS` are skipped. Calls missed while the
application is stopped are deliberately not replayed, so a restart cannot burst through the daily
provider limit.

Every attempt is recorded in `marketaux_collection_runs`, which enforces the one-call-per-time-slot
limit even across restarts. The application stores only these article entity fields in
`marketaux_entity_sentiments`: entity name, symbol, sentiment score, article UUID, article publish
time, and collection time. It does not persist article content.

The collector stores each `(article UUID, entity symbol)` combination only once. At application
startup it removes any previously stored duplicates (retaining the earliest row) and creates a
database uniqueness guard, so repeat provider pages do not grow the table unnecessarily.

Read the latest stored score for each entity without spending a Marketaux request:

```text
GET /api/market-news/india/sentiments
GET /api/market-news/india/sentiments?date=2026-08-16
```

Set `MARKETAUX_INDIA_SENTIMENT_COLLECTION_ENABLED=false` to disable the scheduler. The request
limit is hard-capped at 100 even if `MARKETAUX_INDIA_SENTIMENT_CALLS_PER_DAY` is set higher.
