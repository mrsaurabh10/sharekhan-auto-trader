# NSE daily skill-input adapter — first integration step

This standalone research tool converts the application's saved Nifty 200 daily
market archive into explicit local skill inputs. It does not change the running
application, installed skills, data, schedules, risk gates or order paths.

## Run

From the application repository, use Python3.11+:

```sh
python3 tools/nse-skill-adapter/export_snapshot.py \
  --as-of 2026-10-30 --output /absolute/private/path/source.jsonl
python3 tools/nse-skill-adapter/adapter.py \
  --snapshot /absolute/private/path/source.jsonl --as-of 2026-10-30 \
  --output /absolute/private/path/bundle
python3 -m unittest discover -s tools/nse-skill-adapter -p 'test_*.py' -v
```

The first command uses the configured `sharekhan-prod` SSH alias and a PostgreSQL
`REPEATABLE READ READ ONLY` transaction. It selects only market candles and
constituent provenance, never account, credential or trading tables. No broker
history request, session renewal, migration or remote file write is involved.
The source currently is the already imported **Dhan archive**, not live Sharekhan
prices; no provider fallback is attempted. The export refuses existing paths.

The adapter itself is offline and uses only the Python standard library. The
as-of must be an explicit completed regular session in the verified2026 calendar;
it does not silently choose today's date. It preserves the source snapshot and
refuses existing output directories.

## Output contract

Consumers first call `adapter.verify_bundle(Path(...))` to verify the `COMPLETE`
marker and SHA256 checksums. A directory without that marker is not published.

| File | Contents |
|---|---|
| `manifest.json` | Source checksum, source/capture cutoff, universe policy, recent sessions, counts and limits |
| `instruments.json` | All200 stocks, ISIN/provider IDs, tick history, capture/import provenance, flags, blocking reasons and validated bars |
| `prices.json` | Only input-ready stocks, keyed by symbol, with ascending `date/open/high/low/close/volume` bars |
| `COMPLETE` | Hashes of all three payload files, written last |

Prices are INR per cash share, volume is share count, dates are IST trading-session
dates and interval is daily. Dhan security IDs remain explicitly provider-specific;
`sharekhan_scrip_code` is null. Price numbers exported to skills use JSON floating
point; this format is research input, not an exact-money order calculation format.

An input-ready stock requires one unambiguous Dhan ID, valid finite OHLC/volume,
at least220 completed bars, all recent20 expected sessions, positive recent
volume, and no >30% adjacent-close discontinuity requiring corporate-action review.
Any invalid/conflicting row blocks the entire stock. Cross-symbol ID collisions
block all affected stocks. Exact duplicate bars collapse; conflicting prices
never silently win. Snapshot trailer counts detect incomplete exports.

These are **data gates**, separate from the app's market/trend/setup/liquidity,
event and portfolio gates. `RESEARCH_INPUT_READY` is not a trade recommendation.
Corporate-action adjustment stays UNKNOWN; no prices are repaired. The latest
constituent snapshot is not historical membership. Capture after the candle date
does not establish point-in-time knowledge. Before2026, session-calendar audit
is unavailable and explicitly flagged.

Regular2026 holidays mirror `SwingSessions.java`, with a regression check for
drift. The historical Sunday Budget session on1Feb2026 is explicitly included
from [NSE/CMTR/72349](https://nsearchives.nseindia.com/content/circulars/CMTR72349.pdf),
normal market09:15–15:30IST. Other nonregular2026 dates require review rather than
being dropped. This changes only the adapter calendar, not app entry scheduling.

## Installed-reader acceptance check

Use the existing trading-bundle virtual environment, since reader imports can
need its installed packages:

```sh
/absolute/path/to/trading-skills-codex-port/.venv/bin/python \
  tools/nse-skill-adapter/check_consumers.py \
  --bundle /absolute/private/path/bundle \
  --skills-root /absolute/path/to/codex-trading-skills/skills
```

The check forbids network connections and subprocesses, loads five installed
Stockbee offline readers and compares every symbol, date and OHLCV value. It
does not run candidate scoring, publish signals or validate US thresholds for INR.

## Acceptance evidence — 1 November2026

- 20 offline validation tests passed.
- Read-only source:60,330 daily bars for200 stocks through30Oct2026.
- 197 input-ready stocks /59,716 bars; ICICIAMC and VAML blocked for short history,
  POLICYBZR for price-discontinuity review. Zero invalid-source-row findings.
- All five readers passed lossless loading of all197 stocks /59,716 bars:
  momentum burst, exhaustion hammer, episodic pivot,20% study and setup fluency.
- No per-skill strategy validation, app integration, deployment or order execution.

Raw source/bundles remain outside Git under the parent project's private outputs.
Next: define and validate explicit NSE/INR momentum-burst policies, then connect
its research output to report contracts. VCP additionally needs a local provider
path and an appropriate declared benchmark. Fundamentals/events, portfolio,
derivatives and original-market tools remain separate later steps.
