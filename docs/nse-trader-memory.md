# Private NSE research thesis journal

Stock Analysis → Private thesis journal adapts selected Trader Memory Core methods:
thesis registration, source provenance, forward-only lifecycle, review dates,
immutable revision history and terminal postmortem notes. It is a **research-only**
contract (`NSE_RESEARCH_THESIS_V1`), not the upstream position/YAML schema. See
[MIT attribution](licenses/tradermonty-memory-MIT.txt).

## User workflow

Create a manual thesis for a saved NSE Nifty 200 symbol, or use **Draft private
thesis** in a saved Setup Fluency example. Drafting changes the local form only;
write your thesis, entry conditions, invalidation and review date before saving.
Source selection freezes the original candidate/date/score/historical market gate,
reference prices, archive capture time and input hash in the new private record.
Current-membership/reconstruction/adjustment limits remain attached to that source.

Research lifecycle: `IDEA → ENTRY_READY → TRACKING → CLOSED`; any nonterminal may
be `INVALIDATED`. Entry ready is a personal review label, not a gate override.
Tracking means following a thesis, **not** upstream ACTIVE (which requires actual
execution data). Closure/invalidation requires a research outcome and lessons.
Terminal records cannot reopen or have their core text rewritten; append
postmortem notes instead. No fills, quantities, positions, partial exits, trade
P&L, broker synchronisation or orders are created/inferred. Full upstream memory,
portfolio and discipline-gate interoperability require separate validated adapters.

Record OK/WARN/REVIEW notes and reschedule a review within one year of today.
Due reviews are displayed when opening/filtering the journal, with no background
notification or scheduled job. Amendments require an explanation and preserve
previous text. History timestamps come from the server, without backdating a
research record into a historical executed position.

## Privacy and write integrity

- All routes under `/api/stock-analysis/journal` require USER or ADMIN. Ownership
  is resolved on the server from the canonical admin/app-user account family and
  stable database ID, matching login lookup precedence. Client owner parameters
  cannot select another account. Admins have no cross-account journal browse.
- GET journal/record/sources is no-store and performs no journal file writes.
  POST create/transition/review/postmortem and PUT amend require CSRF. Sources
  are shared public-market model-book evidence; journal text remains private.
- Default persistent location: `/app/data/trader-memory/NSE_RESEARCH_THESIS_V1/`
  (host `/root/data/trader-memory`). SHA256 owner directory, UUID record directory
  and numbered immutable JSON revisions separate accounts and histories. Raw
  usernames, credentials and broker account records are not stored. Avoid putting
  secrets in user-authored notes. No journal data is committed to project Git or
  included in the public skill audit; no private records are seeded by deployment.
- UUID request IDs make identical create retries idempotent, including after later
  amendments. A conflicting body using the same request ID is rejected. Updates
  require the expected current revision; stale/concurrent edits return409.
- Complete temporary revisions are published via exclusive same-filesystem hard
  links. Existing revision files are never overwritten, even across processes.
  Readers check owner/ID/schema/history continuity and state/text invariants.
  Corrupt/gapped records fail closed rather than resetting the journal. No delete
  route exists. Bounds:500 records/account,200 revisions/record,1MB/revision plus
  field lengths. Existing data stays on the persistent mount during rollback.
- No new DB table/migration, broker call, feed, strategy setting, account setting,
  notification, order path or scheduler is added. Account/membership reads reuse
  existing repositories/services; the journal itself uses files.

## HTTP surface

- GET `/journal`, `/journal/sources`, `/journal/{id}`
- POST `/journal` creates an IDEA (`requestId`, symbol/type/source, thesis,
  entryCondition, invalidation, nextReview).
- PUT `/journal/{id}` amends nonterminal text with expectedRevision and note.
- POST `/journal/{id}/transition`, `/review`, `/postmortem` perform named actions
  with expectedRevision. None executes a broker operation.

All paths above are relative to `/api/stock-analysis`. No arbitrary file paths,
external report URLs or caller-selected account ownership are accepted.

## Verification

Focused tests cover canonical account identity, separate admin/user namespaces,
private reads/no file creation, cross-account404, idempotent retries, frozen source
provenance, stale edits, two-process-style competing publication, immutable history,
valid/invalid lifecycle transitions, due reviews, closure lessons/postmortem,
input/path checks, corruption and HTTP role/CSRF/no-delete boundaries. Browser
fixtures use synthetic private records only, including model-book drafting,
manual save/amend/review/lifecycle/postmortem, safe text, stale drafts, account
switching, errors and mobile. Production checks must avoid reading real journal
contents or creating fake entries for real accounts; use a disposable temporary
store with synthetic owners when exercising deployed classes.
