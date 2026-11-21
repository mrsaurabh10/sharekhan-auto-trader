from pathlib import Path
import sys
import csv,datetime as dt,json,subprocess,hashlib
ROOT=Path(sys.argv[1]).resolve();OUT=ROOT/"outputs/ema921-20261102";OUT.mkdir(parents=True,exist_ok=True);BASE=ROOT/'outputs/index-strategy-backtests-20261102'
source=BASE/'index-raw.csv';spot=OUT/'NIFTY-1m.csv';n=0
with source.open() as f,spot.open('w') as dest:
 w=csv.writer(dest);w.writerow(['timestamp','open','high','low','close'])
 for r in csv.DictReader(f):
  if r['symbol']!='NIFTY' or r['quality_status']!='NATIVE_5M_WINDOW_CONFIRMED':continue
  t=dt.datetime.fromisoformat(r['candle_time'])
  if t.weekday()>4 or not dt.time(9,15)<=t.time()<dt.time(15,30) or t.second:continue
  w.writerow([t.isoformat(),*[r[k] for k in ['open_price','high_price','low_price','close_price']]]);n+=1
cp=str(ROOT/'sharekhan-intraday-exit-fix/target/classes')+':'+':'.join(str(p) for p in (ROOT/'outputs/pending-fixes-rollout-20261031/m2').rglob('*.jar'))
for tf in [5,15,30]:
 config={'timeframe':tf,'start':'09:30','cutoff':'14:30','from':None,'to':None,'slippageRate':.005,'fixedPoints':0,'capital':None,'fees':[]}
 cfg=OUT/f'config-{tf}.json';cfg.write_text(json.dumps(config))
 p=subprocess.run(['java','-Xmx768m','-cp',cp,'org.com.sharekhan.research.ema921.Ema921Main',str(spot),str(cfg),str(OUT/f'signals-{tf}.json')],capture_output=True,text=True)
 (OUT/f'run-{tf}.log').write_text(p.stdout+p.stderr);assert p.returncode==0,p.stderr;print(tf,p.stdout,flush=True)
(OUT/'inputs.json').write_text(json.dumps({'nativeSpotRows':n,'files':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [source,spot,BASE/'options-archive.jsonl',ROOT/'references/index-option-lot-sizes.json']}},indent=2))
