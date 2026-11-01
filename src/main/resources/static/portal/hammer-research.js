'use strict';
(function(){
 const $=id=>document.getElementById(id),node=(tag,text)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;return n;};
 const label=s=>String(s||'').replaceAll('_',' '),money=x=>Number.isFinite(x)?new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR'}).format(x):'—',pct=x=>Number.isFinite(x)?x.toFixed(2)+'%':'—';
 let report=null,busy=false;
 function table(headers,rows){const t=node('table'),h=node('thead'),hr=node('tr'),b=node('tbody');headers.forEach(s=>{const c=node('th',s);c.scope='col';hr.append(c);});h.append(hr);rows.forEach(values=>{const r=node('tr');values.forEach(v=>{const c=node('td');if(v instanceof Node)c.append(v);else c.textContent=v;r.append(c);});b.append(r);});t.append(h,b);return t;}
 function evidence(e){if(!e)return 'Unavailable';const d=node('details');d.append(node('summary',(e.hammer?'Hammer geometry passed':'Geometry failed')+' · '+(e.undercutReclaim?'undercut/reclaim':'no undercut/reclaim')));
  [ 'Lower wick '+pct(e.lowerWickPercent)+' · body '+pct(e.bodyPercent)+' · upper wick '+pct(e.upperWickPercent),
    'Wick/body '+e.wickToBody.toFixed(2)+'× (999 cap for zero/tiny body) · close location '+pct(e.closeLocation)+' · recovery '+pct(e.recoveryPercent),
    'Prior high '+e.highDate+' · '+e.sessionsSinceHigh+' sessions ago · pullback '+pct(e.pullbackPercent),
    'Prior 5-session low '+money(e.priorLow)+' · '+(e.undercutReclaim?'undercut and reclaimed':'no undercut/reclaim'),
    'Prior momentum '+(e.priorMomentum?'PASS':'FAIL')+' · high vs 61-session close '+pct(e.highVs61ClosePercent)+' · prior 20-session return '+pct(e.prior20ReturnPercent),
    'Volume / prior 20-session average '+e.volumeRatio.toFixed(2)+'× · average turnover '+money(e.averageTurnover)+' · prior 10 red candles '+e.downDays10,
    'Company and institutional quality, earnings/news and portfolio checks are unverified.'
  ].forEach(s=>d.append(node('p',s)));return d;}
 function render(){
  $('hammerRows').replaceChildren();$('hammerPlans').replaceChildren();if(!report)return;
  const rows=report.stocks.filter(r=>$('hammerAll').checked||['WATCH_ONLY','CONDITIONAL_BUY'].includes(r.status)),plans=report.stocks.filter(r=>r.plan);
  const window=report.validSession?(Date.now()>=Date.parse(report.validSession+'T15:15:00+05:30')?'EXPIRED':Date.now()<Date.parse(report.validSession+'T09:15:00+05:30')?'UPCOMING':'ENTRY SESSION'):'UNAVAILABLE';
  if(plans.length)$('hammerPlans').append(table(['Stock','Buy trigger','Maximum entry','Sell stop','Sell T1 · 2R','Sell T2 · 3R','Entry session'],plans.map(r=>{const p=r.plan;return[r.symbol,money(p.entry),money(p.maxEntry),money(p.stop),money(p.target1),money(p.target2),report.validSession+' · '+window];})));
  else $('hammerPlans').append(node('p','No entry or sell-target levels published. '+label(report.status)+': '+report.entryCandidatesBeforeMarketGate+' stock-qualified candidates before the market gate. Sell stops and targets accompany qualifying buy plans only.'));
  if(rows.length)$('hammerRows').append(table(['Stock','State / reason','Score / 85','Pullback / prior high','Volume / prior 20d','Evidence'],rows.map(r=>[r.symbol,label(r.status)+': '+r.reason,r.score,r.evidence?pct(r.evidence.pullbackPercent)+' / '+r.evidence.sessionsSinceHigh+' sessions':'—',r.evidence?r.evidence.volumeRatio.toFixed(2)+'×':'—',evidence(r.evidence)])));
  else $('hammerRows').append(node('p','No qualifying exhaustion-hammer watches. Show every stock to inspect geometry, momentum and rejection reasons.'));
 }
 async function refresh(){if(busy)return;busy=true;$('hammerRefresh').disabled=true;
  try{const response=await fetch('/api/stock-analysis/hammer',{credentials:'same-origin'});if(!response.ok)throw new Error(response.status===401?'Sign in again to view hammer research.':'Hammer research is unavailable. Retry shortly.');report=await response.json();
   const watches=report.stocks.filter(r=>['WATCH_ONLY','CONDITIONAL_BUY'].includes(r.status)).length,shapes=report.stocks.filter(r=>r.evidence?.hammer).length;
   $('hammerStatus').textContent=label(report.status)+' · data '+(report.dataDate||'unavailable')+' · coverage '+report.coveredCount+'/'+report.universeCount+' · '+pct(100*report.breadthAbove50)+' above SMA50 · '+shapes+' hammer-shaped candles · '+watches+' qualifying watches · '+report.entryCandidatesBeforeMarketGate+' candidates before market gate · '+report.stocks.filter(r=>r.plan).length+' conditional buy plans. '+(report.status==='MARKET_CAUTION'?'New entries withheld: breadth below 50%.':'');
   $('hammerRules').replaceChildren(...report.rules.map(s=>node('li',s)));render();
  }catch(e){$('hammerStatus').textContent=e.message+' Previously displayed results, if any, are not refreshed.';}finally{busy=false;$('hammerRefresh').disabled=false;}}
 $('hammerAll').addEventListener('change',render);$('hammerRefresh').addEventListener('click',refresh);setInterval(()=>{if(!document.hidden)refresh();},60000);document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh();});refresh();
})();
