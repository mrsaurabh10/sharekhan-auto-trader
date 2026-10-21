/* Shared personal/admin strategy-instance UI. All labels use textContent. */
(() => {
  'use strict';
  const node=(tag,text,cls)=>{const e=document.createElement(tag);if(text!=null)e.textContent=text;if(cls)e.className=cls;return e;};
  const money=value=>value==null?'Unavailable':'₹'+Number(value).toLocaleString('en-IN',{minimumFractionDigits:2,maximumFractionDigits:2});
  const sourceName=value=>typeof value==='string' && value.trim() ? value.trim() : 'Unspecified';
  function sourceLabel(value) {
    const source=sourceName(value);
    const labels={FNO_0925_MOVER_ATR_BREAKOUT:'F&O 09:25',STOCK_0925_MOVER_ATR_BREAKOUT:'Stocks 09:25',EXPIRY_LONG_STRADDLE:'Expiry straddle'};
    if(source.startsWith('strategy:')) return labels[source.slice(9)] || source.slice(9).replaceAll('_',' ');
    if(source.startsWith('expiry-straddle:')) {
      const slot=source.split(':').at(-1);return 'Expiry straddle'+(['AM','PM'].includes(slot) ? ' · '+slot : '');
    }
    return source;
  }
  function sourceTag(value) {
    const full=sourceName(value),tag=node('span',sourceLabel(full),'source-tag');
    tag.title='Source: '+full;tag.setAttribute('aria-label',tag.title);tag.tabIndex=0;
    tag.onfocus=()=>{tag.textContent=full;};tag.onblur=()=>{tag.textContent=sourceLabel(full);};return tag;
  }
  function statusTag(value,reason) {
    const noOrderWait=value==='REJECTED' && reason==='ENTRY_NOT_READY_NO_ORDER';
    const full=noOrderWait?'WAITING_FOR_ENTRY':String(value || 'PENDING');
    const labels={WAITING_FOR_ENTRY:'Waiting for entry',EXITED_SUCCESS:'Exited',COMPLETED:'Completed',EXECUTED:'Open',PLACED_PENDING_CONFIRMATION:'Pending',ENTRY_SUBMITTING:'Submitting',
      EXIT_ORDER_PLACED:'Exit pending',TARGET_ORDER_PLACED:'Target set',EXIT_TRIGGERED:'Closing',EXIT_FAILED:'Exit failed',REJECTED:'Rejected',CANCELLED:'Cancelled',FAILED:'Failed',TRIGGERED:'Triggered',ACTIVE:'Active'};
    const attention=!['EXITED_SUCCESS','COMPLETED'].includes(full) && (full==='EXIT_FAILED' || String(reason || '').includes('RECONCILIATION_REQUIRED'));
    const label=attention?'Needs attention':labels[full] || full.replaceAll('_',' ');
    const tone=attention?'status-bad':/FAILED|REJECTED|CANCELLED/.test(full)?'status-bad':/EXECUTED|COMPLETED|SUCCESS|ACTIVE/.test(full)?'status-good':'status-wait';
    const tag=node('span',label,'status-pill compact-trade-status '+tone);
    tag.title=noOrderWait?'Waiting for entry · no order was submitted for this attempt; entry conditions were not met.':'Status: '+full+(reason?' · '+String(reason):'');tag.setAttribute('aria-label',tag.title);tag.tabIndex=0;
    tag.onfocus=()=>{tag.textContent=full;};tag.onblur=()=>{tag.textContent=label;};return tag;
  }
  function timestamp(value) {
    const full=String(value || ''),match=full.match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}:\d{2}:\d{2})/);
    if(!match)return node('span',full || '—');
    const months=['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
    const date=node('time',null,'trade-timestamp');date.dateTime=full.replace(' ','T');date.title=full;date.setAttribute('aria-label',full);date.tabIndex=0;
    date.append(node('span',match[4],'trade-time'),node('span',Number(match[3])+' '+(months[Number(match[2])-1] || match[2])+' '+match[1],'trade-date'));return date;
  }
  async function mount(container,api,userId) {
    if(!container)return;
    const token={};container.runViewToken=token;let page=0;
    async function load() {
      try {
        const result=await api('/api/strategies/runs?size=10&page='+page+(userId?'&userId='+encodeURIComponent(userId):''));
        if(container.runViewToken!==token)return;
        container.replaceChildren(node('h3','Strategy instances'));
        container.append(node('p','Close one instance to exit all of its positions. Other instances and future scheduled sessions remain active. Manual and older trades remain in the individual table below.','muted'));
        const controls=node('div',null,'actions');const refresh=node('button','Refresh instances');refresh.type='button';refresh.onclick=load;controls.append(refresh);
        if(!Array.isArray(result.content))throw new Error('Invalid strategy instance response');
        for(const view of result.content) {
          const run=view.run,pnl=view.pnl,details=node('details',null,'strategy-run');
          const summary=node('summary',run.templateId+' · '+run.tradingDay+' '+run.instance+' · '+run.status+' · '+money(pnl.totalGross));details.append(summary);
          const sources=node('span',null,'run-sources');sources.setAttribute('aria-label','Trade sources');
          for(const source of new Set(view.positions.length ? view.positions.map(leg=>sourceName(leg.source)) : ['Unspecified'])) sources.append(sourceTag(source));
          summary.append(sources);
          details.append(node('p','Run '+run.id.slice(0,10)+' · '+run.symbol+' · broker #'+run.brokerCredentialsId+' · '+pnl.openPositions+' open · '+view.requests+' requests','muted'));
          const values=node('div',null,'run-pnl');
          for(const [label,value] of [['Realized gross',pnl.realizedGross],['Open gross',pnl.openGross],['Combined gross',pnl.totalGross],['Estimated charges',pnl.estimatedCosts],['Estimated net',pnl.estimatedNet],['Peak gross',run.peakPnl],['Active stop floor',run.stopFloor]]) values.append(node('span',label+': '+money(value)));
          details.append(values);
          if(!pnl.marksFresh)details.append(node('p','Current combined P&L unavailable: waiting for fresh, synchronized quotes or entry confirmation.','notice'));
          if(run.riskSettings) {
            const scale=run.riskSettings.amountMode==='PER_LOT' ? (run.lots||1) : 1;
            const rules=fields.filter(([key])=>run.riskSettings[key]!=null).map(([key,label])=>label+' '+money(run.riskSettings[key]*scale)).join(' · ');
            details.append(node('p','Run controls (total gross ₹): '+rules,'muted'));
          }
          details.append(node('p',run.message||'','muted'));
          const actions=node('div',null,'actions');
          const close=node('button',run.status==='CLOSING'?'Retry close this instance':'Close this instance','danger');close.type='button';close.disabled=run.status==='CLOSED';
          close.onclick=async event=>{event.preventDefault();close.disabled=true;try {await api('/api/strategies/runs/'+encodeURIComponent(run.id)+'/close','POST',{});await load();}catch(error){close.disabled=false;details.append(node('p',error.message,'notice'));}};actions.append(close);
          if(run.subscriptionId!=null && run.status!=='CLOSED') {
            const all=node('button','Close all & pause strategy');all.type='button';
            all.onclick=async()=>{all.disabled=true;try {await api('/api/strategies/subscriptions/'+run.subscriptionId+'/close-and-pause','POST',{});await load();}catch(error){all.disabled=false;details.append(node('p',error.message,'notice'));}};actions.append(all);
          }
          details.append(actions);
          const wrap=node('div',null,'table-wrap'),table=node('table'),head=node('tr');
          for(const label of ['Trade','Symbol','Leg','Quantity','Actual entry','Exit','Status','Gross P&L','Source'])head.append(node('th',label));
          table.append(head);
          for(const leg of view.positions) {const row=node('tr');for(const value of [leg.id,leg.symbol,[leg.strikePrice,leg.optionType,leg.expiry].filter(Boolean).join(' '),leg.quantity,money(leg.actualEntryPrice),money(leg.exitPrice),leg.status,money(leg.pnl)])row.append(node('td',value));const sourceCell=node('td');sourceCell.append(sourceTag(leg.source));row.append(sourceCell);table.append(row);}
          wrap.append(table);details.append(wrap);container.append(details);
        }
        if(!result.content.length)container.append(node('p','No tracked strategy instances yet. New strategy entries will appear here.','muted'));
        const prev=node('button','← Previous instances'),next=node('button','Next instances →');prev.type=next.type='button';prev.disabled=result.first||page===0;next.disabled=result.last;
        prev.onclick=()=>{page--;load();};next.onclick=()=>{page++;load();};controls.append(prev,next);container.append(controls);
      }catch(error){if(container.runViewToken===token)container.replaceChildren(node('p','Strategy instances could not load: '+error.message,'notice'));}
    }
    await load();
  }
  const fields=[['profitTarget','Profit target'],['maxLoss','Maximum loss'],['trailActivation','Activate profit trail at'],['trailGiveback','Profit giveback'],['slStepProfit','Trailing SL: profit step'],['slStepRaise','Trailing SL: raise stop by']];
  function riskForm(container,lotsInput) {
    if(!container || container.dataset.initialized)return;container.dataset.initialized='true';
    const fieldset=node('fieldset'),legend=node('legend','Optional controls for each strategy instance');fieldset.append(legend);
    const modeLabel=node('label','Rupee amount basis'),mode=node('select');mode.dataset.riskField='amountMode';
    for(const [value,label] of [['TOTAL','Total amount for the instance'],['PER_LOT','Per option lot (multiplied by lots)']]) {const option=node('option',label);option.value=value;mode.append(option);}modeLabel.append(mode);fieldset.append(modeLabel);
    const grid=node('div',null,'run-risk-grid');
    for(const [key,label] of fields) {const field=node('label',label+' (₹)'),input=node('input');input.type='number';input.min='0';input.max='999999999.99';input.step='0.01';input.placeholder='Disabled';input.dataset.riskField=key;field.append(input);grid.append(field);}
    fieldset.append(grid,node('small','Combined gross P&L across all positions, before costs/slippage. Blank/zero disables a rule. Set both profit-trail fields together; trailing SL requires max loss and both step fields. Stops never move backwards.'));
    container.append(fieldset);
  }
  function readRisk(container) {
    if(!container)return null;const risk={};let enabled=false;
    for(const input of container.querySelectorAll('[data-risk-field]')) {
      const key=input.dataset.riskField;if(key==='amountMode'){risk[key]=input.value;continue;}
      const text=input.value.trim();if(text && (!/^\d+(\.\d{1,2})?$/.test(text)||Number(text)>999999999.99))throw new Error('Run controls require nonnegative rupees with up to two decimals.');
      risk[key]=text && Number(text)>0?Number(text):null;enabled ||= risk[key]!=null;
    }
    return enabled?risk:null;
  }
  function resetRisk(container) {if(container)for(const input of container.querySelectorAll('[data-risk-field]'))if(input.tagName==='SELECT')input.value='TOTAL';else input.value='';}
  window.StrategyRunView={mount,riskForm,readRisk,resetRisk,sourceTag,statusTag,timestamp};
})();
