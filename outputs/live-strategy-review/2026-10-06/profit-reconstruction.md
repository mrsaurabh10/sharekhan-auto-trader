# Profit reconstruction — NIFTY Supertrend CE, 6 October 2026

## Reference position

Trade 9934: NIFTY 22600 CE expiring 06/10/2026; 3 lots / 195 units. Simulated option entry 57.70 at 09:27:11 IST. Planned spot entry 22600.10; spot stop 22566.85. The actual spot price at option execution is not recorded in this analysis, so spot gains below use the strategy's signal-close reference.

## Verified spot outcomes

Using the server's 75 stored five-minute NIFTY candles for 6 October:

| Exit scenario | Spot level | Gain from planned entry | First crossing candle (IST) |
|---|---:|---:|---|
| T1: current unstaged, non-trailing policy | 22633.35 | 33.25 points / 1R | 09:45–09:50 |
| T2: alternative fixed 2R policy | 22666.60 | 66.50 points / 2R | 09:55–10:00 |
| T3: alternative fixed 3R policy | 22699.85 | 99.75 points / 3R | 11:45–11:50 |

The lowest stored five-minute low from 09:25 onward was 22589.55, above the stop. All three targets were reached. Five-minute bars identify crossing windows, not exact tick times or executable option prices.

Trailing was disabled and no staged target group was recorded. Current monitor code normally exits all units when the first target is crossed, subject to valid option quotes, price safety and its profitability gate. Removing the Sharekhan UPDATE closure alone would not configure a hold until T2, T3 or end of day. A historical spot-only reconstruction supports T1; the available records cannot prove that a valid executable option quote and target callback were available at that time.

## Option result and missing data

Known simulated execution: entry 57.70, Sharekhan UPDATE exit 91.90 at 09:56, quantity 195. Premium gain 34.20 per unit; gross P&L 6669.00; stored cost estimate 65.75; stored net P&L 6603.25.

The option price at the expected T1 exit and alternative T2/T3 exits is unavailable. No option profit or profit difference is fabricated from spot points. In particular, spot gains multiplied by 195 do not establish option P&L.

Read-only data recovery attempts:

- Sharekhan 1minute and 5minute history for scrip 40701: filtered 6 October calls returned zero candles. Unfiltered calls returned 1896 one-minute / 385 five-minute option candles, with dates through 5 October only. Spot history likewise ended on 5 October.
- Authenticated mStock historical calls failed with a broker instrument/security-ID mismatch. A retry using explicit numeric exchange segments also failed.
- Authenticated mStock intraday calls for segment 2 / token 40701 and segment 1 / token 26000 returned success with zero candles.
- No separate persisted tick/option-candle history table was found. The contract's retained audit quotes were at entry and the actual 09:56 exit, rather than at T1/T3.

Option minute or tick data for NIFTY06OCT26C22600 on 6 October is needed to estimate the counterfactual premiums. Even with minute OHLC, the option price at an intra-minute spot crossing remains an estimate unless synchronized tick data is available.

Raw, non-secret response evidence is saved beside this report in profit-market-data.json, sharekhan-unfiltered-market-data.json, mstock-market-data.json, mstock-direct-market-data.json and mstock-intraday-market-data.json. Trade records were not changed.
