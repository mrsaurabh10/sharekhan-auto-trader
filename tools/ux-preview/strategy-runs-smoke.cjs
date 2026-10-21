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
const compactSourceLabel='Expiry straddle · AM';
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
        if(endpoint==='/api/orders/requests')return json({content:[{id:99,symbol:'NIFTY',quantity:65,entryPrice:100,status:'FAILED',source:unsafeSource},{id:100,symbol:'WIPRO-WAIT',quantity:10,entryPrice:189.5,status:'REJECTED',reason:'ENTRY_NOT_READY_NO_ORDER'},{id:101,symbol:'WIPRO-REJECTED',quantity:10,entryPrice:189.5,status:'REJECTED',reason:'ENTRY_BROKER_REJECTED'}],totalElements:3,number:0,totalPages:1,first:true,last:true});
        if(endpoint==='/api/orders/executed') {
          const trades=[['AXISBANK','FNO',1240,'PE',1235.1,1241.5,1227.32,625],['POLYCAB','FNO',8100,'PE',8050.5,8088,8000.9,125],
            ['UNIONBANK','STOCK',null,null,176.64,175.76,179,5],['UNIONBANK','STOCK',null,null,176.64,175.76,178.41,3],['UNIONBANK','STOCK',null,null,176.64,175.76,177.82,3],
            ['HAVELLS','awr',1120,'CE',29.6,29.6,34,500],['HAVELLS','awr',1120,'CE',29.6,25.1,31.75,500],['M&M','awr',2900,'CE',51.1,51.1,63.5,200]]
            .map(([symbol,source,strikePrice,optionType,entryPrice,stopLoss,target1,quantity],i)=>({id:10326-i,symbol,
              source:source==='awr'?source:'strategy:'+source+'_0925_MOVER_ATR_BREAKOUT',strikePrice,optionType,entryPrice,stopLoss,target1,quantity,
              exchange:source==='STOCK'?'NC':'NF',status:'EXITED_SUCCESS',triggeredAt:'2026-10-19T12:36:05',pnl:0}));
          trades.push({id:99,symbol:'NIFTY',quantity:65,entryPrice:100,status:'FAILED',source:unsafeSource});
          return json({content:trades,totalElements:trades.length,number:0,totalPages:1,first:true,last:true});
        }
        if(endpoint.startsWith('/api/scripts/instruments/'))return json({instruments:['TCS','NIFTY']});
        if(endpoint==='/api/scripts/strikes')return json({strikes:[25000]});
        if(endpoint==='/api/scripts/expiries')return json({expiries:['29/10/2026']});
        return json([]);
      });
      await page.goto('http://ui.test/'+(admin?'admin-dashboard.html':'portal#strategies'));
      return {page,errors,starts,closes,reject:()=>rejectStart=true,setAnalytics:value=>analytics=value};
    }
    async function checkOptionPicker(adminMode) {
      const sample=await fixture([account(7)],adminMode), page=sample.page;
      let orderWrites=0;
      page.on('request',r=>{if(r.method()==='POST' && /orders|manual-execute/.test(r.url())) orderWrites++;});
      await page.route('**/api/scripts/strikes?*',async route=>{
        const old=new URL(route.request().url()).searchParams.get('instrument')==='TCS';
        if(old) await new Promise(resolve=>setTimeout(resolve,200));
        await route.fulfill({contentType:'application/json',body:JSON.stringify({strikes:old?[100,200]:Array.from({length:11},(_,i)=>24500+i*50)})});
      });
      await page.route('**/api/scripts/expiries?*',async route=>{
        const old=new URL(route.request().url()).searchParams.get('strikePrice')==='24700';
        if(old) await new Promise(resolve=>setTimeout(resolve,200));
        await route.fulfill({contentType:'application/json',body:JSON.stringify({expiries:old?['stale-expiry']:['29/10/2026','05/11/2026']})});
      });
      const ex=page.locator(adminMode?'#exchange':'#orderExchange');
      const instr=page.locator(adminMode?'#instrument':'#orderInstrument');
      const strike=page.locator(adminMode?'#strikePrice':'#orderStrike');
      const expiry=page.locator(adminMode?'#expiry':'#orderExpiry');
      const type=page.locator(adminMode?'#optionType':'#orderForm [name=optionType]');
      if(adminMode) { await page.locator('#userTabs button').first().click(); await page.locator('#manualOrderComposer summary').click(); }
      else await page.locator('[data-view=orders]').click();
      await ex.selectOption('NF');
      if(adminMode) {
        await page.waitForFunction(()=>document.querySelector('#instrument').options.length>1);
        await instr.selectOption('TCS');await instr.selectOption('NIFTY');
      } else {
        await instr.fill('TCS');await instr.dispatchEvent('change');
        await instr.fill('NIFTY');await instr.press('Tab');
      }
      await page.waitForFunction(()=>document.querySelectorAll('.option-picker-grid button').length===10);
      await page.waitForTimeout(250);
      assert.equal(await strike.locator('option').count(),12,'stale strike response ignored');
      assert.equal(await page.locator('.option-picker-strike').count(),5);
      const visibleStrikes=await page.locator('.option-picker-strike').allTextContents();
      await page.getByRole('button',{name:'Select NIFTY 24700 CE',exact:true}).click();
      assert.deepEqual(await page.locator('.option-picker-strike').allTextContents(),visibleStrikes,'clicking a visible strike must keep rows stationary');
      assert.equal(await page.locator('.option-picker .is-selected').getAttribute('aria-label'),'Select NIFTY 24700 CE');
      await page.getByRole('button',{name:'Select NIFTY 24800 PE',exact:true}).click();
      await page.waitForFunction(id=>document.getElementById(id).options.length===3,adminMode?'expiry':'orderExpiry');
      await expiry.selectOption('29/10/2026');
      await page.waitForTimeout(250);
      assert.equal(await strike.inputValue(),'24800');assert.equal(await type.inputValue(),'PE');
      assert.equal(await expiry.inputValue(),'29/10/2026','stale expiry ignored');
      assert.equal(await page.locator('.option-picker [aria-pressed=true]').count(),1);
      assert.deepEqual(await page.locator('.option-picker-strike').allTextContents(),visibleStrikes,'expiry replies must keep rows stationary');
      assert.equal(await page.locator('.option-picker .is-selected').getAttribute('aria-label'),'Select NIFTY 24800 PE');
      const selectedColor=await page.locator('.option-picker .is-selected').evaluate(el=>getComputedStyle(el).backgroundColor);
      const previousColor=await page.getByRole('button',{name:'Select NIFTY 24700 CE',exact:true}).evaluate(el=>getComputedStyle(el).backgroundColor);
      assert.notEqual(selectedColor,previousColor,'highlight follows the current selection');
      // A response arriving between pointer-down and pointer-up must not replace the button.
      const second=page.getByRole('button',{name:'Select NIFTY 24800 CE',exact:true});
      const hit=await second.boundingBox();
      await page.mouse.move(hit.x+hit.width/2,hit.y+hit.height/2);await page.mouse.down();
      await expiry.evaluate(el=>el.append(new Option('12/11/2026','12/11/2026')));
      await page.waitForTimeout(40);await page.mouse.up();
      assert.equal(await type.inputValue(),'CE');
      assert.equal(await page.locator('.option-picker .is-selected').getAttribute('aria-label'),'Select NIFTY 24800 CE');
      assert.deepEqual(await page.locator('.option-picker-strike').allTextContents(),visibleStrikes);
      await page.getByRole('button',{name:'Select NIFTY 24850 CE',exact:true}).click();
      await page.waitForFunction(id=>document.getElementById(id).value==='29/10/2026',adminMode?'expiry':'orderExpiry');
      await page.getByRole('button',{name:'Higher →',exact:true}).click();
      assert.ok((await page.locator('.option-picker-strike').allTextContents()).includes('25000'));
      await strike.selectOption('24500');
      assert.ok((await page.locator('.option-picker-strike').allTextContents()).includes('24500'));
      await page.setViewportSize({width:390,height:844});
      const mobileRows=await page.locator('.option-picker-strike').allTextContents();
      for (const [value,side] of [['24600','PE'],['24550','CE'],['24700','PE']]) {
        await page.getByRole('button',{name:'Select NIFTY '+value+' '+side,exact:true}).click();
        assert.deepEqual(await page.locator('.option-picker-strike').allTextContents(),mobileRows,'mobile selection keeps rows stationary');
        assert.equal(await page.locator('.option-picker .is-selected').getAttribute('aria-label'),'Select NIFTY '+value+' '+side);
        assert.equal(await page.locator('.option-picker [aria-pressed=true]').count(),1);
      }
      const bounds=await page.locator('.option-picker').boundingBox();
      assert.ok(bounds.x>=0 && bounds.x+bounds.width<=390,'picker fits mobile viewport');
      await page.locator('.option-picker').screenshot({path:path.join(require('node:os').tmpdir(),adminMode?'option-picker-admin.png':'option-picker-portal.png')});
      await ex.selectOption('NC');
      assert.equal(await page.locator('.option-picker').isVisible(),false);
      assert.equal(orderWrites,0,'contract selection does not submit orders');
      assert.deepEqual(sample.errors,[]);
      await page.close();
    }
    await checkOptionPicker(false);
    await checkOptionPicker(true);
    console.log('PASS: stationary five-strike Call/Put rows, exact latest highlight, clicks spanning expiry replies, desktop/mobile repeated selection, full list/navigation, expiry preservation, stale strike/expiry guards and cash hiding; zero order writes, portal/admin');
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
    assert.deepEqual(await p.locator('#strategyRuns summary .source-tag').allTextContents(),[compactSourceLabel,unsafeSource,'Unspecified']);
    assert.equal(await p.locator('#executions .source-tag').last().textContent(),unsafeSource);
    assert.equal(await p.locator('#orderRequests .source-tag').first().textContent(),unsafeSource);
    assert.equal(await p.locator('#strategyRuns img, #executions img').count(),0);
    assert.equal(await p.locator('#orderRequests tr').filter({hasText:'WIPRO-WAIT'}).locator('.compact-trade-status').textContent(),'Waiting for entry');
    assert.equal(await p.locator('#orderRequests tr').filter({hasText:'WIPRO-WAIT'}).locator('.status-bad').count(),0);
    assert.equal(await p.locator('#orderRequests tr').filter({hasText:'WIPRO-REJECTED'}).locator('.status-bad').textContent(),'Rejected');
    await p.locator('#strategyRuns details').first().locator('summary').click();
    assert.equal(await p.locator('#strategyRuns details').first().locator('td').count(),18);
    await p.locator('#strategyRuns details').first().getByRole('button',{name:'Close this instance',exact:true}).click();
    await p.waitForFunction(()=>document.querySelector('#strategyRuns summary').textContent.includes('CLOSING'));
    assert.deepEqual(closes,['/api/strategies/runs/run-am/close']);
    assert.ok((await p.locator('#strategyRuns details').nth(1).locator('summary').textContent()).startsWith('EXPIRY_LONG_STRADDLE · 2026-10-19 PM · OPEN · Unavailable'));
    assert.deepEqual(errors,[]);
    const admin=await fixture([account(7)],true);const a=admin.page;
    await a.locator('#userTabs button').first().click();
    assert.equal(await a.locator('#user-requests-table tr').filter({hasText:'WIPRO-WAIT'}).locator('.compact-trade-status').textContent(),'Waiting for entry');
    assert.equal(await a.locator('#user-requests-table tr').filter({hasText:'WIPRO-REJECTED'}).locator('.status-bad').textContent(),'Rejected');
    await a.locator('#strategyTemplate').selectOption('EXPIRY_LONG_STRADDLE');
    await a.locator('#adminRunRisk [data-risk-field=profitTarget]').fill('2000');await a.locator('#strategyLots').fill('3');
    await a.locator('#strategyBroker').selectOption('7');await a.locator('#applyStrategyBtn').click();
    await a.waitForFunction(()=>document.querySelector('#strategyResult').textContent.includes('STARTED'));
    assert.equal(admin.starts[0].riskSettings.profitTarget,2000);assert.equal(admin.starts[0].riskSettings.amountMode,'PER_LOT');assert.equal(admin.starts[0].lots,3);
    assert.deepEqual(await a.locator('#adminStrategyRuns summary .source-tag').allTextContents(),[compactSourceLabel,unsafeSource,'Unspecified']);
    assert.equal(await a.locator('#user-executed-table .source-tag').last().textContent(),unsafeSource);
    assert.equal(await a.locator('#user-executed-table img').count(),0);
    assert.equal(await a.locator('#user-executed-table .source-tag').first().textContent(),'F&O 09:25');
    assert.equal(await a.locator('#user-executed-table .source-tag').first().getAttribute('title'),'Source: strategy:FNO_0925_MOVER_ATR_BREAKOUT');
    assert.equal(await a.locator('#user-executed-table .compact-trade-status').first().textContent(),'Exited');
    assert.equal(await a.locator('#user-executed-table .compact-trade-status').first().getAttribute('title'),'Status: EXITED_SUCCESS');
    const layout=await a.locator('#user-executed-table tbody tr').first().evaluate(row=>({height:row.getBoundingClientRect().height,auditWidth:row.querySelector('button.btn.small').getBoundingClientRect().width,sourceWhiteSpace:getComputedStyle(row.querySelector('.source-tag')).whiteSpace}));
    assert.ok(layout.height<=52,'Compact desktop row height: '+layout.height);assert.ok(layout.auditWidth<80);assert.equal(layout.sourceWhiteSpace,'nowrap');
    console.log('Compact desktop layout:',layout);
    const source=a.locator('#user-executed-table .source-tag').first();await source.focus();
    assert.equal(await source.textContent(),'strategy:FNO_0925_MOVER_ATR_BREAKOUT');await source.evaluate(el=>el.blur());
    assert.equal(await source.textContent(),'F&O 09:25');
    const status=a.locator('#user-executed-table .compact-trade-status').first();await status.focus();
    assert.equal(await status.textContent(),'EXITED_SUCCESS');await status.evaluate(el=>el.blur());
    await a.setViewportSize({width:1800,height:1000});
    const previewStyle=await a.addStyleTag({content:'#user-executed-table tbody tr:last-child{display:none}'});
    await a.locator('#user-executed-table').screenshot({path:path.join(require('node:os').tmpdir(),'compact-trades-desktop.png')});
    await previewStyle.evaluate(el=>el.remove());
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
    console.log('PASS: compact rows/source labels/status/date/actions and source tags (deduplicated/multiple/missing/literal HTML) on grouped and individual portal/admin trades, shared per-lot risk payloads, complete grouped legs, single-click AM-only close, CLOSING acknowledgement, PM retained, missing P&L unavailable, portal/admin/mobile, no page errors; all API calls mocked');
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
