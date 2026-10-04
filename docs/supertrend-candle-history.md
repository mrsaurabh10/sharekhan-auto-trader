# Supertrend RSI EMA ADX candle history

The CE and PE templates require at least **50 completed five-minute candles** for EMA50. At 09:30 there are three completed candles in today's session, so a morning evaluation needs at least 47 from previous sessions. The daily subscription completion behaviour continues to apply.

## Sources and retention

- MStock supplies today's candles. Completed candles are saved in the primary application database's `strategy_candle_history` table, keyed by spot exchange/scrip code and timestamp.
- If fewer than 50 usable candles are available, Sharekhan seeds the cache using `GET /skapi/services/historical/{exchange}/{scripcode}/5minute`. Failed or insufficient bootstrap attempts retry at most every five minutes per instrument.
- Sharekhan's [historical documentation](https://www.sharekhan.com/trading-api/documentation/historical-api) lists the interval path and separate `tradeDate` / `tradeTime` response fields. It does not document `from` / `to` query parameters. The service now filters requested date ranges locally.
- Sharekhan's [API FAQ](https://www.sharekhan.com/faq/API) describes seven days of intraday history for F&O. Actual index/scrip coverage still depends on the broker's response and a valid Sharekhan session.
- Existing captured MStock candles win over bootstrap candles at matching timestamps. Subsequent MStock responses may correct stored candles.
- Keep up to 250 completed candles per instrument, from the last 14 calendar days. Fifty is the minimum required to evaluate; 250 is a storage limit, not a new entry requirement.
- At 15:31 IST on trading days, refresh the full MStock session for active Supertrend subscriptions, including subscriptions that have already traded that day.

Incomplete candles are removed before retention or minimum-count checks. Indicator calculations keep previous-session history after today's intraday count reaches 50. A cached candle cannot trigger an entry once a newer five-minute candle should have completed; evaluations wait for the live feed to advance.

On a cold start without usable Sharekhan history or saved candles, the strategy waits until 50 completed candles are available. With only today's candles, this is 13:25 IST. Database tables are created by the application's existing Hibernate schema-update configuration; deployment does not require new broker credentials beyond the existing Sharekhan historical integration.

## Entry rules and diagnostics

| Rule | CE | PE |
| --- | --- | --- |
| Supertrend / EMA50 | Close above both | Close below both |
| RSI (inclusive) | 50–75 | 25–50 |
| ADX | Greater than 18 for BANKNIFTY, 20 otherwise | Same |
| DI | +DI greater than -DI | -DI greater than +DI |
| Candle colour | Green | Red |

RSI direction is optional and disabled by default. A one-candle RSI pullback no longer vetoes an otherwise aligned trend. Candle colour remains required by default. Configure bands and switches under `app.strategy.supertrend` in `application.yml`; each setting has a `SUPERTREND_*` environment variable. Invalid RSI ranges or nonfinite/out-of-range thresholds fail startup.

Each evaluated completed candle writes `SUPERTREND_EVALUATION` logs and a `STRATEGY_EVALUATION` audit event with QUALIFIED/REJECTED, failing rules, indicator values and daily rejection counts. Repeated scheduler polls for the same candle are suppressed per user, broker, template and symbol. Counters reset on application restart; saved audit events remain available when audit storage is enabled. Prerequisite waits (insufficient history or stale feed) retain their response messages. QUALIFIED means the signal passed, not that an order was filled.

These defaults are intended to remove overly restrictive entry filters. Profitability and trade frequency require backtesting or paper trading; they have not been validated against live broker data.
