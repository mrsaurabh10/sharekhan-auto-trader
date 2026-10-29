'use strict';
(function () {
  const $ = id => document.getElementById(id), make = (tag,text) => {const n=document.createElement(tag);if(text!=null)n.textContent=text;return n;};
  const money = v => Number.isFinite(v)?new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR',maximumFractionDigits:2}).format(v):'Unavailable';
  let latest, displayed, observations=[], history=[], loading=false, timer, budget=null;
  const label = text => String(text||'').replaceAll('_',' ');
  async function api(path,options={}) {
    const response=await fetch('/api/stock-analysis/swing'+path,{credentials:'same-origin',...options});
    if(response.status===401)throw new Error('Please sign in again to view daily ideas.');
    const data=await response.json().catch(()=>({}));if(!response.ok)throw new Error(data.message||'Daily research could not be loaded.');return data;
  }
  function windowFor(report) {
    if(!/^\d{4}-\d{2}-\d{2}$/.test(String(report?.validSession||'')))return 'UNAVAILABLE';const now=Date.now();
    if(!Number.isFinite(Date.parse(report.validSession+'T15:15:00+05:30')))return 'UNAVAILABLE';
    if(now>=Date.parse(report.validSession+'T15:15:00+05:30'))return 'EXPIRED';
    return now<Date.parse(report.validSession+'T09:15:00+05:30')?'UPCOMING':'ENTRY SESSION';
  }
  function render(report) {
    displayed=report;$('swingPlans').replaceChildren();$('swingNotes').replaceChildren();$('swingAudit').replaceChildren();
    $('swingDownload').disabled=!report;
    if(!report){$('swingMeta').textContent='No report saved yet.';$('swingPlans').append(make('p','The first scan will save a report here. No ideas have been inferred.'));return;}
    const entryWindow=windowFor(report);
    $('swingMeta').textContent='Data '+report.dataDate+' · entry '+report.validSession+' · '+entryWindow+' · coverage '+report.coveredCount+'/'+report.universeCount;
    const heading=make('p',label(report.status)+(entryWindow==='EXPIRED'?' · archived entry window has expired':''));heading.className='notice';$('swingPlans').append(heading);
    if(!report.plans.length)$('swingPlans').append(make('p',report.status==='DATA_INCOMPLETE'?'Required EOD candles or verified metadata are missing. This is a data-pending report, not evidence that no opportunities exist.':report.status==='MARKET_CAUTION'?'Fewer than half of the covered stocks are above SMA50. The research market-condition gate withheld new ideas.':'No stock passed all of the defined technical filters. No trade is a valid result.'));
    const observed=new Map(observations.map(o=>[o.planDate+':'+o.symbol,o]));
    report.plans.forEach(plan=>{
      const card=make('article');card.className='card section-gap';
      card.append(make('h3',plan.symbol+' · '+label(plan.setup)),make('p',plan.rationale));
      const table=make('table'),body=make('tbody');
      const fields=[['Conditional buy trigger',money(plan.entry)],['Maximum entry · do not chase',money(plan.maxEntry)],['Stop-loss level',money(plan.stop)],['T1 · 2R',money(plan.target1)],['T2 · 3R',money(plan.target2)],['Risk per share at maximum entry',money(plan.riskPerShare)],['Planned shares','Enter planning budget above']];
      let quantity;
      fields.forEach(([key,value])=>{const row=make('tr');const cell=make('td',value);row.append(make('th',key),cell);body.append(row);if(key==='Planned shares')quantity=cell;});
      if(budget)try{const sizing=window.StockAnalysisMath.sizeSwing({...budget,entry:plan.maxEntry.toFixed(2),stop:plan.stop.toFixed(2)});quantity.textContent=sizing.quantity.toLocaleString('en-IN')+' shares · risk '+money(sizing.plannedRisk)+' · value '+money(sizing.positionValue);}catch(e){quantity.textContent=e.message;}
      table.append(body);const wrap=make('div');wrap.className='table-wrap';wrap.append(table);card.append(wrap);
      const obs=observed.get(plan.dataDate+':'+plan.symbol);
      if(obs)card.append(make('p','EOD observation through '+obs.observedThrough+': '+label(obs.status)+'. '+obs.detail));
      const list=make('ul');plan.cancelIf.forEach(s=>list.append(make('li',s)));card.append(list);
      $('swingPlans').append(card);
    });
    report.notes.forEach(note=>$('swingNotes').append(make('li',note)));
    const table=make('table'),head=make('thead'),row=make('tr');['Stock','Result','Latest daily date','Reason'].forEach(s=>row.append(make('th',s)));head.append(row);table.append(head);
    const body=make('tbody');report.stocks.forEach(stock=>{const row=make('tr');[stock.symbol,label(stock.status),stock.lastDate||'Unavailable',stock.detail].forEach(s=>row.append(make('td',s)));body.append(row);});table.append(body);$('swingAudit').append(table);
  }
  async function refresh() {
    if(loading)return;if(document.hidden){timer=setTimeout(refresh,60000);return;}loading=true;
    try {
      const previous=latest?.generatedAt;const state=await api('');latest=state.report;observations=state.observations||[];
      $('swingStatus').textContent=state.problem||(state.running?'Daily research scan is running. Saved reports remain visible.':latest?'Saved daily research · '+label(latest.status):'No report yet. The automatic scan may be waiting for its first run.');
      $('swingScan').disabled=state.running;
      $('swingUniverse').textContent='Configured research universe ('+state.universe.length+'): '+state.universe.join(', ')+'. Expected EOD session: '+(state.expectedDate||'calendar unavailable')+'.';
      if(!$('swingHistory').value)render(latest);else if(displayed)render(displayed);if(latest?.generatedAt!==previous)loadHistory();
      clearTimeout(timer);timer=setTimeout(refresh,state.running?5000:60000);
    }catch(e){$('swingStatus').textContent=e.message;}finally{loading=false;}
  }
  async function loadHistory() {
    try {
      history=await api('/history');const selected=$('swingHistory').value;$('swingHistory').replaceChildren(make('option','Latest report'));$('swingHistory').firstChild.value='';
      history.forEach(report=>{const option=make('option',report.dataDate+' · '+label(report.status));option.value=report.dataDate;$('swingHistory').append(option);});$('swingHistory').value=selected;
    }catch(e){$('swingStatus').textContent=e.message;}
  }
  $('swingScan').addEventListener('click',async()=>{
    $('swingScan').disabled=true;
    try {
      const header=document.querySelector('meta[name="swing-csrf-header"]')?.content,token=document.querySelector('meta[name="swing-csrf-token"]')?.content;
      if(!header||!token)throw new Error('Refresh the page to obtain a current sign-in token.');
      const result=await api('/scan',{method:'POST',headers:{[header]:token}});$('swingStatus').textContent=result.message;
      await refresh();await loadHistory();
    }catch(e){$('swingStatus').textContent=e.message;$('swingScan').disabled=false;}
  });
  $('swingHistory').addEventListener('change',()=>{const id=$('swingHistory').value;render(id?history.find(r=>r.dataDate===id):latest);});
  $('swingBudget').addEventListener('submit',event=>{
    event.preventDefault();const candidate=Object.fromEntries(new FormData(event.currentTarget));
    try {window.StockAnalysisMath.sizeSwing({...candidate,entry:'100.00',stop:'90.00'});budget=candidate;$('swingSizingStatus').textContent='Quantities use maximum entry, whole shares, your position cap and remaining planned portfolio risk.';render(displayed);}
    catch(e){$('swingSizingStatus').textContent=e.message;}
  });
  $('swingDownload').addEventListener('click',()=>{
    if(!displayed)return;const url=URL.createObjectURL(new Blob([JSON.stringify(displayed,null,2)],{type:'application/json'})),link=make('a');link.href=url;link.download='swing_ideas_'+displayed.dataDate+'.json';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
  });
  document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh();});
  refresh();loadHistory();
})();
