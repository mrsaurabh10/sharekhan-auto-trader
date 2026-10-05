# CSV archives and Supertrend backtests

## Supported inputs

Use `date,open,high,low,close,volume` with `yyyy-MM-dd HH:mm:ss` timestamps at bar **starts in IST**. Symbols are `NIFTY` and `BANKNIFTY`; input intervals are 1 or 5 minutes. One-minute timestamps with seconds jitter (e.g. `09:21:01`) are floored to their stated minute, and the normalization count is recorded. Five-minute inputs must be exactly aligned. Files may arrive out of order; duplicate timestamps and malformed OHLC are rejected with row numbers. Zero volume is valid for these spot-index strategies.

Only 09:15 inclusive to 15:30 exclusive is retained. One-minute inputs require all five minutes to build a bar; incomplete groups are dropped and counted. Import metadata reports excluded rows, incomplete groups, internal missing five-minute slots, source filename and date coverage. Missing whole sessions are not counted against an exchange calendar.

## Store once in the application database

The new `csv_backtest_datasets` and `csv_backtest_candles` tables use the **primary application datasource**, whether configured as H2 or PostgreSQL. They are separate from the live 250-candle cache and existing trade-replay tables. Hibernate's configured schema update creates them. The source file, symbol and input timeframe are hashed to identify an immutable dataset; reuploading the identical bytes returns the same dataset ID. Overlapping archives remain separate datasets and must be selected explicitly, rather than silently mixed.

Start the application with these changes deployed. Set `BT_BASE_URL` and `BT_ADMIN_TOKEN` to your application URL and configured admin token. These endpoints require a configured, matching token.

```bash
curl "$BT_BASE_URL/api/backtests/csv/datasets" \
  -H "X-Admin-Token: $BT_ADMIN_TOKEN" \
  -F 'symbol=NIFTY' -F 'inputMinutes=5' \
  -F 'file=@/Users/saurabhgupta/Downloads/archive/NIFTY 50_5minute.csv'

curl "$BT_BASE_URL/api/backtests/csv/datasets" \
  -H "X-Admin-Token: $BT_ADMIN_TOKEN" \
  -F 'symbol=BANKNIFTY' -F 'inputMinutes=5' \
  -F 'file=@/Users/saurabhgupta/Downloads/archive/NIFTY BANK_5minute.csv'

curl "$BT_BASE_URL/api/backtests/csv/datasets" \
  -H "X-Admin-Token: $BT_ADMIN_TOKEN"
```

Use the returned dataset ID to replay any date range without reuploading:

```bash
curl -X POST "$BT_BASE_URL/api/backtests/csv/datasets/$BT_DATASET_ID/run" \
  -H "X-Admin-Token: $BT_ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"from":"2025-01-01","to":"2026-05-15","targetR":2,"slippagePoints":0,"costPoints":0,"squareOff":"15:20"}'
```

The response includes the effective RSI/ADX switches and thresholds, run configuration, data-quality counts, CE and PE summaries, and individual trades. Upload limit is 128 MB per file. Run endpoints simulate in memory and return results; report history is not yet stored in a separate database table.

## Offline runs

After compiling with Java 21 (`mvn -DskipTests compile`), run without booting Spring, connecting to a database, or contacting a broker:

```bash
java -Xmx768m -cp target/classes org.com.sharekhan.backtest.csv.CsvBacktestMain \
  --file '/Users/saurabhgupta/Downloads/archive/NIFTY 50_5minute.csv' \
  --symbol NIFTY --input-minutes 5 --from 2025-01-01 --to 2026-05-15 \
  --target-r 2 --slippage-points 0 --cost-points 0 \
  --output-dir outputs/csv-backtests/nifty-supertrend-2r
```

For BANKNIFTY change the file, symbol and output directory. For NIFTY one-minute data use `--input-minutes 1`. Outputs are `trades.csv`, `summary.csv` and `assumptions.txt`. The standalone CLI uses the source-code defaults; API replay uses the application's configured Supertrend settings.

## Execution assumptions

- Reuse the live `IndicatorService` and `SupertrendSignalRules`, with a rolling maximum of 250 candles from the last 14 calendar days and minimum 50. Prior dates warm the indicators even when the test starts later.
- Only the completed signal candle is used for qualification. Fill at the next **contiguous** bar's open, from 09:25 until before 15:20 or the chosen square-off time, whichever is earlier. Price gaps through the planned stop or target skip the entry.
- Planned stop and target levels are the live signal-candle extremes and closing-price risk multiple. The target parameter selects a single 1R, 2R, 3R or other fixed target; this is not the live quantity split/trailing-stop execution engine.
- One filled entry per CE/PE direction per symbol per day; opposite directions are simulated independently.
- Stop before target if both are touched inside the same candle. A stop crossed at the open fills at that open; target gaps fill at the target conservatively.
- Entry and exit slippage is adverse by `slippagePoints` each. `costPoints` is a fixed round-trip deduction in index points. Defaults are zero and therefore optimistic.
- Square off at the first available bar open at/after the chosen time, default 15:20. Incomplete sessions/end-of-data close at the last observed close and are reported separately. `exitBarAt` identifies the candle, not an inferred intrabar execution timestamp.
- Results are **spot-index points**, not option premiums, rupee profit or a broker-execution replay. Historical option chains and prices are required for option P&L.

The files and generated reports have not been uploaded to the production server by this task.
