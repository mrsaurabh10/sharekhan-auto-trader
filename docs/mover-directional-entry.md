# Mover entry candle direction

The `FNO_0925_MOVER_ATR_BREAKOUT` and `STOCK_0925_MOVER_ATR_BREAKOUT`
templates require their latest completed five-minute signal candle to agree
with the entry direction:

- F&O CE and cash BUY: close strictly greater than open.
- F&O PE: close strictly less than open.
- A flat candle does not qualify for either direction.

The check runs before choosing between the morning opening-range and
VWAP-reclaim/base-break setups, so neither setup bypasses it. A forming candle
does not determine signal direction, and the strategy does not backfill an
older matching candle when the latest completed one fails.

Rejection remains a waiting outcome. Existing qualification audit records and
subscription messages explain the required direction. The candidate remains
eligible for a later matching completed candle; rejecting direction alone does
not consume the daily entry allowance. Historical retained-trade screens that
assume no later replacement entries therefore do not predict live P&L.

Only these two mover templates call the directional qualifier. Existing manual
F&O templates retain their original qualification behavior. No new ORB
VWAP-distance cap, volume threshold, sizing, selection, stop, target or exit
change is included in this rule.
