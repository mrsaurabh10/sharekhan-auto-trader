# NSE exhaustion-hammer research

Authenticated GET `/api/stock-analysis/hammer` is DB-only, no-store, read-only
repeatable-read with a 60-second cache. Stock Analysis has a dedicated hammer
panel with evidence, filters and conditional buy/stop/2R/3R prices. It uses the
existing saved Dhan NSE_EQ archive and current Nifty 200 membership, not a new
broker feed. Dhan security IDs are research identities, never Sharekhan orders.

This is an after-close price/volume adaptation of TraderMonty's MIT-licensed
`stockbee-exhaustion-hammer-screener`, not its full US quality screen, near-close
live process or validated trading edge. Company market cap, holder quality,
earnings/news, portfolio heat and costs remain separate manual reviews. Unknown
fundamentals receive no points. No FMP/US dollar defaults are reused as INR gates.
See [source attribution](licenses/tradermonty-hammer-MIT.txt).

## Declared rules

- Minimum 220 bars/current completed day, valid OHLCV, positive recent volumes,
  full latest 20 sessions and supported cash tick ₹0.01–₹5. Identity collisions,
  conflicting duplicates, >30% close discontinuities and nonregular 2026 sessions
  fail closed (verified 1 February Budget session allowed). Future bars ignored.
- Prior 20-session average turnover ≥₹20 crore; current volume ≥100,000 shares.
- Highest high of previous 40 sessions, ties use most recent, 3–30 sessions ago;
  pullback 6–35% from that high. Prior momentum: high ≥20% above close 61 sessions
  ago OR prior close ≥10% above close 21 sessions ago. These exclude latest bar.
- Lower wick ≥40%, body ≤35%, upper wick ≤35% of range; close location ≥60%;
  lower-wick/body ≥1.5; recovery from low ≥2%. Flat range fails; zero-body ratio
  capped to 999. Undercut/reclaim of prior five-session low is optional evidence.
- Score /85: liquidity 10; momentum 10 + above-SMA50 5; pullback 6 (4 if >25%),
  undercut/reclaim 6, prior-ten red candles 3 if ≥5 or 2 if ≥3, volume 5 if ≥1.5×
  prior average or 3 if ≥1×. Geometry: wick 8/6 at ≥60/40%, body 5/4 at ≤20/35%,
  close location 5/4 at ≥80/60%, wick/body 3/2 at ≥3/1.5, upper wick 2/1 at
  ≤15/35%, recovery ≥2% gives 2. Risk 15/12/8 at ≤3/5/8% worst-entry risk.
  Watch ≥55 with every hard gate above and 0–8% worst-entry risk satisfied.
- Plans additionally require score ≥70, close >SMA50, volume ≥prior-20 average,
  known next regular session and maximum entry ≤2% above hammer high. Trigger
  high +max(tick,0.1ATR14); maximum +0.25ATR; stop low −max(tick,0.1ATR).
  Round entries/targets up and stops down to tick. Risk =maximum−stop. Targets
  maximum +2R/+3R. Entry window next regular day 09:15–15:15 IST; skip excess
  gaps or pre-entry stop breaches. Review follow-through after 3–5 sessions.
- Full 200-stock universe, ≥80% covered, ≥50% of covered above SMA50 and verified
  next session required to publish prices. Preserve watches/evidence when blocked;
  report candidate count before this independent market gate.

Adjustment status UNKNOWN, current membership rather than point-in-time membership,
older calendar verification and unsupported future/special sessions remain limits.
Stops/targets are projections for qualifying cash buys, not swing shorts or advice
on existing holdings. No orders, settings, schema, feeds or schedules are added.

## Validation

Focused Java tests cover setup evidence, weak-prior-momentum hammers, geometry,
liquidity, zero-range/doji, wide risk/tick rounding, cutoff, missing/short/stale/
conflicting data, discontinuities, identity collision, coverage, breadth and cache.
MVC tests enforce USER/ADMIN, anonymous/role denial, no-store and no write route.
Browser checks cover real saved-data replay, synthetic labelled prices, expiry,
empty/error states, evidence, all 200 reasons, mobile, injection text and zero writes.
Runtime validation and observed counts belong in release notes; these checks do
not establish profitability or complete upstream skill equivalence.
