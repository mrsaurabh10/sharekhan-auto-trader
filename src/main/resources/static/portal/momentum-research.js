'use strict';
(function(){
  const $=id=>document.getElementById(id),node=(tag,text)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;return n;};
  const label=s=>String(s||'').replaceAll('_',' '),money=x=>Number.isFinite(x)?new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR'}).format(x):'—';
  let report=null,skills=[],busy=false;
  function table(headers,rows){const t=node('table'),h=node('thead'),hr=node('tr'),b=node('tbody');headers.forEach(s=>{const c=node('th',s);c.scope='col';hr.append(c);});h.append(hr);rows.forEach(values=>{const r=node('tr');values.forEach(v=>r.append(node('td',v)));b.append(r);});t.append(h,b);return t;}
  function render(){
    $('momentumRows').replaceChildren();$('momentumPlans').replaceChildren();if(!report)return;
    const all=$('momentumAll').checked,rows=report.stocks.filter(r=>all||['WATCH_ONLY','CONDITIONAL_BUY'].includes(r.status));
    if(!rows.length)$('momentumRows').append(node('p','No qualifying momentum watchlist setups in this report. Show every stock to inspect the filters.'));
    const window=report.validSession?(Date.now()>=Date.parse(report.validSession+'T15:15:00+05:30')?'EXPIRED':Date.now()<Date.parse(report.validSession+'T09:15:00+05:30')?'UPCOMING':'ENTRY SESSION'):'UNAVAILABLE';
    const plans=report.stocks.filter(r=>r.plan);
    if(plans.length)$('momentumPlans').append(table(['Stock','Buy trigger','Maximum entry','Sell stop','Sell T1 · 2R','Sell T2 · 3R','Entry session'],plans.map(r=>{const p=r.plan;return[r.symbol,money(p.entry),money(p.maxEntry),money(p.stop),money(p.target1),money(p.target2),report.validSession+' · '+window];})));
    else $('momentumPlans').append(node('p',report.status==='MARKET_CAUTION'?'No entry or sell-target levels published: the market gate blocks new buy plans. Observed setups remain available for review below.':'No stock passed all entry gates. Sell stops and targets accompany qualifying buy plans only.'));
    if(rows.length)$('momentumRows').append(table(['Stock','State / reason','Trigger','Day change','Volume / prior 20d','Score / 95'],rows.map(r=>[r.symbol,label(r.status)+': '+label(r.reason),r.triggers.map(label).join(', ')||'None',Number.isFinite(r.dayGainPercent)?r.dayGainPercent.toFixed(2)+'%':'—',Number.isFinite(r.volumeRatio)?r.volumeRatio.toFixed(2)+'×':'—',r.score])));

  }
  async function refresh(){if(busy)return;busy=true;$('momentumRefresh').disabled=true;
    try{const response=await fetch('/api/stock-analysis/momentum',{credentials:'same-origin'});if(!response.ok)throw new Error(response.status===401?'Sign in again to view research.':'Momentum research is unavailable. Retry shortly.');report=await response.json();
      const setups=report.stocks.filter(r=>['WATCH_ONLY','CONDITIONAL_BUY'].includes(r.status)).length,plans=report.stocks.filter(r=>r.plan).length;
      $('momentumStatus').textContent=label(report.status)+' · data '+(report.dataDate||'unavailable')+' · coverage '+report.coveredCount+'/'+report.universeCount+' · '+(100*report.breadthAbove50).toFixed(2)+'% above SMA50 · '+setups+' watchlist setups · '+plans+' conditional buy plans. '+(report.status==='MARKET_CAUTION'?'New entries withheld: fewer than 50% of covered stocks are above SMA50.':'');
      $('momentumRules').replaceChildren(...report.rules.map(s=>node('li',s)));render();
    }catch(e){$('momentumStatus').textContent=e.message+' Previously displayed results, if any, are not refreshed.';}finally{busy=false;$('momentumRefresh').disabled=false;}}
  function renderSkills(){const q=$('skillFilter').value.toLowerCase();$('skillRows').replaceChildren(table(['Skill','App integration','Scope / remaining work'],skills.filter(s=>(s.skill+' '+s.remaining).toLowerCase().includes(q)).map(s=>[s.skill,label(s.status),s.remaining])));}
  async function loadSkills(){try{const response=await fetch('/portal/trading-skills.json?v=20261102-vcp1');if(!response.ok)throw new Error();skills=(await response.json()).skills;$('skillsStatus').textContent=skills.filter(s=>s.status==='SELECTED_METHODS_IN_APP').length+' of '+skills.length+' skills have selected methods integrated; remaining skills need separate data or methodology work.';renderSkills();}catch(e){$('skillsStatus').textContent='Integration inventory unavailable.';}}
  $('momentumAll').addEventListener('change',render);$('momentumRefresh').addEventListener('click',refresh);$('skillFilter').addEventListener('input',renderSkills);
  setInterval(()=>{if(!document.hidden)refresh();},60000);document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh();});refresh();loadSkills();
})();
