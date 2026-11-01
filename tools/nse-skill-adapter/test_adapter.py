import copy
from datetime import date, datetime, time, timedelta
import json
from pathlib import Path
import re
import tempfile
import unittest

import adapter


class AdapterTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'snapshot.jsonl'
        self.day = date(2026, 10, 30)
        self.meta = dict(type='snapshot', schema='NSE_DAILY_SNAPSHOT_V1', as_of=str(self.day),
            exported_at='2026-11-01T18:00:00+05:30', provider='DHAN', exchange_segment='NSE_EQ',
            universe='NIFTY 200', membership_policy='LATEST_SNAPSHOT_NOT_POINT_IN_TIME', snapshot_date='2026-11-01')
        self.members = [dict(type='member', symbol='TEST' + str(i), isin='INE'+str(i).zfill(9),
            snapshot_date='2026-11-01', source_url='https://example.test/fixture', source_sha256='a'*64) for i in range(200)]
        days, cursor = [], self.day
        while len(days) < 220:
            if cursor.year != 2026 or adapter.regular(cursor):
                if cursor.weekday() < 5:
                    days.append(cursor)
            cursor -= timedelta(days=1)
        self.bars = [dict(type='candle', symbol='TEST0', security_id='123', date=str(d),
            epoch=int(datetime.combine(d, time(0), adapter.IST).timestamp()), open=100, high=102, low=99,
            close=101, volume=10000, tick_size=.05, captured_at='2026-11-01T17:00:00+05:30',
            import_id='FIXTURE_ONLY', adjustment_status='UNKNOWN') for d in days]

    def run_build(self, bars=None, *, meta=None, members=None, end=True):
        members = self.members if members is None else members
        bars = self.bars if bars is None else bars
        records = [meta or self.meta] + members + bars
        if end:
            records += [dict(type='end', members=len(members), candles=len(bars))]
        self.path.write_text('\n'.join(json.dumps(r) for r in records))
        return adapter.build(self.path, self.day)

    def blocked(self, bars):
        manifest, instruments, prices = self.run_build(bars)
        self.assertNotIn('TEST0', prices)
        return next(i for i in instruments if i['symbol'] == 'TEST0')['blocking_reasons']

    def test_valid_prices_order_units_and_provenance(self):
        m, instruments, prices = self.run_build()
        self.assertEqual(1, m['eligible_count'])
        self.assertEqual(200, m['universe_count'])
        self.assertEqual(sorted(b['date'] for b in self.bars), [b['date'] for b in prices['TEST0']])
        item = next(i for i in instruments if i['symbol'] == 'TEST0')
        self.assertEqual('INR', item['currency'])
        self.assertIsNone(item['sharekhan_scrip_code'])
        self.assertIn('ADJUSTMENT_UNKNOWN', item['flags'])

    def test_no_future_bars_or_epoch_shift(self):
        for key, value in [('date','2026-11-02'), ('epoch', self.bars[0]['epoch'] - 86400)]:
            with self.subTest(key=key):
                bars = copy.deepcopy(self.bars); bars[0][key] = value
                self.assertIn('INVALID_SOURCE_ROW', self.blocked(bars))

    def test_bad_prices_and_volume_fail_closed(self):
        for key, value in [('high',90),('low',103),('close',0),('open',float('nan')),
                           ('volume',-1),('volume',1.5),('volume',True),('tick_size',0)]:
            with self.subTest(key=key, value=value):
                bars = copy.deepcopy(self.bars); bars[0][key] = value
                self.assertIn('INVALID_SOURCE_ROW', self.blocked(bars))

    def test_exact_duplicates_collapsed(self):
        manifest, _, prices = self.run_build(self.bars + [copy.deepcopy(self.bars[0])])
        self.assertEqual(1, manifest['exact_duplicate_bars_collapsed'])
        self.assertEqual(220, len(prices['TEST0']))

    def test_conflicting_duplicate_blocks_entire_symbol(self):
        conflict = copy.deepcopy(self.bars[0]); conflict['close'] = 100
        self.assertIn('INVALID_SOURCE_ROW', self.blocked(self.bars + [conflict]))

    def test_identity_collision_blocks_entire_symbol(self):
        bars = copy.deepcopy(self.bars); bars[0]['security_id'] = '456'
        self.assertIn('MISSING_OR_AMBIGUOUS_SECURITY_ID', self.blocked(bars))

    def test_cross_symbol_identity_collision_blocks_both(self):
        extra = copy.deepcopy(self.bars)
        for b in extra:
            b['symbol']='TEST1'
        manifest, instruments, prices = self.run_build(self.bars+extra)
        self.assertFalse(prices)
        self.assertEqual(2,manifest['blocking_reason_counts']['CROSS_SYMBOL_SECURITY_ID_COLLISION'])

    def test_duplicate_isin_rejected(self):
        members=copy.deepcopy(self.members);members[1]['isin']=members[0]['isin']
        with self.assertRaises(adapter.InputError):
            self.run_build(members=members)

    def test_missing_recent_session_and_short_history(self):
        reasons = self.blocked(self.bars[1:])
        self.assertIn('MISSING_RECENT_SESSIONS', reasons)
        self.assertIn('INSUFFICIENT_HISTORY', reasons)

    def test_zero_recent_volume(self):
        bars = copy.deepcopy(self.bars); bars[0]['volume'] = 0
        self.assertIn('NONPOSITIVE_RECENT_VOLUME', self.blocked(bars))

    def test_discontinuity_review_without_price_repair(self):
        bars = copy.deepcopy(self.bars)
        for key in ['open','high','low','close']:
            bars[0][key] *= 2
        self.assertIn('CORPORATE_ACTION_REVIEW', self.blocked(bars))
        _, instruments, _ = self.run_build(bars)
        item = next(i for i in instruments if i['symbol'] == 'TEST0')
        self.assertEqual(202, item['bars'][-1]['close'])

    def test_provenance_and_capture_validation(self):
        for key,value in [('captured_at','2026-11-02T10:00:00+05:30'),
                          ('captured_at','2026-01-01T10:00:00'),('import_id',''),
                          ('adjustment_status','ADJUSTED')]:
            with self.subTest(key=key):
                bars = copy.deepcopy(self.bars); bars[0][key] = value
                self.assertIn('INVALID_SOURCE_ROW', self.blocked(bars))

    def test_nonregular_session_requires_review(self):
        extra = copy.deepcopy(self.bars[0]); extra['date']='2026-10-31'
        extra['epoch']=int(datetime(2026,10,31,tzinfo=adapter.IST).timestamp())
        # Use a bounded earlier weekend rather than a future row.
        extra['date']='2026-10-25'; extra['epoch']-=6*86400
        self.assertIn('NONREGULAR_SESSION_REVIEW', self.blocked(self.bars+[extra]))

    def test_rejects_truncation_and_count_mismatch(self):
        with self.assertRaises(adapter.InputError):
            self.run_build(end=False)
        self.run_build()
        with self.path.open('a') as stream:
            stream.write('\n'+json.dumps(self.bars[0]))
        with self.assertRaises(adapter.InputError):
            adapter.build(self.path,self.day)

    def test_rejects_incomplete_or_duplicate_universe(self):
        for members in [self.members[:-1], self.members + [self.members[0]]]:
            with self.assertRaises(adapter.InputError):
                self.run_build(members=members)

    def test_wrong_source_and_cutoff(self):
        for key,value in [('provider','FMP'),('as_of','2026-10-29'),
                          ('exported_at','2026-10-30T15:31:00+05:30'),('snapshot_date','2027-01-01')]:
            meta = dict(self.meta); meta[key] = value
            with self.subTest(key=key), self.assertRaises(adapter.InputError):
                self.run_build(meta=meta)

    def test_unknown_year_and_nonregular_cutoff_rejected(self):
        for day in [date(2027,1,5), date(2026,11,8),date(2026,10,31),date(2026,1,2)]:
            with self.assertRaises(adapter.InputError):
                adapter.required_sessions(day)

    def test_calendar_matches_app(self):
        source = Path(__file__).resolve().parents[2]/'src/main/java/org/com/sharekhan/analysis/SwingSessions.java'
        dates = set(re.findall(r'"(2026-\d{2}-\d{2})"', source.read_text()))
        self.assertEqual(dates, adapter.HOLIDAYS)

    def test_verified_budget_session_retained_and_required(self):
        extra = copy.deepcopy(self.bars[0]); extra['date']='2026-02-01'
        extra['epoch']=int(datetime(2026,2,1,tzinfo=adapter.IST).timestamp())
        _, instruments, prices = self.run_build(self.bars+[extra])
        self.assertIn('TEST0',prices)
        item = next(i for i in instruments if i['symbol']=='TEST0')
        self.assertEqual(['2026-02-01'],item['verified_special_sessions'])
        self.assertIn('2026-02-01',adapter.required_sessions(date(2026,2,2)))

    def test_bundle_completion_hash_and_no_overwrite(self):
        m, instruments, prices = self.run_build()
        target = Path(self.temp.name)/'bundle'
        adapter.write_bundle(target, m, instruments, prices)
        self.assertEqual(m['source_sha256'], adapter.verify_bundle(target)[0]['source_sha256'])
        self.assertEqual(220,len(json.loads((target/'prices.json').read_text())['TEST0']))
        with self.assertRaises(adapter.InputError):
            adapter.write_bundle(target, m, instruments, prices)
        (target/'prices.json').write_text('{}')
        with self.assertRaises(adapter.InputError):
            adapter.verify_bundle(target)


if __name__ == '__main__':
    unittest.main()
