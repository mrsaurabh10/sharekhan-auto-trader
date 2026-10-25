(() => {
  'use strict';
  const $=s=>document.querySelector(s);let report;
  const money=n=>n==null?'Unavailable':new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR'}).format(n);
  const labels={CURRENT:'Current rules',IGNORE_SOFT_SPREAD:'Ignore soft spread',IGNORE_BOTH_SPREADS:'Ignore both spread limits'};
  const row=(target,values)=>{const tr=document.createElement('tr');values.forEach(v=>{const td=document.createElement('td');td.textContent=v==null?'Unknown':String(v);tr.append(td);});$(target).append(tr);};
  const time=t=>new Date(t).toLocaleTimeString('en-IN',{timeZone:'Asia/Kolkata',hour12:false});
  $('#day').value=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Kolkata',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
  $('#filters').addEventListener('submit',async e=>{
    e.preventDefault();$('#message').textContent='Loading observations…';$('#download').disabled=true;
    ['#policies','#trades','#attempts'].forEach(s=>$(s).replaceChildren());
    try{
      const p=new URLSearchParams({day:$('#day').value});if($('#user').value)p.set('userId',$('#user').value);
      const response=await fetch('/api/strategies/mover-shadow/report?'+p,{credentials:'same-origin'});
      if(!response.ok){const body=await response.json().catch(()=>({}));throw new Error(body.message||(response.status===403?'You can only view your own account.':response.status===401?'Please sign in to view this report.':'Report unavailable.'));}
      report=await response.json();
      $('#coverage').textContent=report.attemptCount?`${report.attemptCount} recorded attempts across ${report.uniqueSetups} unique setups. Sampled observations cannot establish exact fills.`:'No captured entry decisions for this account and date. This does not establish that the strategy had no signals.';
      $('#health').textContent=JSON.stringify({capture:report.captureHealth,current:report.currentObserver},null,2);
      for(const p of report.policies){row('#policies',[labels[p.policy],p.entries,p.resolvedQuoteModels,p.unresolvedOrAmbiguous,`${p.wins} / ${p.losses}`,money(p.quoteModelNet),money(p.quoteModelMaxDrawdown)]);
        for(const t of p.trades)row('#trades',[labels[p.policy],t.symbol+' / '+t.contract,t.status,t.entryAsk,t.remainingLots,money(t.net),t.exits.map(x=>`${time(x.at)} ${x.reason}: ${x.lots} lot(s) @ bid ${x.bid}`).join('; ')||'No supported exit']);}
      for(const a of report.attempts){const d=a.decision,b=d.sample.book;row('#attempts',[time(d.at),d.symbol+' / '+d.contractKey,b?`${b.bid??'?'} / ${b.ask??'?'}`:'Unavailable',a.spreadPercent==null?'Unknown':a.spreadPercent.toFixed(2)+'%',money(a.spreadCostRupees),a.blockedBy.join(', ')||'Eligible quote; fill not confirmed',a.depthStatus]);}
      const hp=new URLSearchParams();if($('#user').value)hp.set('userId',$('#user').value);
      const hr=await fetch('/api/strategies/mover-shadow/history?'+hp,{credentials:'same-origin'});
      if(hr.ok){const h=await hr.json();$('#history').textContent=`${h.sessions} archived sessions / ${h.uniqueSetups} unique setups. Review milestone: ${h.minimumReviewSampleReached?'reached; coverage review still required':'not yet reached (20 sessions and 50 setups)'}.`;$('#historyDetails').textContent=JSON.stringify(h,null,2);}
      $('#message').textContent=report.interpretation;$('#download').disabled=false;
    }catch(error){$('#message').textContent=error.message;$('#message').className='error';}
  });
  $('#download').addEventListener('click',()=>{if(!report)return;const url=URL.createObjectURL(new Blob([JSON.stringify(report,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download=`mover-shadow-${report.day}.json`;a.click();URL.revokeObjectURL(url);});
})();
