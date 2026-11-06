# FNO Mover final entry reward/risk

Approved on 6 November 2026 for live accounts and Simulator. Entries with source
`strategy:FNO_0925_MOVER_ATR_BREAKOUT` must have at least 1.10 reward/risk to the
original first spot target at final admission:

- CE: `(target1 - currentSpot) / (currentSpot - stopLoss)`.
- PE: `(currentSpot - target1) / (stopLoss - currentSpot)`.

Both distances must be positive, finite and based on spot levels. Exactly 1.10
passes; a 1e-9 rupee arithmetic tolerance only handles floating point equality.
Spot price and timestamp are read together from one immutable cache observation.
The observation must be at most five seconds old and not future-dated. Missing or
stale spot and invalid brackets fail closed. Original signal entry, stop and all
three targets remain unchanged. This is admission validation, not a new exit or a
promise that an eventual broker fill still has 1.10R.

Validate after quote/price preparation before initial placement and immediate
retries, during scheduled entry chase, and before manual retry/repricing of an
FNO Mover order. Other sources retain their existing entry behavior. Existing
spread, premium-slippage, signal-direction, expiry and account checks still apply.
No subscription, sizing, schema or profit-lock changes are required.

If a pending entry fails, cancel only the unfilled remainder and retain the order
ID for normal polling. Cancellation intent is saved before broker I/O. A timeout
remains pending reconciliation; fills already received are not discarded or
retroactively rejected. No replacement order is submitted. Executed positions
continue through normal stop, target and profit-lock monitoring. Manual repricing
cannot override the FNO admission rule.

Startup logs `FNO_ENTRY_RR_POLICY` with `minRewardRisk=1.10`,
`maxSpotAgeMs=5000`, `levels=ORIGINAL`, `accounts=LIVE_AND_SIMULATOR`.
`FNO_ENTRY_RR_CHECK` records spot, stop, first target, ratio and outcome. Reasons:
`FNO_ENTRY_RR_BELOW_MINIMUM`, `FNO_ENTRY_RR_SPOT_UNAVAILABLE`,
`FNO_ENTRY_RR_INVALID_BRACKET`.

The threshold is a user-approved operating choice following exploratory research,
not an established optimum. It can reject winners. Market movement between the
last check and broker fill remains possible; no post-fill bracket movement or
automatic loss exit is introduced.
