// Mock-only browser regression. Run with Node + Playwright; optional BROWSER_EXECUTABLE_PATH.
// No backend, broker, credentials or live trading is contacted.
const {chromium} = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname,'../..');
const account = id => ({id,brokerName:'Dhan',clientCode:'Sample '+id,connected:true,active:true,tradingEnabled:true});
const records = ['ACTIVE','TRIGGERED','CANCELLED'].map((status,i)=>({id:i+1,templateId:'Stock mover',symbol:status,status,brokerCredentialsId:7}));
const sourceLabel='expiry-straddle:2026-10-19:1:7:NIFTY:AM';
const unsafeSource='<img src=x onerror=alert(1)>';
const templates = [{id:'EXPIRY_LONG_STRADDLE',name:'Expiry straddle',description:'Mock straddle'},{id:'STOCK_0925_MOVER_ATR_BREAKOUT',name:'Stock mover',description:'Mock stock'},{id:'ST_RSI_EMA_ADX_CE',name:'Other option',description:'Mock option'}];
(async()=>{
  const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE_PATH ? {executablePath:process.env.BROWSER_EXECUTABLE_PATH}: {})});
  try {
    async function fixture(brokers,admin=false) {
      const page=await browser.newPage({viewport:{width:1360,height:960}});
      const errors=[];page.on('pageerror',e=>errors.push(e.message));
      const starts=[],closes=[];let rejectStart=false;
      const run=(id,instance)=>({run:{id,templateId:'EXPIRY_LONG_STRADDLE',tradingDay:'2026-10-19',instance,status:'OPEN',symbol:'NIFTY',brokerCredentialsId:7,subscriptionId:4},pnl:{realizedGross:0,openGross:2000,totalGross:2000,marksFresh:true,openPositions:2},positions:[{id:11,symbol:'NIFTY',optionType:'CE',quantity:65,status:'EXECUTED',actualEntryPrice:100},{id:12,symbol:'NIFTY',optionType:'PE',quantity:65,status:'EXECUTED',actualEntryPrice:100}],requests:2});
      const groups=[run('run-am','AM'),run('run-pm','PM')];groups[1].pnl.marksFresh=false;groups[1].pnl.totalGross=null;groups[0].positions.forEach(leg=>leg.source=sourceLabel);groups[1].positions[0].source=unsafeSource;
      let analytics={
        summary:{effectiveRealizedPnl:-320.5,realizedPnl:1000,totalTradeCost:1320.5,totalClosedTrades:5,winRate:60,openTrades:2},
        bySymbol:[{symbol:'PROFIT',closedCount:3,realizedPnl:1250,winRate:100},{symbol:'LOSS',closedCount:2,realizedPnl:-250,winRate:0},{symbol:'ZERO',closedCount:1,realizedPnl:0,winRate:50},{symbol:'MISSING',closedCount:0,realizedPnl:null,winRate:null},{symbol:'INVALID',closedCount:0,realizedPnl:'invalid',winRate:null}],
        byDay:[250,-250,0,1000,null,1000].map((value,i)=>({date:'2026-10-0'+(i+1),closedCount:1,realizedPnl:value,cumulativeRealizedPnl:value})),
        recentClosedTrades:[{symbol:'PROFIT',quantity:10,entryPrice:100,exitPrice:125,pnl:250,exitReason:'TARGET'},{symbol:'LOSS',quantity:10,entryPrice:100,exitPrice:95,pnl:-50,exitReason:'STOP_LOSS'}]
      };
      await page.route('**/*',route=>{
        const url=new URL(route.request().url()), endpoint=url.pathname;
        if(url.hostname!=='ui.test')return route.abort();
        const json=(body,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)});
        if(endpoint==='/portal'||endpoint==='/admin-dashboard.html')return route.fulfill({contentType:'text/html',body:fs.readFileSync(path.join(root,'src/main/resources',endpoint==='/portal'?'templates/portal.html':'static/admin-dashboard.html'),'utf8')});
        if(endpoint.startsWith('/portal/')||endpoint==='/admin-dashboard.js'||endpoint==='/strategy-runs.js'||endpoint==='/strategy-runs.css')return route.fulfill({contentType:endpoint.endsWith('.js')?'text/javascript':'text/css',body:fs.readFileSync(path.join(root,'src/main/resources/static',endpoint),'utf8')});
        if(endpoint==='/api/analytics/trades')return json(analytics);
        if(endpoint==='/api/user/me')return json({id:1,username:'Sample user',displayName:'Sample user',admin});
        if(endpoint==='/api/auth/csrf-token')return json({header:'X-CSRF-TOKEN',token:'mock-only',parameter:'_csrf'});
        if(endpoint==='/api/user/brokers')return json(brokers);
        if(/^\/admin\/app-users\/\d+\/brokers$/.test(endpoint))return json(endpoint.includes('/2/')?[account(8),account(9)]:brokers);
        if(endpoint==='/admin/app-users')return json([{id:1,username:'User one'},{id:2,username:'User two'}]);
        if(endpoint==='/api/user/onboarding')return json({connectedBrokers:brokers.filter(b=>b.connected).length,activeStrategies:2,orderRequests:0,steps:[]});
        if(endpoint==='/api/strategies/templates')return json(templates);
        if(endpoint==='/api/strategies/subscriptions') {
          const filter=url.searchParams.get('status')||'ACTIVE';
          return json(records.filter(r=>filter==='ALL'||(filter==='ACTIVE'?['ACTIVE','TRIGGERED'].includes(r.status):r.status===filter)));
        }
        if(endpoint==='/api/strategies/runs')return json({content:groups,first:true,last:true});
        if(endpoint.endsWith('/close')) {closes.push(endpoint);groups.find(v=>endpoint.includes(v.run.id)).run.status='CLOSING';return json({});}
        if(endpoint==='/api/strategies/start') {
          starts.push(route.request().postDataJSON());return rejectStart?json({message:'Mock broker unavailable'},503):json({id:4,status:'ACTIVE'});
        }
        if(endpoint==='/api/orders/requests'||endpoint==='/api/orders/executed')return json({content:[{id:99,symbol:'NIFTY',quantity:65,entryPrice:100,status:'FAILED',source:unsafeSource}],totalElements:1,number:0,totalPages:1,first:true,last:true});
        if(endpoint.startsWith('/api/scripts/instruments/'))return json({instruments:['TCS','NIFTY']});
        if(endpoint==='/api/scripts/strikes')return json({strikes:[25000]});
        if(endpoint==='/api/scripts/expiries')return json({expiries:['29/10/2026']});
        return json([]);
      });
      await page.goto('http://ui.test/'+(admin?'admin-dashboard.html':'portal#strategies'));
      return {page,errors,starts,closes,reject:()=>rejectStart=true,setAnalytics:value=>analytics=value};
    }
    const {page:p,errors,starts,closes}=await fixture([account(7)]);
    await p.locator('#strategyCatalog article').filter({hasText:'Expiry straddle'}).getByRole('button').click();
    const form=p.locator('#subscriptionForm');
    assert.equal(await form.locator('[name=symbol]').inputValue(),'NIFTY');
    assert.equal(await form.locator('[data-risk-field=amountMode]').inputValue(),'PER_LOT');
    await form.locator('[data-risk-field=profitTarget]').fill('2000');
    await form.locator('[data-risk-field=maxLoss]').fill('1000');
    await form.locator('[name=lots]').fill('2');await form.locator('[name=brokerCredentialsId]').selectOption('7');await form.locator('[name=consent]').check();
    await form.locator('button[type=submit]').click();await p.waitForFunction(()=>!document.querySelector('#strategyDialog').open);
    assert.equal(starts[0].riskSettings.profitTarget,2000);assert.equal(starts[0].riskSettings.maxLoss,1000);assert.equal(starts[0].riskSettings.amountMode,'PER_LOT');assert.equal(starts[0].lots,2);
    await p.locator('[data-view=orders]').click();
    assert.deepEqual(await p.locator('#strategyRuns summary .source-tag').allTextContents(),[sourceLabel,unsafeSource,'Unspecified']);
    assert.equal(await p.locator('#executions .source-tag').textContent(),unsafeSource);
    assert.equal(await p.locator('#orderRequests .source-tag').textContent(),unsafeSource);
    assert.equal(await p.locator('#strategyRuns img, #executions img').count(),0);
    await p.locator('#strategyRuns details').first().locator('summary').click();
    assert.equal(await p.locator('#strategyRuns details').first().locator('td').count(),18);
    await p.locator('#strategyRuns details').first().getByRole('button',{name:'Close this instance',exact:true}).click();
    await p.waitForFunction(()=>document.querySelector('#strategyRuns summary').textContent.includes('CLOSING'));
    assert.deepEqual(closes,['/api/strategies/runs/run-am/close']);
    assert.ok((await p.locator('#strategyRuns details').nth(1).locator('summary').textContent()).startsWith('EXPIRY_LONG_STRADDLE · 2026-10-19 PM · OPEN · Unavailable'));
    assert.deepEqual(errors,[]);
    const admin=await fixture([account(7)],true);const a=admin.page;
    await a.locator('#userTabs button').first().click();
    await a.locator('#strategyTemplate').selectOption('EXPIRY_LONG_STRADDLE');
    await a.locator('#adminRunRisk [data-risk-field=profitTarget]').fill('2000');await a.locator('#strategyLots').fill('3');
    await a.locator('#strategyBroker').selectOption('7');await a.locator('#applyStrategyBtn').click();
    await a.waitForFunction(()=>document.querySelector('#strategyResult').textContent.includes('STARTED'));
    assert.equal(admin.starts[0].riskSettings.profitTarget,2000);assert.equal(admin.starts[0].riskSettings.amountMode,'PER_LOT');assert.equal(admin.starts[0].lots,3);
    assert.deepEqual(await a.locator('#adminStrategyRuns summary .source-tag').allTextContents(),[sourceLabel,unsafeSource,'Unspecified']);
    assert.equal(await a.locator('#user-executed-table .source-tag').textContent(),unsafeSource);
    assert.equal(await a.locator('#user-executed-table img').count(),0);
    await a.locator('#adminStrategyRuns details').first().locator('summary').click();
    await a.locator('#adminStrategyRuns details').first().getByRole('button',{name:'Close this instance',exact:true}).click();
    await a.waitForFunction(()=>document.querySelector('#adminStrategyRuns summary').textContent.includes('CLOSING'));
    assert.deepEqual(admin.closes,['/api/strategies/runs/run-am/close']);
    await a.setViewportSize({width:390,height:844});
    assert.deepEqual(admin.errors,[]);
    await a.screenshot({path:path.join(require('node:os').tmpdir(),'strategy-runs-admin-mobile.png'),fullPage:true});
    await p.setViewportSize({width:390,height:844});
    await p.locator('#strategyRuns details').first().locator('summary').click();
    await p.screenshot({path:path.join(require('node:os').tmpdir(),'strategy-runs-portal-mobile.png'),fullPage:true});
    console.log('PASS: source tags (deduplicated/multiple/missing/literal HTML) on grouped and individual portal/admin trades, shared per-lot risk payloads, complete grouped legs, single-click AM-only close, CLOSING acknowledgement, PM retained, missing P&L unavailable, portal/admin/mobile, no page errors; all API calls mocked');
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
