"""Independent recurrence/event replay and ledger arithmetic audit."""
from pathlib import Path
import sys
import csv,json,datetime as dt,collections,math
P=Path(sys.argv[1]).resolve()/"outputs/ema921-20261102"
bars=collections.defaultdict(list)
for r in csv.DictReader((P/'NIFTY-1m.csv').open()):bars[r['timestamp'][:10]].append((r['timestamp'],*[float(r[k]) for k in ['open','high','low','close']]))
audit={}
for tf in [5,15,30]:
 report=json.loads((P/f'signals-{tf}.json').read_text());actual=[t['signal'] for t in report['trades'] if t['mode']=='ATM_BUY'];expected=[];count=0;f=s=None;sumf=sums=0;relation=0;previous=None;candleCount=0
 for day,rs in sorted(bars.items()):
  date=dt.date.fromisoformat(day);rows=sorted(rs);origin=dt.datetime.combine(date,dt.time(9,15));complete=len(rows)==375 and all(dt.datetime.fromisoformat(r[0])==origin+dt.timedelta(minutes=i) for i,r in enumerate(rows))
  if not complete or previous and date>previous+dt.timedelta(days=7):count=0;f=s=None;sumf=sums=0;relation=0
  previous=date
  if not complete:continue
  events=[];cross=0
  for i in range(0,375-tf+1,tf):
   price=rows[i+tf-1][4];count+=1
   if count<=9:sumf+=price;f=sumf/9 if count==9 else None
   else:f=f+2/10*(price-f)
   if count<=21:sums+=price;s=sums/21 if count==21 else None
   else:s=s+2/22*(price-s)
   sign=(1 if f>s else -1 if f<s else 0) if f is not None and s is not None else 0
   crossed=sign!=0 and relation!=0 and sign!=relation
   if sign:relation=sign
   if crossed:cross+=1;events.append((origin+dt.timedelta(minutes=i+tf),sign,cross,price))
   candleCount+=1
  pending=None
  for at,side,cross,signalSpot in events:
   if at.time()>=dt.time(15):continue
   nextOpen=rows[int((at-origin).total_seconds()/60)][1]
   if pending is not None:expected.append((*pending,at.isoformat(),nextOpen,'OPPOSITE_CROSS'));pending=None
   if dt.time(9,30)<=at.time()<=dt.time(14,30):pending=(at.isoformat(),'BULLISH' if side>0 else 'BEARISH',cross,signalSpot,nextOpen)
  if pending:expected.append((*pending,day+'T15:00:00',rows[345][1],'SQUARE_OFF'))
 assert len(expected)==len(actual),(tf,len(expected),len(actual))
 for e,a in zip(expected,actual):
  assert e==(a['entryAt'],a['direction'],a['cross'],a['signalSpot'],a['entrySpot'],a['exitAt'],a['exitSpot'],a['reason']),(tf,e,a)
 assert candleCount==len(report['candles'])
 audit[str(tf)]={'signalTradesChecked':len(actual),'candlesChecked':candleCount,'independentSignalReplay':'PASS'}
if (P/'accepted.json').exists():
 rows=json.loads((P/'accepted.json').read_text());summary=json.loads((P/'results.json').read_text())['summary']
 for r in rows:
  assert r['entryAt'][:10]==r['exitAt'][:10] and r['exitAt'][11:16]<='15:00'
  assert r['matchedMinutes']==int((dt.datetime.fromisoformat(r['exitAt'])-dt.datetime.fromisoformat(r['entryAt'])).total_seconds()/60)+1
  for scenario in ['base','stress']:
   v=r[scenario];assert abs(v['gross']-round((1 if r['mode']=='ATM_BUY' else -1)*(r['exitPremium']-r['entryPremium'])*r['position_units'],2))<.011
   assert abs(v['costs']['total']-sum(x for k,x in v['costs'].items() if k!='total'))<.011
   assert abs(v['net']-(v['gross']-v['costs']['total']))<.011
 for s in summary:
  rs=[r for r in rows if r['timeframe']==s['timeframe'] and r['mode']==s['mode']];vals=[r['base']['gross'] if s['scenario']=='gross' else r[s['scenario']]['net'] for r in rs]
  assert len(rs)==s['trades']==s['accepted'];assert abs(sum(vals)-s['pnl'])<.011
  assert s['wins']+s['losses']+s['flat']==s['trades']
 audit['ledger']={'acceptedObservationsChecked':len(rows),'summariesChecked':len(summary),'accounting':'PASS'}
(P/'independent-audit.json').write_text(json.dumps(audit,indent=2));print(json.dumps(audit,indent=2))
