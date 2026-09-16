# Entry chasing and Telegram actions

When an entry reaches `app.trading.entry.max-attempts` (default 5), the existing limit order stays open and price modifications stop. Broker status polling continues. Telegram offers:

- **Retry:** a fresh budget of at most five modifications to the same order, within the automatic slippage ceiling.
- **Market:** one LIMIT modification at the fresh best ask. Current entry adapters submit buys, including PE entries; no market order is submitted. This explicit action can exceed the automatic slippage ceiling. It does not guarantee a fill. If still open, new action buttons are offered.
- **Cancel:** requests cancellation of the unfilled remainder. Execution/cancellation is confirmed by broker polling; a cancellation request alone does not reject the trade.

Broker-triggered chasing also pauses for user action if its automatic slippage ceiling is reached. Initial placement validation and intraday cutoff rules still apply. Retry/Market are disabled after the intraday entry cutoff.

Partial fills stay under broker monitoring. Modification retains the broker's original total order quantity, so only its outstanding remainder is repriced; it never creates a replacement order. A terminal cancellation with a partial fill is reconciled through the existing partial-execution handling.

## Telegram configuration

Use the existing bot token, configured chat ID, and `app.telegram.webhook-secret`. Register the same secret with Telegram's webhook. Entry callbacks require the matching secret header. In a private chat, only its owner can act; in a shared chat, only its administrators/creator can act (checked with Telegram). The configured chat is the application's administration destination, including the user-scoped trades displayed there.

Callbacks contain a random decision token tied to a trade/order. Repeated clicks and buttons from an older decision cannot submit another action. Button cleanup is best effort; even if Telegram cannot remove an old button, its token is invalidated.

## Persistence and recovery

`entry_chase_control` stores each trade's order ID, chase budget, decision token, last price, notification message ID, and workflow state separately from trade status. The existing Hibernate `ddl-auto: update` configuration creates this additive table on startup.

Recovery runs every 30 seconds. Paused orders remain paused across restarts, active chases resume their remaining budget, and failed prompt deliveries are retried. Interrupted user actions are reconciled before another user action instead of automatically repeating the broker request. Unconfirmed cancellations that remain open get fresh buttons. Broker polling remains responsible for execution bookkeeping and notifications.

The service serializes callbacks, chasing, and entry polling per trade within the running application. As with the existing chase/polling schedulers, run a single trading application instance against the live account; this is not a distributed scheduler.
