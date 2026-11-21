"""Conditional nearest-family mapping for this retained snapshot only; see canonical lot reference."""
import datetime as dt
def nearest_nominal(day,symbol):
 if day<dt.date(2023,2,4):return day+dt.timedelta(days=(3-day.weekday())%7)
 if symbol=='NIFTY':return day+dt.timedelta(days=(1-day.weekday())%7)
 # Nearest nominal monthly last Tuesday; holiday shifts are NOT invented.
 def last_tuesday(d):
  end=dt.date(d.year+1,1,1) if d.month==12 else dt.date(d.year,d.month+1,1)
  last=end-dt.timedelta(days=1);return last-dt.timedelta(days=(last.weekday()-1)%7)
 end=last_tuesday(day)
 return end if end>=day else last_tuesday(dt.date(day.year+1,1,1) if day.month==12 else dt.date(day.year,day.month+1,1))

def assign(row):
 day=dt.date.fromisoformat(row['date']);symbol=row['symbol']
 if symbol not in ('NIFTY','BANKNIFTY'):raise ValueError('unsupported instrument')
 if dt.date(2021,11,1)<=day<=dt.date(2023,2,3):lot=50 if symbol=='NIFTY' else 25;period='OLDER_ARCHIVE_STABLE_LOT_RULE'
 elif dt.date(2025,11,1)<=day<=dt.date(2026,10,30):
  new=day>=dt.date(2025,12,31);lot=(65 if new else 75) if symbol=='NIFTY' else (30 if new else 35);period='NEW_NEAREST_JANUARY_ONWARD' if new else 'NEAREST_NOVEMBER_DECEMBER_CONTRACT'
 else:raise ValueError('No dataset-scoped lot mapping; resolve actual contract, do not fill missing periods')
 expected='WEEK' if symbol=='NIFTY' or day<dt.date(2023,2,4) else 'MONTH'
 if row['strategy'].startswith('st') and row['family']!=expected:raise ValueError('Unexpected expiry family')
 sensitive=dt.date(2025,12,24)<=day<=dt.date(2025,12,30)
 lots=3 if row['strategy'].startswith('st') else 1;low=lot;high=lot
 if sensitive:low,high=(65,75) if symbol=='NIFTY' else (30,35)
 # Bounds are only quantity sensitivity for flagged rows, not contract verification.
 return dict(lot_size=lot,position_lots=lots,position_units=lot*lots,assumed_nominal_expiry=nearest_nominal(day,symbol).isoformat(),lot_assignment_status='CONDITIONAL_NEAREST_FAMILY',lot_period=period,transition_sensitive=sensitive,possible_lot_low=low,possible_lot_high=high)
