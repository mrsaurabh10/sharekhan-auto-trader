import importlib.util
import unittest
from pathlib import Path
spec = importlib.util.spec_from_file_location('report', Path(__file__).with_name('expiry-straddle-option-report.py'))
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)

class OptionReportTest(unittest.TestCase):
    def fixture(self):
        spots = [{'candle_time':'2026-10-19 09:30:00','open_price':'22981.65'},
                 {'candle_time':'2026-10-19 13:30:00','open_price':'22861.75'}]
        contracts = []
        for slot, strike, side, entry, exit_price in [('AM',23000,'CE','61.80','131.10'),('AM',23000,'PE','100.95','26.10'),
                                                     ('PM',22850,'CE','44.05','34.55'),('PM',22850,'PE','47.50','33.90')]:
            start,end=report.SESSIONS[slot]
            contracts.append({'session':slot,'strike':strike,'side':side,'candles':[
                {'time':'19-10-2026 '+start,'into':entry},{'time':'19-10-2026 '+end,'into':exit_price}]})
        return spots,{'date':'2026-10-19','expiry':'2026-10-19','provider':'fixture','lot_size':65,'contracts':contracts}

    def test_decimal_both_legs_and_sessions(self):
        result=report.calculate(*self.fixture())
        self.assertEqual(result['total_gross_pnl'],'-1862.25')
        self.assertEqual([s['gross_pnl'] for s in result['sessions']],['-360.75','-1501.50'])
        self.assertEqual([s['strike'] for s in result['sessions']],['23000','22850'])
        self.assertEqual([r['gross_pnl'] for r in result['legs']],['4504.50','-4865.25','-617.50','-884.00'])

    def test_lots_scale_actual_option_quantity_and_pnl(self):
        result=report.calculate(*self.fixture(),lots=2)
        self.assertEqual(result['total_gross_pnl'],'-3724.50')
        self.assertTrue(all(s['quantity_per_leg']==130 for s in result['sessions']))

    def test_missing_boundary_cannot_substitute_a_quote(self):
        spots,options=self.fixture();options['contracts'][0]['candles'].pop()
        with self.assertRaises(KeyError):report.calculate(spots,options)

    def test_nonexpiry_and_invalid_inputs_rejected(self):
        spots,options=self.fixture();options['expiry']='2026-10-27'
        with self.assertRaises(ValueError):report.calculate(spots,options)
        with self.assertRaises(ValueError):report.calculate(*self.fixture(),lots=0)

    def test_wrong_strike_or_missing_leg_cannot_form_pair(self):
        spots,options=self.fixture();options['contracts'][0]['strike']=22950
        with self.assertRaises(ValueError):report.calculate(spots,options)

    def test_duplicate_timestamp_and_invalid_price_rejected(self):
        spots,options=self.fixture();options['contracts'][0]['candles'].append(options['contracts'][0]['candles'][0])
        with self.assertRaises(ValueError):report.calculate(spots,options)
        spots,options=self.fixture();options['contracts'][0]['candles'][0]['into']='NaN'
        with self.assertRaises(ValueError):report.calculate(spots,options)

if __name__=='__main__':unittest.main()
