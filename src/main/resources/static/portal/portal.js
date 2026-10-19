'use strict';
(async function () {
  const $ = selector => document.querySelector(selector);
  const state = {brokers: [], templates: [], subscriptions: [], requests: [], executions: [], requestPage: 0, executionPage: 0};
  let pendingOrder;
  function confirmAction(message) {
    const dialog=$('#actionDialog');$('#actionDescription').textContent=message;dialog.returnValue='cancel';
    return new Promise(resolve=>{dialog.addEventListener('close',()=>resolve(dialog.returnValue==='confirm'),{once:true});dialog.showModal();});
  }
  function node(tag, text, className) { const el = document.createElement(tag); if (text != null) el.textContent = text; if (className) el.className = className; return el; }
  function notify(text, error = false) { const el = $('#message'); el.textContent = text; el.hidden = false; el.className = 'notice ' + (error ? 'error' : 'success'); el.scrollIntoView({behavior: 'smooth', block:'nearest'}); }
  async function api(url, method = 'GET', body) {
    const headers = {Accept: 'application/json'};
    if (method !== 'GET') { const csrf = await api('/api/auth/csrf-token'); headers[csrf.header] = csrf.token; headers['Content-Type'] = 'application/json'; }
    const response = await fetch(url, {method, credentials: 'same-origin', headers, body:body == null ? undefined : JSON.stringify(body)});
    const raw = await response.text(); let result; try { result = JSON.parse(raw); } catch { result = raw; }
    if (response.status === 401) { location.assign('/login'); throw new Error('Your session expired. Please sign in.'); }
    if (!response.ok) throw new Error(typeof result === 'string' ? (result.startsWith('<') ? 'Unable to complete this request. Refresh and try again.' : result) : result.message || result.error || 'Request failed');
    return result;
  }
  async function busy(button, action) {
    const errorBox=button.closest('#strategyDialog') ? $('#strategyFormError') : null;
    if(errorBox)errorBox.hidden=true;
    button.disabled=true;
    try {await action();} catch(error) {
      if(errorBox){errorBox.textContent=error.message;errorBox.hidden=false;errorBox.focus();}
      else notify(error.message,true);
    } finally {button.disabled=false;}
  }
  function button(text, action, style = 'secondary') { const el = node('button', text, style); el.type = 'button'; el.addEventListener('click', () => busy(el, action)); return el; }
  function badge(text, good = false) { return node('span', text, 'badge' + (good ? ' good' : '')); }
  function view(name) {
    if (!['overview', 'brokers', 'strategies', 'orders', 'analytics', 'settings'].includes(name)) name = 'overview';
    for (const el of document.querySelectorAll('[id^="view-"]')) el.hidden = el.id !== 'view-' + name;
    for (const el of document.querySelectorAll('[data-view]')) { if (el.dataset.view === name) el.setAttribute('aria-current','page'); else el.removeAttribute('aria-current'); }
    history.replaceState(null, '', '#'+name); $('#main').focus({preventScroll:true});
    if (name === 'analytics') loadAnalytics().catch(e => notify(e.message, true));
    if (name === 'orders') loadInstruments().catch(e => notify(e.message, true));
  }
  document.querySelectorAll('[data-view],[data-goto]').forEach(el => el.addEventListener('click', () => {el.closest('dialog')?.close();view(el.dataset.view || el.dataset.goto);}));
  document.querySelectorAll('[data-close]').forEach(el => el.addEventListener('click', () => el.closest('dialog').close()));
  function table(container, columns, rows, action) {
    container.replaceChildren(); container.tabIndex=0; container.setAttribute("role","region"); container.setAttribute("aria-label",container.id.replaceAll(/([A-Z])/g," $1")+" table");
    if (!rows.length) { container.append(node('div', 'Nothing here yet. Your activity will appear here.', 'empty')); return; }
    const t = node('table'); const head = node('thead'); const hr = node('tr');
    columns.forEach(c => hr.append(node('th', c[0], c[2]))); if (action) hr.append(node('th', 'Actions')); head.append(hr); t.append(head);
    const body = node('tbody');
    rows.forEach(row => { const tr = node('tr'); columns.forEach(c => { const td = node('td', null, c[2]); const value = typeof c[1] === 'function' ? c[1](row) : row[c[1]]; if (value instanceof Node) td.append(value); else td.textContent = value == null ? '—' : String(value); tr.append(td); }); if (action) { const td = node('td'); action(row, td); tr.append(td); } body.append(tr); });
    t.append(body); container.append(t);
  }
  const orderColumns = [['Instrument', 'symbol'], ['Exchange','exchange'], ['Quantity', row => row.lots ? row.lots+' lots' : row.quantity], ['Entry ₹', 'entryPrice'], ['Stop-loss ₹','stopLoss'], ['Target ₹','target1'], ['Status', row => window.StrategyRunView?.statusTag(row.status || 'PENDING') || badge(row.status || 'PENDING')], ['Source', r=>window.StrategyRunView?.sourceTag(r.source) || r.source || 'Unspecified']];
  function readyBrokers() { return state.brokers.filter(b => ['Sharekhan','Dhan'].includes(b.brokerName) && b.connected && b.active && b.tradingEnabled); }
  function brokerLabel(b) { return b.brokerName + ' · ' + (b.clientCode || 'Account ' + b.id); }
  function refreshSelectors() {
    for (const selector of ['#orderBroker', '#strategyBroker']) {
      const el = $(selector), previous = el.value; el.replaceChildren();
      el.append(new Option(readyBrokers().length ? 'Choose a broker account' : 'Connect a broker and enable trading first', ''));
      const brokers=readyBrokers();
      brokers.forEach(b => el.append(new Option(brokerLabel(b), b.id, brokers.length===1)));
      el.value=brokers.some(b=>String(b.id)===previous) ? previous : brokers.length===1 ? String(brokers[0].id) : '';
    }
    const ab=$('#analyticsBroker'); const selected=ab.value; ab.replaceChildren(new Option('All my brokers',''), ...state.brokers.map(b=>new Option(brokerLabel(b),b.id))); ab.value=selected;
    $('#strategyGate').hidden = readyBrokers().length > 0;
    $('#strategyDialogGate').hidden=readyBrokers().length > 0;
    $('#subscriptionForm button[type=submit]').disabled=!readyBrokers().length;
    $('#orderForm button[type=submit]').disabled = !readyBrokers().length;
    $('#orderGate').textContent = readyBrokers().length ? '' : 'Connect a broker and enable trading in Brokers to continue.';
  }
  function renderOverview() {
    const status=state.onboarding;
    $('#brokerCount').textContent=status ? status.connectedBrokers : '—';
    $('#strategyCount').textContent=status ? status.activeStrategies : '—';
    $('#orderCount').textContent=status ? status.orderRequests : '—';
    $('#brokerStatus').textContent=status ? (status.connectedBrokers ? 'CONNECTION VERIFIED':'SETUP NEEDED') : 'Loading';
    $('#brokerStatus').className='badge '+(status && status.connectedBrokers ? 'good':'warn');
    const details={ACCOUNT:'You’re signed in to your personal workspace.',BROKER:'Add broker credentials and verify the connection.',TRADING:'Explicitly enable new trading for your connected account.',WORKFLOW:'Subscribe to a strategy or review your first order.'};
    const steps=status ? status.steps : [];
    $('#progress').textContent=status ? steps.filter(s=>s.complete).length+' of '+steps.length+' complete':'Loading progress';
    $('#onboardingSteps').replaceChildren();
    steps.forEach((s,i)=>{const row=node('div',null,'step'+(s.complete?' done':''));row.append(node('span',s.complete?'✓':i+1,'step-number'));const body=node('div');body.append(node('strong',s.title),node('p',details[s.id] || ''));if(!s.complete)body.append(button('Continue →',async()=>view(s.destination),'text-button'));row.append(body);$('#onboardingSteps').append(row);});
    table($('#recentOrders'), orderColumns, state.requests.slice(0,5)); refreshSelectors();
  }
  function renderBrokers() {
    const list = $('#brokerAccounts'); list.replaceChildren();
    if (!state.brokers.length) list.append(node('div', 'No broker connected yet. Add a broker account to get started.', 'empty'));
    state.brokers.forEach(b => {
      const card = node('article', null, 'card'); const title = node('div',null,'row'); title.append(node('h2', brokerLabel(b)), badge(b.connected ? 'CONNECTED' : 'NOT CONNECTED', b.connected)); card.append(title);
      card.append(node('p', b.connected ? 'Session verified until ' + new Date(b.verifiedUntil).toLocaleString('en-IN') : 'Credentials saved. Verify your connection to continue.', 'muted'));
      card.append(badge(b.tradingEnabled ? 'NEW TRADING ENABLED' : 'NEW TRADING DISABLED', b.tradingEnabled));
      const actions = node('div',null,'actions');
      if (b.brokerName !== 'Dhan' || b.hasTotpSecret) actions.append(button(b.brokerName === 'Dhan' ? 'Generate token with TOTP' : b.connected ? 'Reconnect' : 'Verify connection', async () => {
        const result = await api('/api/user/brokers/'+b.id+'/connect','POST',{}); notify(result.message); await loadBrokers();
      }));
      if (b.brokerName === 'Dhan' && b.hasApiKey && b.hasApiSecret) actions.append(button('Login with Dhan API key', async () => {
        const result = await api('/api/user/brokers/'+b.id+'/dhan/consent','POST',{}); location.assign(result.loginUrl);
      }));
      if (b.brokerName === 'Dhan') card.append(node('p','Cash and option limit orders. Whitelist the server static IP in Dhan before enabling trading.','muted'));
      if (['Sharekhan','Dhan'].includes(b.brokerName)) actions.append(button(b.tradingEnabled ? 'Disable new trading' : 'Enable trading', async () => {
        if (!b.tradingEnabled && !await confirmAction('Enable live trading for this broker account? Orders and strategy subscriptions can then submit broker orders.')) return;
        await api('/api/user/brokers/'+b.id+'/trading','POST',{enabled:!b.tradingEnabled}); notify(b.tradingEnabled ? 'New trading disabled. Existing positions remain open.' : 'Trading enabled.'); await loadBrokers();
      }, b.tradingEnabled ? 'secondary' : 'primary'));
      actions.append(button('Edit credentials', async () => {
        const f = $('#brokerForm'); f.reset(); f.elements.id.value = b.id; f.elements.brokerName.value = b.brokerName; f.elements.brokerName.disabled = true; configureBrokerForm();
        for (const input of f.querySelectorAll('input:not([type=hidden])')) input.required = false;
        $('#brokerFormTitle').textContent = 'Update credentials · ' + brokerLabel(b);
        $('#brokerFormCard').scrollIntoView({behavior:'smooth'}); f.elements.clientCode.focus();
        notify('Leave fields blank to keep saved values. Updating credentials requires no active subscriptions, pending orders, or open positions.');
      }));
      actions.append(button('Disconnect', async () => {
        if (!await confirmAction('Disconnect this broker? Cancel active subscriptions and pending orders, and close open positions first.')) return;
        const result = await api('/api/user/brokers/'+b.id+'/disconnect','POST',{}); notify(result.message); await loadBrokers();
      },'text-button')); card.append(actions); list.append(card);
    });
  }
  async function loadOnboarding() { state.onboarding=await api('/api/user/onboarding'); renderOverview(); }
  async function loadBrokers() { state.brokers = await api('/api/user/brokers'); renderBrokers(); await loadOnboarding(); renderStrategies(); }
  function configureBrokerForm() {
    const f = $('#brokerForm'), dhan = f.elements.brokerName.value === 'Dhan';
    const apiLogin = $('#dhanMethod').value === 'api';
    $('#dhanMethodRow').hidden = !dhan; $('#dhanAuthHelp').hidden = !dhan;
    $('#brokerFormNote').textContent = dhan ? 'Verify the connection after saving. Whitelist the server static IP in Dhan, then explicitly enable trading.' : 'Saving credentials does not enable trading. Verify the connection, then explicitly enable trading.';
    const visible = dhan ? (apiLogin ? ['clientCode','apiKey','secretKey'] : ['clientCode','brokerPassword','totpSecret']) : ['customerId','clientCode','apiKey','brokerPassword','totpSecret','secretKey'];
    for (const name of ['customerId','clientCode','apiKey','brokerPassword','totpSecret','secretKey']) {
      const input=f.elements[name]; input.closest('label').hidden=!visible.includes(name);
      input.disabled=!visible.includes(name); input.required=!f.elements.id.value && visible.includes(name);
    }
    f.elements.brokerPassword.maxLength = dhan ? 6 : 512;
    if (dhan) f.elements.brokerPassword.pattern='[0-9]{6}'; else f.elements.brokerPassword.removeAttribute('pattern');
    // Keep labels accessible when changing broker-specific names.
    f.elements.clientCode.closest('label').firstChild.textContent = dhan ? 'Dhan Client ID' : 'Client code';
    f.elements.brokerPassword.closest('label').firstChild.textContent = dhan ? 'Dhan PIN (6 digits)' : 'Broker password';
    $('#brokerFormTitle').textContent=(f.elements.id.value ? 'Update credentials · ' : 'Add your ') + f.elements.brokerName.value + ' account';
  }
  $('#brokerForm').elements.brokerName.addEventListener('change', configureBrokerForm);
  $('#dhanMethod').addEventListener('change', configureBrokerForm);
  configureBrokerForm();
  const dhanResult = new URLSearchParams(location.search).get('dhan');
  if (dhanResult) {
    notify(dhanResult === 'connected' ? 'Dhan token generated successfully.' : 'Dhan login failed or expired. Start login again.', dhanResult !== 'connected');
    history.replaceState(null, '', '/portal#brokers');
  }
  $('#showBrokerForm').addEventListener('click', () => {
    const f = $('#brokerForm'); f.reset(); f.elements.id.value = '';
    f.elements.brokerName.disabled = false; configureBrokerForm();
    f.elements.clientCode.focus();
  });
  $('#brokerForm').addEventListener('submit', event => { event.preventDefault(); const f=event.target; busy(f.querySelector('button'),async () => {
    const body = Object.fromEntries([...new FormData(f)].filter(([key,value]) => key !== 'id' && value !== '')); body.brokerName = f.elements.brokerName.value;
    const id = f.elements.id.value; await api('/api/user/brokers'+(id ? '/'+id : ''),id ? 'PUT':'POST',body);
    f.reset(); f.elements.id.value=''; f.elements.brokerName.disabled=false; configureBrokerForm(); notify('Credentials saved. Verify the broker connection next.'); await loadBrokers();
  }); });
  function renderStrategies() {
    $('#strategyCatalog').replaceChildren();
    if (!state.templates.length) $('#strategyCatalog').append(node('div','No strategies are currently available.','empty'));
    state.templates.forEach(template => {
      const card = node('article',null,'card'); card.append(node('div','STRATEGY','eyebrow'),node('h2',template.name),node('p',template.description));
      const el = button('Configure subscription →',async () => {
        window.StrategyRunView?.riskForm($('#subscriptionRunRisk'));
        window.StrategyRunView?.resetRisk($('#subscriptionRunRisk'));
        const f = $('#subscriptionForm'); f.reset(); refreshSelectors(); $('#strategyFormError').hidden=true; f.elements.templateId.value=template.id;
        const stockMover = template.id === 'STOCK_0925_MOVER_ATR_BREAKOUT';
        f.elements.intraday.disabled = template.id === 'EXPIRY_LONG_STRADDLE';
        if (f.elements.intraday.disabled) f.elements.intraday.checked = true;
        const straddle = template.id === 'EXPIRY_LONG_STRADDLE';
        $('#subscriptionProfitTargetRow').hidden = true;
        f.elements.profitTargetPerLot.disabled = true;
        $('#subscriptionRunRisk').querySelector('[data-risk-field=amountMode]').value=straddle ? 'PER_LOT' : 'TOTAL';
        const automatic = /FNO_0925|STOCK_0925|MARKETAUX/.test(template.id);
        f.elements.symbol.value = automatic ? 'FNO_UNIVERSE' : template.id === 'EXPIRY_LONG_STRADDLE' ? 'NIFTY' : '';
        f.elements.symbol.readOnly = automatic;
        $('#subscriptionLotsRow').hidden = stockMover;
        f.elements.lots.disabled = stockMover;
        $('#subscriptionInvestmentRow').hidden = !stockMover;
        f.elements.investmentPerStock.disabled = !stockMover;
        f.elements.investmentPerStock.required = stockMover;
        $('#strategyDialogTitle').textContent=template.name; $('#strategyDialogDescription').textContent=template.description;
        $('#strategyDialog').showModal();
      }); card.append(el); $('#strategyCatalog').append(card);
    });
    table($('#subscriptions'),[['Strategy','templateId'],['Broker',r=>{const b=state.brokers.find(b=>b.id===r.brokerCredentialsId);return b ? brokerLabel(b) : 'Account '+r.brokerCredentialsId;}],['Instruments','symbol'],['Size / investment',r=>r.investmentPerStock != null ? '₹'+r.investmentPerStock+' / stock' : r.lots+(r.profitTargetPerLot ? ' lots · profit target ₹'+r.profitTargetPerLot+' / lot / session (₹'+(r.profitTargetPerLot*r.lots).toFixed(2)+' total)' : '')],['Status',r=>badge(r.status,['ACTIVE','TRIGGERED'].includes(r.status))],['Latest update','lastMessage']],state.subscriptions,(row,td)=>{
      if (['ACTIVE','TRIGGERED'].includes(row.status)) td.append(button('Cancel',async()=>{
        if (!await confirmAction('Cancel future evaluations for this subscription? Existing orders and positions remain open.')) return;
        await api('/api/strategies/subscriptions/'+row.id+'/cancel','POST',{}); await loadStrategies(); notify('Subscription cancelled. Existing orders and positions remain open.');
      }));
    });
  }
  async function loadStrategies() { state.subscriptions=await api('/api/strategies/subscriptions?status='+encodeURIComponent($('#subscriptionStatusFilter').value)); renderStrategies(); await loadOnboarding(); }
  $('#subscriptionStatusFilter').addEventListener('change',()=>busy($('#subscriptionStatusFilter'),loadStrategies));
  $('#subscriptionForm').addEventListener('submit',event=>{event.preventDefault(); const f=event.target; busy(f.querySelector('button[type=submit]'),async()=>{
    const body=Object.fromEntries(new FormData(f)); delete body.consent; body.brokerCredentialsId=Number(body.brokerCredentialsId); body.intraday=f.elements.intraday.checked;
    if (body.templateId === 'EXPIRY_LONG_STRADDLE') {
      const target = String(body.profitTargetPerLot || '').trim();
      if (target && (!/^\d+(\.\d{1,2})?$/.test(target) || Number(target) > 999999999.99)) throw new Error('Enter a nonnegative profit target with up to two decimal places.');
      body.profitTargetPerLot = target && Number(target) > 0 ? Number(target) : null;
    } else { delete body.profitTargetPerLot; }
    if (body.templateId === 'STOCK_0925_MOVER_ATR_BREAKOUT') {
      const amount = String(body.investmentPerStock || '').trim();
      if (!/^\d+(\.\d{1,2})?$/.test(amount) || Number(amount) <= 0 || Number(amount) > 999999999.99) throw new Error('Enter a positive investment per stock with up to two decimal places.');
      body.investmentPerStock = Number(amount); body.lots = null;
    } else { body.lots = Number(body.lots); delete body.investmentPerStock; }
    body.riskSettings=window.StrategyRunView?.readRisk($('#subscriptionRunRisk')) || null;
    await api('/api/strategies/start','POST',body); $('#strategyDialog').close(); notify('Strategy subscription activated.'); await loadStrategies(); await loadOrders();
  });});
  async function loadOrders() {
    window.StrategyRunView?.mount($('#strategyRuns'),api);
    const [requests,executions]=await Promise.all([api('/api/orders/requests?page='+state.requestPage+'&size=10'),api('/api/orders/executed?page='+state.executionPage+'&size=10&'+new URLSearchParams([...new FormData($('#executionFilters'))].filter(([,v])=>v!=='')))]);
    state.requests=requests.content || requests; state.executions=executions.content || executions; state.requestTotal=requests.totalElements;
    table($('#orderRequests'),orderColumns,state.requests,(row,td)=>td.append(button('Cancel request',async()=>{
      if (!await confirmAction('Cancel this order request?')) return;
      await api('/api/trades/cancel-request/'+row.id,'POST',{}); notify('Request cancelled.'); await loadOrders();
    })));
    table($('#executions'),[['Instrument','symbol'],['Source',r=>window.StrategyRunView?.sourceTag(r.source) || r.source || 'Unspecified'],['Quantity','quantity'],['Entry ₹',r=>r.actualEntryPrice || r.entryPrice],['Stop-loss ₹','stopLoss'],['Target ₹','target1'],['Running / booked P&L',r=>positionPnlValue(r),'numeric'],['Costs',r=>money(r.totalTradeCost),'numeric'],['Net P&L',r=>pnlValue(r.effectivePnl),'numeric'],['Status',r=>window.StrategyRunView?.statusTag(r.status) || badge(r.status)],['Order ID','orderId']],state.executions,(row,td)=>{
      if (['EXECUTED','TARGET_ORDER_PLACED'].includes(row.status)) td.append(button('Close position',async()=>{
        if (!await confirmAction('Submit a live square-off order for this position?')) return;
        await api('/api/trades/square-off/'+row.id,'POST',{}); notify('Square-off requested. Check broker execution status.'); await loadOrders();
      }));
    });
    $('#requestPage').textContent='Page '+(state.requestPage+1); $('#executionPage').textContent='Page '+(state.executionPage+1);
    $('#previousRequests').disabled=state.requestPage===0; $('#nextRequests').disabled=requests.last == null ? state.requests.length<10 : requests.last;
    $('#previousExecutions').disabled=state.executionPage===0; $('#nextExecutions').disabled=executions.last == null ? state.executions.length<10 : executions.last;
    await loadOnboarding();
  }
  $('#executionFilters').addEventListener('submit',event=>{event.preventDefault();state.executionPage=0;busy(event.target.querySelector('button'),loadOrders);});
  $('#refreshOrders').addEventListener('click',event=>busy(event.target,loadOrders));
  for (const [id,key,delta] of [['previousRequests','requestPage',-1],['nextRequests','requestPage',1],['previousExecutions','executionPage',-1],['nextExecutions','executionPage',1]]) $('#'+id).addEventListener('click',event=>busy(event.target,async()=>{state[key]=Math.max(0,state[key]+delta);await loadOrders();}));
  const query = object => new URLSearchParams(object).toString();
  async function loadInstruments() {
    const exchange=$('#orderExchange').value, derivatives=!['NC','BC'].includes(exchange);
    for (const id of ['optionTypeField','strikeField','expiryField']) $('#'+id).hidden=!derivatives;
    $('#quantityLabel').textContent=derivatives?'Quantity (lots)':'Quantity (shares)';
    $('#orderStrike').required=derivatives; $('#orderExpiry').required=derivatives;
    const data=await api('/api/scripts/instruments/'+encodeURIComponent(exchange));
    $('#instrumentOptions').replaceChildren(...data.instruments.map(item=>new Option(item,item)));
  }
  async function loadStrikes() {
    const f=$('#orderForm'); if (['NC','BC'].includes(f.elements.exchange.value)) return;
    $('#orderStrike').replaceChildren(new Option('Choose strike','')); $('#orderExpiry').replaceChildren(new Option('Choose expiry',''));
    const data=await api('/api/scripts/strikes?'+query({exchange:f.elements.exchange.value,instrument:f.elements.instrument.value}));
    data.strikes.forEach(v=>$('#orderStrike').append(new Option(v,v)));
  }
  $('#orderExchange').addEventListener('change',()=>{ $('#orderInstrument').value=''; loadInstruments().catch(e=>notify(e.message,true)); });
  $('#orderInstrument').addEventListener('change',()=>loadStrikes().catch(e=>notify(e.message,true)));
  $('#orderStrike').addEventListener('change',()=>{ const f=$('#orderForm'); api('/api/scripts/expiries?'+query({exchange:f.elements.exchange.value,instrument:f.elements.instrument.value,strikePrice:f.elements.strikePrice.value})).then(data=>{ $('#orderExpiry').replaceChildren(new Option('Choose expiry',''),...data.expiries.map(v=>new Option(v,v))); }).catch(e=>notify(e.message,true)); });
  $('#orderForm').addEventListener('submit', event=>{
    event.preventDefault(); const f=event.target; const body=Object.fromEntries(new FormData(f));
    for (const key of ['brokerCredentialsId','quantity','entryPrice','stopLoss','target1','target2','target3','strikePrice']) body[key]=body[key] ? Number(body[key]) : null;
    if (['NC','BC'].includes(body.exchange)) { body.strikePrice=null; body.optionType=null; body.expiry=null; }
    body.intraday=f.elements.intraday.checked; body.action='BUY';
    pendingOrder=body; $('#orderSummary').replaceChildren();
    for (const [label,value] of [['Broker',f.elements.brokerCredentialsId.selectedOptions[0].textContent],['Instrument',body.instrument],['Exchange',body.exchange],['Contract',body.optionType],['Strike',body.strikePrice],['Expiry',body.expiry],[body.optionType ? 'Lots' : 'Shares',body.quantity],['Entry ₹',body.entryPrice],['Stop-loss ₹',body.stopLoss],['Target 1 ₹',body.target1],['Target 2 ₹',body.target2],['Target 3 ₹',body.target3],['Broker product',body.optionType?'Carry-forward':'Delivery'],['Same-day exit by app',body.intraday?'Enabled':'Disabled']]) if(value!=null) $('#orderSummary').append(node('dt',label),node('dd',value));
    $('#confirmOrder').reset(); $('#reviewDialog').showModal();
  });
  $('#confirmOrder').addEventListener('submit',event=>{event.preventDefault(); busy(event.target.querySelector('button[type=submit]'),async()=>{
    const result=await api('/api/trades/trigger-on-price','POST',pendingOrder); pendingOrder=null; $('#reviewDialog').close();
    notify('Order request #'+result.id+' submitted. Status: '+result.status+'.'); state.requestPage=0; await loadOrders();
  });});
  $('#passwordForm').addEventListener('submit',event=>{event.preventDefault(); const f=event.target; busy(f.querySelector('button'),async()=>{await api('/api/user/password','POST',Object.fromEntries(new FormData(f))); f.reset(); location.assign('/login?passwordUpdated');});});
  function financialNumber(value) {
    if(value==null || !['number','string'].includes(typeof value) || (typeof value==='string' && !value.trim()))return null;
    const number=Number(value);return Number.isFinite(number) ? number : null;
  }
  function positionPnlValue(row) {
    if(row.runningPnl==null)return pnlValue(row.pnl);
    const el=node('div');el.append(pnlValue(row.runningPnl));
    el.append(node('small','Last price '+money(row.lastKnownPrice)+' · observed '+new Date(row.runningPnlAsOf).toLocaleString('en-IN'),'muted'));
    el.title=row.runningPnlSource || 'Last known price';return el;
  }
  const currency=new Intl.NumberFormat('en-IN',{style:'currency',currency:'INR',minimumFractionDigits:2,maximumFractionDigits:2});
  const money = value => financialNumber(value)==null ? '—' : currency.format(financialNumber(value));
  function pnlTone(value) {
    const number=financialNumber(value);return number==null || number===0 ? 'neutral' : number>0 ? 'positive' : 'negative';
  }
  function pnlValue(value) {
    const number=financialNumber(value), tone=pnlTone(value);
    const text=(tone==='positive' ? '+' : '')+money(value);
    const result=node('span',text,'pnl-value '+tone);
    result.setAttribute('aria-label',number==null ? 'No P&L data' : (tone==='positive' ? 'Profit ' : tone==='negative' ? 'Loss ' : 'Break-even ')+money(Math.abs(number)));
    return result;
  }
  function winRate(value) {
    const number=financialNumber(value);
    if(number==null || number<0 || number>100)return node('span','—','muted');
    const result=node('span',null,'win-rate');result.append(node('span',number.toFixed(1)+'%'));
    const track=node('span',null,'win-rate-track'),fill=node('span');
    track.setAttribute('aria-hidden','true');fill.style.width=number+'%';track.append(fill);result.append(track);return result;
  }
  function renderEquityChart(days) {
    const chart=$('#equityChart');chart.replaceChildren();
    const points=days.map((day,index)=>({day,index,value:financialNumber(day.cumulativeRealizedPnl)})).filter(point=>point.value!=null);
    if(!points.length){chart.append(node('div','No realized P&L data in this period. Adjust your filters to view performance.','empty'));return;}
    const values=points.map(point=>point.value),low=Math.min(0,...values),high=Math.max(0,...values),range=high-low||1;
    const x=index=>100+index*860/Math.max(1,days.length-1),y=value=>230-(value-low)*190/range;
    const svgNs='http://www.w3.org/2000/svg';
    function svgNode(tag,attributes){const element=document.createElementNS(svgNs,tag);for(const [key,value] of Object.entries(attributes))element.setAttribute(key,value);return element;}
    const svg=svgNode('svg',{viewBox:'0 0 1000 280',role:'img','aria-label':'Cumulative gross realized P&L in INR. Green above zero, red below zero. '+points[0].day.date+' to '+points.at(-1).day.date});
    const axisFormat=new Intl.NumberFormat('en-IN',{notation:'compact',maximumFractionDigits:1});
    for(let i=0;i<=4;i++) {
      const value=low+range*i/4;
      svg.append(svgNode('line',{x1:100,x2:960,y1:y(value),y2:y(value),stroke:'#e0e6ef'}));
      const label=svgNode('text',{x:8,y:y(value)+4,fill:'#66748b','font-size':12});label.textContent='₹'+axisFormat.format(value);svg.append(label);
    }
    svg.append(svgNode('line',{x1:100,x2:960,y1:y(0),y2:y(0),stroke:'#8795aa','stroke-dasharray':'5 5',class:'equity-zero'}));
    const color=value=>value>0 ? '#087b52' : value<0 ? '#ba3346' : '#66748b';
    function segment(x1,v1,x2,v2,tone){svg.append(svgNode('line',{x1,y1:y(v1),x2,y2:y(v2),stroke:color(tone),'stroke-width':3,'stroke-linecap':'round',class:'equity-segment'}));}
    for(let i=1;i<points.length;i++) {
      const before=points[i-1],after=points[i];
      if(after.index!==before.index+1)continue; // Do not fabricate values across missing days.
      if((before.value<0 && after.value>0)||(before.value>0 && after.value<0)) {
        const crossing=x(before.index)+(x(after.index)-x(before.index))*(-before.value)/(after.value-before.value);
        segment(x(before.index),before.value,crossing,0,before.value);segment(crossing,0,x(after.index),after.value,after.value);
      } else segment(x(before.index),before.value,x(after.index),after.value,after.value || before.value);
    }
    for(const point of points) {
      const circle=svgNode('circle',{cx:x(point.index),cy:y(point.value),r:3.5,fill:color(point.value),class:'equity-point'});
      const title=svgNode('title',{});title.textContent=point.day.date+': '+(point.value>0 ? '+' : '')+money(point.value);circle.append(title);svg.append(circle);
    }
    for(const point of [...new Set([points[0],points.at(-1)])]) {
      const label=svgNode('text',{x:x(point.index),y:266,'text-anchor':point===points[0] ? 'start' : 'end',fill:'#66748b','font-size':12});label.textContent=point.day.date;svg.append(label);
    }
    chart.append(svg);
  }
  async function loadAnalytics() {
    const params=new URLSearchParams([...new FormData($('#analyticsForm'))].filter(([,v])=>v!==''));
    const data=await api('/api/analytics/trades?'+params.toString());
    const summary=data.summary || {}; const cards=$('#analyticsSummary');cards.replaceChildren();
    for (const [label,value,financial] of [['Net realized P&L',summary.effectiveRealizedPnl,true],['Gross realized P&L',summary.realizedPnl,true],['Trading costs',summary.totalTradeCost,true],['Closed trades',summary.totalClosedTrades,false],['Win rate',summary.winRate == null ? '—' : summary.winRate.toFixed(1)+'%',false],['Open positions',summary.openTrades,false]]) {
      const card=node('div',null,'card metric-card');card.append(node('div',label,'stat-label'));
      const amount=node('div',financial ? money(value) : value == null ? '—' : value,'stat-value');
      if(financial && label!=='Trading costs') {
        const tone=pnlTone(value);amount.replaceChildren(pnlValue(value));card.classList.add('metric-'+tone);
      }
      if(label==='Trading costs')card.classList.add('metric-cost');
      card.append(amount);cards.append(card);
    }
    table($('#symbolAnalytics'),[['Instrument','symbol'],['Closed trades','closedCount','numeric'],['Gross P&L',r=>pnlValue(r.realizedPnl),'numeric'],['Win rate',r=>winRate(r.winRate),'numeric']],data.bySymbol || []);
    table($('#dayAnalytics'),[['Date','date'],['Closed trades','closedCount','numeric'],['Gross P&L',r=>pnlValue(r.realizedPnl),'numeric'],['Cumulative',r=>pnlValue(r.cumulativeRealizedPnl),'numeric']],data.byDay || []);
    table($('#closedTrades'),[['Instrument','symbol'],['Quantity','quantity','numeric'],['Entry',r=>money(r.entryPrice),'numeric'],['Exit',r=>money(r.exitPrice),'numeric'],['Gross P&L',r=>pnlValue(r.pnl),'numeric'],['Exit reason','exitReason']],data.recentClosedTrades || []);
    renderEquityChart(data.byDay || []);
  }
  $('#analyticsForm').addEventListener('submit',event=>{event.preventDefault();busy(event.target.querySelector('button'),loadAnalytics);});
  let pnlRefreshPending=false;
  setInterval(async()=>{
    if(document.hidden || $('#view-orders').hidden || pnlRefreshPending || document.querySelector('dialog[open]'))return;
    pnlRefreshPending=true;
    try{await loadOrders();}catch{}finally{pnlRefreshPending=false;}
  },15000);
  try {
    const me=await api('/api/user/me'); const name=me.displayName || me.username;
    $('#identity').textContent=name; $('#avatar').textContent=name.slice(0,1).toUpperCase(); $('#welcome').textContent='Welcome, '+name.split(' ')[0]; $('#profileEmail').textContent=me.email || me.username;
    $('#adminLink').hidden=!me.admin;
    state.templates=await api('/api/strategies/templates');
    const sources=await api('/api/analytics/sources'); (Array.isArray(sources)?sources:[]).forEach(v=>$('#analyticsSource').append(new Option(v,v)));
    await Promise.all([loadBrokers(),loadStrategies(),loadOrders()]); renderStrategies(); view(location.hash.slice(1));
  } catch(error) { notify(error.message,true); }
})();
