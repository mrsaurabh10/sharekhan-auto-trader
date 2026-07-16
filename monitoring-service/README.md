# Sharekhan Trade Advisory Monitor

Small, read-only Go service that polls the trader's authenticated snapshot endpoint, evaluates advisory rules, and optionally sends Telegram messages. It never receives broker credentials and cannot place or modify orders.

## Advisories

- Missing stop-loss protection
- Missing or stale market data
- Stop-loss proximity with remaining distance
- Stop-loss breached while the trade remains active
- Explicit T1, T2, and T3 proximity advisories with remaining distance
- Move-stop-loss-to-cost suggestion
- Stuck exit order
- Daily closed-trade summary at 15:40 IST

Each advisory is deduplicated and rate-limited in `/app/data/advisory-state.json` on the `monitor-data` Docker volume. Price rules are suspended when their relevant instrument or spot price is stale.

`PROXIMITY_FRACTION=0.10` means an advisory is generated after price enters the final 10% of the applicable entry-to-level or adjacent-level range. T1, T2, T3, and SL each have independent deduplication keys and cooldowns. Mixed price-source trades, such as option entry with spot targets, use the configured target ladder to calculate a comparable monitoring range.

## Trader configuration

Set the same strong random value in the trader host and monitor host:

```bash
# Trader host
MONITORING_API_TOKEN=replace-with-a-long-random-token

# Monitor host
TRADER_API_TOKEN=replace-with-the-same-token
```

The monitor host must be able to reach:

```text
GET https://<trader-host>/internal/monitoring/snapshot
X-Monitoring-Token: <token>
```

Expose that path only to the monitoring host using a firewall, VPN, or reverse-proxy allowlist. TLS is required between hosts.

## Deployment

```bash
cp .env.example .env
# Edit .env. Leave SHADOW_MODE=true initially.
docker compose build
docker compose up -d
docker compose logs -f trade-monitor
```

Run in shadow mode for several market sessions. Shadow mode logs advisories but does not call Telegram. After validating thresholds, set `SHADOW_MODE=false`, configure the Telegram variables, and restart.

Health endpoint:

```bash
curl http://127.0.0.1:8090/healthz
```

## Verification

Run tests without installing Go locally:

```bash
docker build --target build -t sharekhan-trade-monitor-build .
```
