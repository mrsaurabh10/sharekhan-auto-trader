# Option entry quote fallback

FNO option entries (live and Simulator) first use an available Sharekhan WebSocket
quote only when its provider, scrip, provider-echoed exchange and individual LTP or
coherent-book receipt time match the requested option. Generic cache age, bare
numeric tokens without an exchange, unknown providers, stale components and future
receipt clocks are insufficient. Existing entry signal, spread and slippage checks
still apply. If unavailable, bounded Shoonya quote reads are followed by another
Sharekhan cache check and the exact MStock contract lookup/LTP fallback.

Automated Shoonya reads use saved sessions and never launch browser authentication.
Option MStock fallback reads likewise never refresh authentication. Both have 500ms
connect and 750ms socket read timeouts. These are socket limits, not a whole-operation
wall-clock guarantee; entry deadlines are checked again before using the result and
submitting an order. Manual Shoonya controller requests retain explicit login support.

The MStock master refreshes at 07:00 IST weekdays when older than today. There is
no startup or in-session scheduled refresh; a failed morning refresh leaves the
previous master intact and requires an explicit operator retry. Download/validation must succeed before an
atomic replacement transaction; a failed persistence batch rolls back the replacement.
Old contracts never substitute for the requested expiry, strike or option side.

On 27 October 2026, the production master was still dated 13 August, with no November
contracts for BANKINDIA, MANKIND, APLAPOLLO or TATACONSUM. Refresh restores the data
needed by the existing exact-contract resolver; identity validation is unchanged.

This change does not reconnect an expired Shoonya session or change the fresh-book
requirements of the FNO early-profit/no-progress exit policy. Quote availability
alone does not guarantee an eligible signal, a broker fill or a profitable trade.
