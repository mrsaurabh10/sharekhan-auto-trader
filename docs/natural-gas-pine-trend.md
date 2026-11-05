# NG EMA / VWAP — futures decisions, bought ATM options

Template `MCX_NATURALGAS_PINE_TREND` is independent of the existing NG breakout,
Scalping and Trend Averaging templates. It is derived from the two identical
`NG_Intraday_Trend_Identifier` Pine indicators and the candidate strategy discussed
with the user. This implementation buys options: it never submits futures orders
or sells options short. It has no demonstrated profitability or completed market
backtest. Adding this code does not enroll users, activate a strategy or deploy it.

## Rules

Use completed five-minute candles from the exact option-underlying future.
Default bullish entry: EMA9 crosses above EMA21, the close is above daily HLC3
VWAP and confirmed hourly EMA50, and Wilder ATR14 exceeds its SMA20 by 10%.
Bearish entry reverses the direction tests. Buy the listed ATM CE for bullish,
ATM PE for bearish; ATM uses the current futures quote, not an equity spot proxy.
An earlier crossover is not remembered when filters align later.

The original triangles required only the crossover and VWAP; hourly/ATR were
originally shading conditions. This strategy explicitly enforces all filters.
The original EMA lengths and ATR expansion threshold are fixed. Optional
`PULLBACK` mode uses the preceding candle's touch of the 9/21 EMA band, close
holding EMA21, followed by a close beyond its high/low and fast EMA. Entry must
remain within one ATR of EMA9. This is a new rule, not an original Pine signal.

The hourly EMA uses only complete 12-candle hourly groups available at the
**opening** of the signal candle, matching Pine's `[1] + lookahead_on` timing.
At least 50 complete hours are required. EMA initializes from the first
available close; ATR initializes from 14 true ranges and uses Wilder smoothing.
Exact TradingView parity still depends on identical history, session and feed.
Partial hourly groups are omitted. Missing five-minute bars in today's session,
missing opening bars, duplicate intervals, malformed OHLC and missing/zero session
volume block entries. No rolling VWAP substitution is allowed.

Entries default to 18:30–23:15 IST. The signal candle must open at or after
18:30, and be no more than 75 seconds old when evaluated. The app polls after
bar close, so option execution is not an exact TradingView next-open fill.
The futures quote and coherent option bid/ask must be from the exact Sharekhan
contracts, at most 15 seconds old; maximum option spread is 2%. Existing broker
execution checks still apply. One position/pending submission per owner/broker;
maximum three attempts per IST day across both NG symbols. Request cleanup does
not erase durable fills from the cap. Re-entry requires a new completed signal
after the preceding attempt and exit. Unknown submissions block further entries.
Unfilled entry runs expire after 75 seconds; the run service cancels/reconciles
pending orders. The entry cutoff also cancels pending entries while retaining
filled positions until their configured exit.

## Futures stop and target, option fills and P&L

At admission capture the exact future's fresh price. Freeze the signal candle's
ATR, round 1.5 ATR up to futures ticks, and establish the futures bracket:

| Bought leg | Futures stop | Futures target |
| --- | --- | --- |
| CE | reference minus stop distance | reference plus 2 × stop distance |
| PE | reference plus stop distance | reference minus 2 × stop distance |

Targets use the configured R multiple, rounded to futures ticks. For example,
future 300 and ATR 2 give CE stop 297/target 306, or PE stop 303/target 294.
These are **futures prices**, not option premiums. The exact future is persisted
in `spotScripCode`, with underlying flags for SL and target; entry price and
actual entry price remain option premiums. Option slippage never shifts the
absolute futures bracket. The reference is captured at option admission because
the later option fill has no synchronized futures execution price.

An independent one-second monitor checks fresh exact futures prices, without
waiting for candle-close confirmation of an ATR stop. At a hit it durably moves
the individual position's run to closing. The common run service then cancels
pending entries/reconciles late fills and closes the option at its own price.
A missing option book cannot erase an already latched exit when futures recover.
An exit remains under reconciliation until confirmed; HTTP acknowledgement is
not proof of a fill. There is no requirement that a futures-target exit be
profitable in option-premium terms.

Generic candle-confirmed underlying SL/target processing is excluded for this
source. Its dedicated monitor owns those exits. A separate rupee max-loss limit
is required and checked by the existing run risk engine using gross option P&L.
It is an additional emergency exit per position/run, not a daily cap or a
guaranteed maximum loss. Premium profit targets and trailing overrides are
rejected to keep target/SL behavior on futures. Option quantity is the selected
whole lot count from verified contract metadata. Futures ATR is **not** multiplied
by the option lot size and represented as predicted option loss; delta is unknown.
The Pine candidate's equity-percentage futures sizing does not translate into
equivalent option risk, and is intentionally not claimed here.

The independent monitor also closes for a completed five-minute close against
EMA21 or VWAP (optional), EIA blackout, or end of session. Exit monitoring
continues when the entry subscription is paused. Each confirmed closed position
permits a later independent run, subject to the daily cap and a new signal.

## Session and EIA configuration

Configuration is application-wide for this template only:

```yaml
app:
  strategy:
    ng-pine:
      entry-mode: CROSSOVER       # or PULLBACK
      stop-atr: 1.5
      reward-r: 2.0
      max-entries: 3
      entry-start: "18:30"
      entry-cutoff: "23:15"
      square-off: "23:25"
      exit-on-invalidation: true
      avoid-eia: true
      monitor-ms: 1000
      blackouts:                 # additional windows, in IST; maintain manually
        - start: "2026-11-13T20:50:00"
          end: "2026-11-13T21:15:00"
        - start: "2026-11-25T22:20:00"
          end: "2026-11-25T22:45:00"
```

These dated examples reflect EIA's listed holiday shifts; they are not enabled
by code defaults. Regular Thursdays block 10:20–10:45 America/New_York, with
DST conversion automatic (19:50–20:15 IST in US summer, 20:50–21:15 in winter).
Maintain exceptions for historical or future tests; no calendar is downloaded.
No new entry during blackout, but entries can resume afterward on a new signal.
Default square-off is 23:25, before MCX's earlier 23:30 seasonal close; adjust
earlier for broker square-off. The entry window is a fixed research choice.

## Application use and limits

The template is listed automatically in personal and admin strategy catalogs.
Select `NATURALGAS` or `NATGASMINI`, an owned Simulator or Sharekhan account,
whole lots and the rupee emergency max loss. Mini requires its own exact
future/options, lot/tick metadata and available candles; it never substitutes
standard NG. Existing data-source policy is retained: prior-session Sharekhan
seed and current-session Sharekhan capture, or the already configured Shoonya
Simulator source. Live broker accounts retain Sharekhan current-session data.
Opening-session capture and enough hourly history may require prior warmup;
insufficient data is a waiting condition. Data availability has not been verified
against a live account in this change.

Use Simulator and review actual fills before enabling real execution. Stops and
targets are software-monitored triggers, not broker-held futures stop orders.
Gaps, outages, missing quotes, spread and option repricing can delay execution
and produce losses beyond the configured rupee limit. No option backtest or
broker paper/live activation is performed by local unit tests.

References: [Pine repainting](https://www.tradingview.com/pine-script-docs/concepts/repainting/),
[EIA release calendar](https://ir.eia.gov/ngs/schedule.html).
