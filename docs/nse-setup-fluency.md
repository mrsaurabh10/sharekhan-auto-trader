# NSE Setup Fluency research model book

Stock Analysis → Setup model book adapts selected Stockbee Setup Fluency Trainer
methods to the existing native NSE Momentum Burst evaluator. This is study of
setup-day reference moves, not original live alerts, simulated fills, executed
trades or stop-adjusted profitability. See [MIT attribution](licenses/tradermonty-fluency-MIT.txt).

## Dated candidates and persistence

GET `/api/stock-analysis/fluency` is USER/ADMIN, no-store and read-only/repeatable-read
with a 60-second cache. It reads saved Dhan NSE_EQ candles and current Nifty 200
membership, reconstructing the last 20 verified regular setup dates. Momentum
scoring/filters use only candles through each setup date. Only native watches or
conditional candidates (score ≥55 plus existing stock filters) enter the study.
Historical breadth/coverage market gate and stock-level plan qualification are
separate evidence. A watch in MARKET_CAUTION was not a published buy plan.

Historical reconstructions use today's archive/current members, not point-in-time
membership or original live reports. Survivorship/selection bias, unverified
adjustment, archive capture times and per-candidate input hashes remain visible.

POST `/api/stock-analysis/fluency/capture` requires ADMIN and CSRF. It freezes the
latest 20 eligible dates under the existing persistent mount, by default
`/app/data/setup-fluency/NSE_SETUP_FLUENCY_V1/YYYY-MM-DD.json` (host `/root/data`).
The database remains read-only. A completed temporary file is published via an
exclusive hard link on the same filesystem; existing version/date snapshots are
never overwritten, including concurrent publication. Repeated capture is
idempotent. Incomplete-universe/coverage/session snapshots are not saved. Partial
capture failures preserve already-published dates and return an error for review.
GET never creates directories or saves files. No new schema or scheduler exists.

Latest 60 saved/preview setup dates are reported. Saved snapshots take precedence
over reconstructed previews, preserving original scores/references even if the
archive later changes. Source-price changes at the setup date invalidate outcome
metrics instead of silently rebasing the frozen reference. Reference corrections
require separate review/new methodology version; no destructive reset route exists.
The UI displays the latest 100 matching examples, with a date filter for older rows.
Human annotations and automatic rule promotion are outside this selected-method
integration. All authenticated users can read shared market research; only admin
can save the shared book. It contains no account/trade/credential records.

## Exact forward outcomes

Reference = setup-day CLOSE, reference stop = setup-day LOW. These differ from the
Momentum planner's next-session trigger/maximum-entry/ATR stop. Subsequent exact
three/five regular sessions are obtained from the existing verified 2026 calendar;
missing dates cannot be replaced with later bars. Unfinished horizons are PENDING;
missing expected sessions are DATA_GAP; source identity/setup correction/invalid
OHLCV/conflicts/>30% close discontinuities are DATA_REVIEW. Unsupported special
or year boundaries are CALENDAR_UNAVAILABLE. Metrics are null in these states.
Future bars outside the cutoff or horizon cannot change earlier measurements.

Close return =100×(last close/reference−1); MFE/MAE =100×(window maximum high or
minimum low/reference−1). Excursions are signed and use the full window, including
moves after any reference-stop touch. First expected-session low ≤reference stop
records the first touch. Daily OHLC cannot establish intraday order/fill sequence;
no targets, brokerage, gaps, spread or stop-fill P&L is inferred.

Outcome-label precedence: FAILED_STOP first; otherwise STRONG_WINNER if MFE≥12%
or close≥8%; WORKED if MFE≥6% or close≥4%; FAILED_FADE if close≤−2%; CHOPPY_FAILURE
if MAE≤−5% and close<2%; otherwise NEUTRAL. These are upstream study labels, not
actual trade winners/losers. Both horizons retain their own maturity and evidence.

Cohorts group all examples, primary trigger, native score band, historical market
gate, saved/preview state and overlapping setup tags. Only valid five-session
measurements enter means/touch/follow-through rates; metrics are withheld below
five observations. Pending/unavailable denominators stay visible. Follow-through
means WORKED/STRONG_WINNER, not realised win rate. Overlapping dates/symbols/tags
are not independent observations. No strategy/gate/risk rule is automatically changed.

## Verification

Focused tests cover exact holiday-aware windows, future-data isolation, stop-label
precedence/full-window interpretation, pending versus missing, identity/source
corrections, invalid/conflicting data, horizon isolation, classification/cohort
sample handling, cutoff candidate hashing, read-only reporting, explicit idempotent
capture, immutable atomic file storage/corruption and ADMIN/CSRF/route protections.
Browser fixtures cover real saved-data replay, date filters, cohort metrics,
explicit simulated save, admin denial, errors, mobile and safe text. Deployment
notes distinguish fixture UI checks, actual deployed-class probes and snapshot writes.
No profitability/full-upstream-skill certification is implied.
