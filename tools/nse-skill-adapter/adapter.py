#!/usr/bin/env python3
"""Validate an archived daily NSE snapshot and emit local skill input files.

No network, database, broker, order or installed-skill mutation dependencies.
"""
import argparse
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta
from decimal import Decimal, InvalidOperation
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
from zoneinfo import ZoneInfo

IST = ZoneInfo('Asia/Kolkata')
HOLIDAYS = set('2026-01-15 2026-01-26 2026-03-03 2026-03-26 2026-03-31 '
    '2026-04-03 2026-04-14 2026-05-01 2026-05-28 2026-06-26 2026-09-14 '
    '2026-10-02 2026-10-20 2026-11-10 2026-11-24 2026-12-25'.split())
SPECIAL = {'2026-11-08'}
VERIFIED_HISTORY_SESSIONS = {'2026-02-01': 'https://nsearchives.nseindia.com/content/circulars/CMTR72349.pdf'}


class InputError(ValueError):
    pass


def iso_day(value):
    if not isinstance(value, str) or not re.fullmatch(r'\d{4}-\d{2}-\d{2}', value):
        raise InputError('invalid ISO session date')
    return date.fromisoformat(value)


def instant(value):
    stamp = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if stamp.utcoffset() is None:
        raise InputError('capture timestamp needs timezone')
    return stamp


def regular(day):
    return day.year == 2026 and day.weekday() < 5 and day.isoformat() not in HOLIDAYS


def required_sessions(as_of):
    if not regular(as_of) or as_of.isoformat() in SPECIAL:
        raise InputError('as-of must be a verified regular 2026 session')
    days, day = [], as_of
    while len(days) < 20:
        if day.year != 2026:
            raise InputError('recent coverage crosses unsupported calendar year')
        if regular(day) or day.isoformat() in VERIFIED_HISTORY_SESSIONS:
            days.append(day.isoformat())
        day -= timedelta(days=1)
    return days


def number(value, *, integer=False, positive=False):
    if isinstance(value, bool) or value is None:
        raise InputError('invalid numeric value')
    try:
        result = Decimal(str(value))
    except InvalidOperation as exc:
        raise InputError('invalid numeric value') from exc
    if not result.is_finite() or result < 0 or result > Decimal('1e15'):
        raise InputError('numeric value outside finite nonnegative bounds')
    if positive and result <= 0:
        raise InputError('nonpositive price/tick')
    if integer and result != result.to_integral_value():
        raise InputError('fractional volume/epoch')
    return int(result) if integer else float(result)


def parse_snapshot(path):
    if not 0 < path.stat().st_size <= 100 * 1024 * 1024:
        raise InputError('snapshot size outside supported bounds')
    records = []
    try:
        for line in path.read_text().splitlines():
            if line.strip():
                records.append(json.loads(line, parse_float=Decimal))
    except (ValueError, TypeError) as exc:
        raise InputError('invalid snapshot JSON') from exc
    if len(records) < 3 or any(not isinstance(r, dict) for r in records):
        raise InputError('incomplete snapshot')
    meta, end = records[0], records[-1]
    if (meta.get('type'), meta.get('schema'), meta.get('provider'), meta.get('exchange_segment')) != (
            'snapshot', 'NSE_DAILY_SNAPSHOT_V1', 'DHAN', 'NSE_EQ'):
        raise InputError('unsupported snapshot source/schema')
    if meta.get('universe') != 'NIFTY 200' or meta.get('membership_policy') != 'LATEST_SNAPSHOT_NOT_POINT_IN_TIME':
        raise InputError('unsupported universe policy')
    if end.get('type') != 'end':
        raise InputError('snapshot completion marker missing')
    counts = Counter(r.get('type') for r in records[1:-1])
    if set(counts) - {'member', 'candle'} or counts['member'] != end.get('members') or counts['candle'] != end.get('candles'):
        raise InputError('snapshot row counts/types disagree')
    return meta, records[1:-1]


def build(path, as_of, *, minimum_bars=220):
    required = required_sessions(as_of)
    if not 20 <= minimum_bars <= 2000:
        raise InputError('minimum bars must be between20 and2000')
    meta, records = parse_snapshot(path)
    if iso_day(meta['as_of']) != as_of:
        raise InputError('snapshot and requested as-of disagree')
    exported = instant(meta['exported_at'])
    local_export = exported.astimezone(IST)
    if local_export.date() < as_of or (local_export.date() == as_of and local_export.hour < 18):
        raise InputError('snapshot predates requested completed session')
    snapshot_day = iso_day(meta['snapshot_date'])
    if snapshot_day > local_export.date():
        raise InputError('membership snapshot is future-dated')
    members, raw, problems, isins = {}, defaultdict(list), defaultdict(set), set()
    for r in records:
        symbol = r.get('symbol')
        if not isinstance(symbol, str) or not re.fullmatch(r'[A-Z0-9][A-Z0-9&._-]{0,63}', symbol):
            raise InputError('invalid symbol')
        if r['type'] == 'member':
            if symbol in members:
                raise InputError('duplicate constituent')
            if (iso_day(r['snapshot_date']) != snapshot_day or
                not re.fullmatch(r'IN[A-Z0-9]{10}', str(r.get('isin', ''))) or
                not re.fullmatch(r'[a-fA-F0-9]{64}', str(r.get('source_sha256', ''))) or
                not str(r.get('source_url', '')).startswith('https://')):
                raise InputError('invalid constituent provenance')
            if r['isin'] in isins:
                raise InputError('ambiguous constituent ISIN')
            isins.add(r['isin'])
            members[symbol] = r
        else:
            raw[symbol].append(r)
    if len(members) != 200 or set(raw) - set(members):
        raise InputError('requires200 constituents and member-only candles')
    prices, instruments, deduplicated = {}, [], 0
    for symbol in sorted(members):
        rows, identities, ticks, captures, imports = {}, set(), set(), [], set()
        for r in raw[symbol]:
            try:
                day = iso_day(r['date'])
                if day > as_of:
                    raise InputError('future bar in bounded snapshot')
                security = r['security_id']
                if not isinstance(security, str) or not re.fullmatch(r'\d{1,32}', security):
                    raise InputError('invalid Dhan security ID')
                identities.add(security)
                stamp = datetime.fromtimestamp(number(r['epoch'], integer=True), IST)
                if stamp.date() != day:
                    raise InputError('epoch and IST session date disagree')
                captured = instant(r['captured_at'])
                if captured > exported or captured.astimezone(IST).date() < day:
                    raise InputError('capture timestamp outside source bounds')
                captures.append(captured.isoformat())
                if not isinstance(r['import_id'], str) or not r['import_id'] or len(r['import_id']) > 80:
                    raise InputError('missing/invalid import provenance')
                imports.add(r['import_id'])
                if r.get('adjustment_status') != 'UNKNOWN':
                    raise InputError('unsupported adjustment assertion')
                tick = number(r['tick_size'], positive=True)
                ticks.add(tick)
                bar = {'date': day.isoformat(), **{k: number(r[k], positive=True) for k in ('open','high','low','close')},
                       'volume': number(r['volume'], integer=True)}
                if not bar['low'] <= min(bar['open'], bar['close']) <= max(bar['open'], bar['close']) <= bar['high']:
                    raise InputError('invalid OHLC envelope')
                if bar['date'] in rows:
                    if rows[bar['date']] != bar:
                        raise InputError('conflicting duplicate session')
                    deduplicated += 1
                rows[bar['date']] = bar
            except (KeyError, ValueError, TypeError, OverflowError, OSError):
                problems[symbol].add('INVALID_SOURCE_ROW')
        if len(identities) != 1:
            problems[symbol].add('MISSING_OR_AMBIGUOUS_SECURITY_ID')
        bars = sorted(rows.values(), key=lambda r: r['date'])
        if len(bars) < minimum_bars:
            problems[symbol].add('INSUFFICIENT_HISTORY')
        missing = sorted(set(required) - rows.keys())
        if missing:
            problems[symbol].add('MISSING_RECENT_SESSIONS')
        if any(rows[d]['volume'] <= 0 for d in required if d in rows):
            problems[symbol].add('NONPOSITIVE_RECENT_VOLUME')
        if any(abs(b['close'] / a['close'] - 1) > .30 for a, b in zip(bars, bars[1:])):
            problems[symbol].add('CORPORATE_ACTION_REVIEW')
        unusual = [b['date'] for b in bars if iso_day(b['date']).year == 2026
                   and not regular(iso_day(b['date'])) and b['date'] not in VERIFIED_HISTORY_SESSIONS]
        if unusual:
            problems[symbol].add('NONREGULAR_SESSION_REVIEW')
        flags = ['ADJUSTMENT_UNKNOWN', 'IDENTITY_FROM_ARCHIVE_NOT_LIVE_MASTER', 'NOT_POINT_IN_TIME_UNIVERSE']
        if any(iso_day(b['date']).year != 2026 for b in bars):
            flags.append('OLDER_SESSION_CALENDAR_UNVERIFIED')
        reasons = sorted(problems[symbol])
        instruments.append({'symbol': symbol, 'isin': members[symbol]['isin'], 'provider': 'DHAN',
            'exchange': 'NSE', 'segment': 'NSE_EQ', 'currency': 'INR', 'timezone': 'Asia/Kolkata',
            'dhan_security_ids': sorted(identities), 'sharekhan_scrip_code': None,
            'tick_sizes_observed': sorted(ticks), 'bar_count': len(bars),
            'first_date': bars[0]['date'] if bars else None, 'last_date': bars[-1]['date'] if bars else None,
            'capture_first': min(captures) if captures else None, 'capture_last': max(captures) if captures else None,
            'import_ids': sorted(imports), 'flags': flags, 'blocking_reasons': reasons,
            'missing_recent_sessions': missing, 'nonregular_sessions': unusual,
            'verified_special_sessions': [b['date'] for b in bars if b['date'] in VERIFIED_HISTORY_SESSIONS],
            'status': 'BLOCKED' if reasons else 'RESEARCH_INPUT_READY', 'bars': bars})
        if not reasons:
            prices[symbol] = bars
    by_id = defaultdict(set)
    for item in instruments:
        for security in item['dhan_security_ids']:
            by_id[security].add(item['symbol'])
    collisions = set().union(*(symbols for symbols in by_id.values() if len(symbols) > 1)) if by_id else set()
    for item in instruments:
        if item['symbol'] in collisions:
            item['blocking_reasons'] = sorted(set(item['blocking_reasons']) | {'CROSS_SYMBOL_SECURITY_ID_COLLISION'})
            item['status'] = 'BLOCKED'
            prices.pop(item['symbol'], None)
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    manifest = {'schema': 'NSE_SKILL_DAILY_V1', 'as_of': as_of.isoformat(), 'currency': 'INR', 'interval': '1d',
        'timezone': 'Asia/Kolkata', 'source_sha256': digest, 'source': meta,
        'minimum_bars': minimum_bars, 'recent_sessions': required, 'universe_count': len(members),
        'verified_special_session_sources': VERIFIED_HISTORY_SESSIONS,
        'eligible_count': len(prices), 'blocked_count': len(members)-len(prices),
        'exact_duplicate_bars_collapsed': deduplicated,
        'blocking_reason_counts': dict(sorted(Counter(reason for i in instruments for reason in i['blocking_reasons']).items())),
        'limits': ['Research inputs only; not orders or a skill/strategy validation.',
            'Adjustment unknown; original prices are never repaired.',
            'Current constituent snapshot; not a survivorship-free or point-in-time dataset.',
            'Price as-of is not knowledge as-of: capture may postdate the candle.',
            'No market/portfolio/event gate is implied by input readiness.',
            'INR threshold and benchmark policies remain downstream responsibilities.',
            'Calendar uses app SwingSessions2026 holidays plus sourced1Feb Budget session; older sessions not calendar-audited.']}
    return manifest, instruments, prices


def write_bundle(path, manifest, instruments, prices):
    if path.exists():
        raise InputError('output bundle already exists')
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix='.nse-skill-', dir=path.parent))
    try:
        for name, value in [('manifest.json', manifest), ('instruments.json', instruments), ('prices.json', prices)]:
            data = json.dumps(value, indent=2, sort_keys=True, allow_nan=False, default=lambda v: float(v) if isinstance(v, Decimal) else str(v)) + '\n'
            (temporary / name).write_text(data)
        # Link files into a newly reserved directory; refuse existing destinations.
        path.mkdir()
        try:
            for child in temporary.iterdir():
                os.link(child, path / child.name)
            # Publish hashes last so consumers reject incomplete/tampered bundles.
            hashes = {name: hashlib.sha256((path / name).read_bytes()).hexdigest()
                      for name in ('manifest.json','instruments.json','prices.json')}
            (path / 'COMPLETE').write_text(json.dumps(hashes, sort_keys=True) + '\n')
        except BaseException:
            shutil.rmtree(path)
            raise
    finally:
        shutil.rmtree(temporary)


def verify_bundle(path):
    try:
        hashes = json.loads((path / 'COMPLETE').read_text())
        if set(hashes) != {'manifest.json','instruments.json','prices.json'}:
            raise InputError('invalid bundle completion marker')
        for name, digest in hashes.items():
            if hashlib.sha256((path / name).read_bytes()).hexdigest() != digest:
                raise InputError('bundle file checksum mismatch')
        manifest = json.loads((path / 'manifest.json').read_text())
        prices = json.loads((path / 'prices.json').read_text())
        if manifest.get('schema') != 'NSE_SKILL_DAILY_V1' or len(prices) != manifest['eligible_count']:
            raise InputError('bundle schema/count mismatch')
        return manifest, prices
    except (OSError, ValueError, KeyError, TypeError) as exc:
        raise InputError('incomplete or invalid bundle') from exc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot', type=Path, required=True)
    parser.add_argument('--as-of', type=iso_day, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        manifest, instruments, prices = build(args.snapshot, args.as_of)
        write_bundle(args.output, manifest, instruments, prices)
    except (InputError, KeyError, ValueError, TypeError) as exc:
        parser.exit(2, 'Input rejected: ' + str(exc) + '\n')
    print(json.dumps({k: manifest[k] for k in ('as_of','universe_count','eligible_count','blocked_count','blocking_reason_counts')}))


if __name__ == '__main__':
    main()
