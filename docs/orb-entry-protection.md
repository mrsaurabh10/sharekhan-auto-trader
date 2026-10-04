# ORB entry protection and daily completion

Both ORB CE and PE evaluate a breakout only within 120 seconds of its five-minute candle completing. Historical breakouts are no longer replayed later in the session. Entry still uses the next candle's open, with these additional checks immediately before submission:

- A spot quote must be finite, positive, observed within 30 seconds, and not timestamped in the future.
- Spot must remain beyond the opening range in the breakout direction.
- Absolute spot deviation from the planned entry must be no more than 25% of the original entry-to-stop distance.

ORB warms the spot feed while monitoring the range. Missing or stale quotes produce a waiting response. Configure the limits under `app.strategy.orb` or with `ORB_MAX_SIGNAL_DELAY_SECONDS`, `ORB_MAX_SPOT_AGE_SECONDS`, and `ORB_MAX_ENTRY_DEVIATION_RISK`. These checks use the quote observed before broker submission; they cannot guarantee the eventual fill price.

For subscriptions with one evaluation result per day, a duplicate completes the day only when the returned request belongs to the same user, broker, symbol, strategy source and CE/PE direction, and was created today. Rejected, failed, cancelled or unidentified requests cannot complete the day. Unrelated duplicates do not replace the subscription's generated-request reference. Existing subscriptions are reused only for the requested broker and source, and evaluation preserves the configured source.

Duplicate-entry lookup and submission protection were left unchanged at the user's request.
