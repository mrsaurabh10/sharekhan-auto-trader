# Strategy pause and resume

Personal workspace and admin subscriptions have Pause / Resume controls and a
Paused filter. After either action, the list switches to All so the changed row
stays visible. Cancel is still available on active and paused subscriptions;
cancelled subscriptions cannot resume.

Pause changes the existing subscription to PAUSED. Polling, session and mover
snapshot jobs select ACTIVE/TRIGGERED only. A queued mover worker reloads state,
and run entry checks the subscription under a database lock. An entry that has
already passed the entry gate or reached the broker can still complete. Pause
does not cancel pending requests or broker orders, close positions, close strategy
runs, or disable stop-loss/target/exit management.

Resume changes PAUSED to ACTIVE and allows the next normal scheduled evaluation.
It retains the subscription ID, settings, source, run identity, generated request
and completedAt marker. Daily/session deduplication and existing closed-run guards
remain effective. Missed entry windows are not replayed. User resume checks broker
readiness; admin uses existing legacy/onboarded readiness policy. Ownership is
checked on the server; both endpoints require authentication and CSRF.

POST /api/strategies/subscriptions/{id}/pause
POST /api/strategies/subscriptions/{id}/resume
GET /api/strategies/subscriptions?status=PAUSED

Transitions are idempotent for the intended state; unsupported terminal transitions
return 409. Enrollment includes paused schedules in its duplicate check and asks
the user to resume the saved subscription. Lifecycle changes and enrollment lock
rows; conditional scheduler writes and symbol-only updates cannot reactivate a
paused subscription. No schema migration is required (status is already text).

The existing run-level Close all & pause workflow retains its prior cancellation
semantics. This feature adds a separate reversible scheduling action.
