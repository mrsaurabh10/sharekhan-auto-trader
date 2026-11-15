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

## Completed signal and pending-entry validity (15 November 2026)

The shared FNO qualification path uses completed, aligned five-minute candles
from regular sessions only, including historical ATR inputs. A forming, future
or off-session candle cannot change the ATR or a completed signal's brackets.
The loader reserves an extra candle so excluding the forming bar does not count
it toward ATR(75) warmup. Missing completed history waits instead of fabricating it.

The signal must be the expected latest completed candle, rather than merely the
latest candle the provider returned. A fresh scheduler cycle or fresh spot does
not make an old signal valid. This validation applies to the common ORB/VWAP
qualification path and the fresh-retest path. The rejection-wick filter checks
the upper wick for CE and lower wick for PE, using the existing configured limit.
Volume, body, ATR, cutoff, shortlist and directional thresholds are unchanged.

New FNO Movers requests persist server-owned `FNO_SIGNAL_V1` metadata in the
existing immutable `strategy_decision` field. Entry remains eligible from the
signal's close until the next five-minute candle completes, exclusive. For a
09:55 candle, eligibility is 10:00 <= time < 10:05 IST. The metadata is not
refreshed by a restart, quote retry, manual reprice or queue delay. It is kept
out of user-facing comments. No new column or migration is required.

Normal initial placement, immediate retries, scheduled entry chase and manual
reprice all check this deadline in addition to the original fresh-spot 1.10R
gate. Missing, malformed or expired FNO metadata fails closed with
`FNO_ENTRY_SIGNAL_MISSING_OR_EXPIRED`. Legacy unfilled requests without this
metadata cannot place or modify a new entry; existing broker order IDs go
through normal cancellation/reconciliation. Unknown broker status is not proof
of cancellation. Cancellation intent is persisted before I/O, and actual full
or partial fills remain managed. Filled positions do not lose their exits or
profit-lock state, and existing ADX/Batman/MCX entry rules retain their scope.

The existing 30-second recovery loop also checks paused, running, interrupted
and cancellation-pending FNO entry controls. An expired signal never receives
new retry buttons. A confirmed-open broker order gets cancellation of its
unfilled remainder; an unknown status stays unresolved without a blind cancel,
and a confirmed terminal order closes only its entry control. A broker fill
can race expiry/cancellation, so deadlines do not promise an exact fill cutoff.

Startup logs `FNO_ENTRY_CANDLE_POLICY` with `EXPECTED_COMPLETED_5M`,
`COMPLETED_REGULAR_SESSION`, `CE_UPPER_PE_LOWER` and `NEXT_5M_CLOSE`.
