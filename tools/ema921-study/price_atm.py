"""Saved Dhan rolling archives only. Conditional fixed-strike ATM valuation; never live orders."""
from pathlib import Path
import csv,json,datetime as dt,collections,itertools,base64,gzip,hashlib,math,sys
ROOT=Path(sys.argv[1]).resolve();OUT=ROOT/"outputs/ema921-20261102";BASE=ROOT/'outputs/index-strategy-backtests-20261102'

from lot_mapping import assign
IST=dt.timezone(dt.timedelta(hours=5,minutes=30));reports={tf:json.loads((OUT/f'signals-{tf}.json').read_text()) for tf in [5,15,30]}
candidates=collections.defaultdict(list)
for tf,r in reports.items():
 for t in r['trades']:
  if t['mode']=='ATM_BUY':candidates[t['signal']['entryAt'][:10]].append((tf,t['signal']))
results=[];exclusions=[];audit=collections.Counter();processed=set()
def records():
 for line in (BASE/'options-archive.jsonl').open():
  r=json.loads(line.replace('\\\\','\\'))
  if r['symbol']=='NIFTY':yield r

def minute(t):return int(t[:2])*60+int(t[3:5])
def attempt(tf,s,rows):
 date=s['entryAt'][:10];ent=minute(s['entryAt'][11:]);ext=minute(s['exitAt'][11:]);strike=math.floor(s['signalSpot']/50+.5)*50
 for mode in ['ATM_BUY','ATM_SELL']:
  side='CE' if (s['direction']=='BULLISH')==(mode=='ATM_BUY') else 'PE';q0=rows.get((ent,side,strike));q1=rows.get((ext,side,strike));issue=None
  if not q0:issue='MISSING_EXACT_ATM_ENTRY'
  elif not q1:issue='MISSING_SAME_STRIKE_EXIT'
  elif q0[5]!='WEEK' or q1[5]!='WEEK':issue='UNEXPECTED_EXPIRY_FAMILY'
  elif q0[4]<=0 or q1[4]<=0:issue='ZERO_VOLUME_ENDPOINT'
  elif any((m,side,strike) not in rows or rows[m,side,strike][5]!='WEEK' for m in range(ent,ext+1)):issue='FIXED_STRIKE_PATH_MISSING'
  base=dict(timeframe=tf,mode=mode,**s,strike=strike,right=side)
  if issue:exclusions.append(dict(**base,issue=issue));continue
  lot=assign({'date':date,'symbol':'NIFTY','strategy':'ema921','family':'WEEK'})
  assert lot['position_lots']==1
  base.update(lot);base.update(entryPremium=q0[0],exitPremium=q1[0],matchedMinutes=ext-ent+1,identityStatus='CONDITIONAL_ROLLING_WEEK_FAMILY_NO_SECURITY_ID',feeStatus='HYPOTHETICAL_FULL_STACK_NOT_HISTORICAL_CONTRACT_NOTE')
  results.append(base)

for key,group in itertools.groupby(records(),lambda r:r['from']):
 rowsByDay=collections.defaultdict(dict);bad=collections.defaultdict(set)
 for block in group:
  packed=base64.b64decode(block['gzip']);assert hashlib.sha256(packed).hexdigest()==block['sha256'];audit['verifiedNiftyChunks']+=1
  if not any(block['from']<=d<=block['to'] for d in candidates):continue
  doc=json.loads(gzip.decompress(packed));req=doc['request'];assert req['securityId']=='13' and req['expiryCode']==1 and req['exchangeSegment']=='NSE_FNO'
  side='CE' if block['side']=='CALL' else 'PE';series=doc['response']['data'][side.lower()];n=len(series['timestamp']);assert all(len(series[k])==n for k in ['open','high','low','close','strike','volume'])
  for i,stamp in enumerate(series['timestamp']):
   t=dt.datetime.fromtimestamp(stamp,IST);day=t.date().isoformat();m=t.hour*60+t.minute
   if day not in candidates or not block['from']<=day<=block['to'] or t.second or not 555<=m<=900:continue
   o,h,l,c,k=map(float,(series[x][i] for x in ['open','high','low','close','strike']));vol=int(series['volume'][i]);audit['rawNiftyRows']+=1
   if not all(math.isfinite(x) for x in [o,h,l,c,k]) or not 0<l<=min(o,c)<=max(o,c)<=h or k<=0 or vol<0:audit['invalidRows']+=1;continue
   idx=(m,side,int(k));assert k==int(k);row=(o,h,l,c,vol,block['family'])
   if idx in rowsByDay[day] and rowsByDay[day][idx]!=row:bad[day].add(idx);audit['conflicts']+=1
   else:rowsByDay[day][idx]=row
 for day,rows in rowsByDay.items():
  assert day not in processed,'Overlapping saved archive group';processed.add(day)
  rows={k:v for k,v in rows.items() if k not in bad[day]}
  for tf,s in candidates[day]:attempt(tf,s,rows)
for day,plans in candidates.items():
 if day not in processed:
  for tf,s in plans:
   for mode in ['ATM_BUY','ATM_SELL']:exclusions.append(dict(timeframe=tf,mode=mode,**s,issue='NO_SAVED_OPTIONS_FOR_SIGNAL_DAY'))
results.sort(key=lambda t:(t['exitAt'],t['entryAt'],t['timeframe'],t['mode']));exclusions.sort(key=lambda t:(t['entryAt'],t['timeframe'],t['mode']))
# This is a fixed conservative modeled fee scenario, not a reconstructed historical tariff.
# STT .15% sell, exchange .05% both, SEBI .0001% both, stamp .003% buy,
# GST18%, IPFT .0005% both, brokerage20/fill. All rates remain explicit in output.
FEE={'brokeragePerOrder':20,'sttSell':.0015,'exchange':.0005,'sebi':.000001,'stampBuy':.00003,'gst':.18,'ipft':.000005,'status':'HYPOTHETICAL_FULL_STACK_NOT_HISTORICAL'}
def money(x):return math.floor(x*100+.500000001)/100

def costs(row,slip):
 buy=row['mode']=='ATM_BUY';e=row['entryPremium'];x=row['exitPremium'];units=row['position_units'];sign=1 if buy else -1
 ef=(math.ceil(e*(1+slip)/.05-1e-9) if buy else math.floor(e*(1-slip)/.05+1e-9))*.05
 xf=(math.floor(x*(1-slip)/.05+1e-9) if buy else math.ceil(x*(1+slip)/.05-1e-9))*.05
 if min(ef,xf)<=0:return None
 bought=(ef if buy else xf)*units;sold=(xf if buy else ef)*units;turnover=bought+sold
 charges={k:money(v) for k,v in dict(brokerage=40,stt=sold*FEE['sttSell'],exchange=turnover*FEE['exchange'],sebi=turnover*FEE['sebi'],stamp=bought*FEE['stampBuy'],ipft=turnover*FEE['ipft']).items()}
 charges['gst']=money((charges['brokerage']+charges['exchange']+charges['sebi']+charges['ipft'])*.18)
 gross=money(sign*(x-e)*units);after=money(sign*(xf-ef)*units);charges['slippage']=money(gross-after);charges['total']=money(sum(charges.values()))
 return dict(entryFill=ef,exitFill=xf,gross=gross,costs=charges,net=money(gross-charges['total']))
rejected=[]
for r in results:
 r['base']=costs(r,.005);r['stress']=costs(r,.01)
 if not r['base'] or not r['stress']:rejected.append(r)
for r in rejected:
 results.remove(r);exclusions.append({**{k:v for k,v in r.items() if k not in ['base','stress']},'issue':'NONPOSITIVE_SLIPPAGE_FILL'})

def metric(rows,field):
 vals=[r['base']['gross'] if field=='gross' else r[field]['net'] for r in rows];wins=[v for v in vals if v>0];losses=[v for v in vals if v<0];net=peak=dd=0;streak=longest=0
 for v in vals:
  net=money(net+v);peak=max(peak,net);dd=max(dd,peak-net);streak=streak+1 if v<0 else 0;longest=max(longest,streak)
 return dict(trades=len(vals),wins=len(wins),losses=len(losses),flat=sum(v==0 for v in vals),winRate=100*len(wins)/len(vals) if vals else None,pnl=money(sum(vals)),profitFactor=sum(wins)/-sum(losses) if losses else None,payoff=(sum(wins)/len(wins))/(-sum(losses)/len(losses)) if wins and losses else None,expectancy=sum(vals)/len(vals) if vals else None,maxDrawdown=money(dd),maxDrawdownPercent=None,longestLosingStreak=longest)
summary=[];yearly=[];monthly=[]
for tf in [5,15,30]:
 for mode in ['ATM_BUY','ATM_SELL']:
  rows=[r for r in results if r['timeframe']==tf and r['mode']==mode];excluded=[r for r in exclusions if r['timeframe']==tf and r['mode']==mode]
  for scenario in ['gross','base','stress']:
   summary.append(dict(timeframe=tf,mode=mode,scenario=scenario,candidateTrades=len(rows)+len(excluded),accepted=len(rows),excluded=len(excluded),coveragePercent=100*len(rows)/(len(rows)+len(excluded)) if rows or excluded else 0,**metric(rows,scenario)))
  for length,target in [(4,yearly),(7,monthly)]:
   for period in sorted({r['entryAt'][:length] for r in rows}):
    for scenario in ['gross','base','stress']:target.append(dict(timeframe=tf,mode=mode,period=period,scenario=scenario,**metric([r for r in rows if r['entryAt'].startswith(period)],scenario)))

def csvwrite(name,rows):
 with (OUT/name).open('w') as f:
  fields=list(dict.fromkeys(k for r in rows for k in r));w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows(rows)
ledger=[]
for r in results:
 flat={k:v for k,v in r.items() if k not in ['base','stress']}
 for scenario in ['base','stress']:
  flat.update({scenario+'_'+k:v for k,v in r[scenario].items() if k!='costs'});flat.update({scenario+'_'+k:v for k,v in r[scenario]['costs'].items()})
 ledger.append(flat)
csvwrite('trades.csv',ledger);csvwrite('exclusions.csv',exclusions);csvwrite('summary.csv',summary);csvwrite('yearly.csv',yearly);csvwrite('monthly.csv',monthly)
counts=[dict(timeframe=tf,mode=m,issue=why,count=n) for (tf,m,why),n in collections.Counter((r['timeframe'],r['mode'],r['issue']) for r in exclusions).items()]
report={'status':'CONDITIONAL_INCOMPLETE_ARCHIVE_NOT_VALIDATED_FOR_LIVE','hypothesis':'Closed-bar EMA9/21 spot crosses capture intraday directional persistence sufficient to offset option decay and transaction friction.','window':'09:30–14:30 inclusive; 15:00 square-off; IST','spotFirst':reports[5]['candles'][0]['at'],'spotLast':reports[5]['candles'][-1]['at'],'acceptedSpotSessions':sum(d['status']!='REJECTED' for d in reports[5]['days']),'rejectedObservedSpotSessions':sum(d['status']=='REJECTED' for d in reports[5]['days']),'feeAssumption':FEE,'audit':dict(audit),'acceptedObservations':len(results),'excludedObservations':len(exclusions),'summary':summary,'yearly':yearly,'monthly':monthly,'exclusionCounts':counts,'lotCounts':dict(collections.Counter(str(r['lot_size']) for r in results)),'transitionSensitive':sum(r['transition_sensitive'] for r in results),'optionDates':sorted(processed)}
(OUT/'results.json').write_text(json.dumps(report,indent=2));(OUT/'accepted.json').write_text(json.dumps(results,separators=(',',':')))
print(json.dumps(report,indent=2))
