// Mock-only browser regression. Run with Node + Playwright; optional BROWSER_EXECUTABLE_PATH.
// No backend, broker, credentials or live trading is contacted.
const {chromium} = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
const account = id => ({id,brokerName:'Dhan',clientCode:'Sample '+id,connected:true,active:true,tradingEnabled:true});
const records = ['ACTIVE','TRIGGERED','CANCELLED'].map((status,i)=>({id:i+1,templateId:'Stock mover',symbol:status,status,brokerCredentialsId:7}));
const templates = [{id:'STOCK_0925_MOVER_ATR_BREAKOUT',name:'Stock mover',description:'Mock strategy'}];
(async()=>{
  const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE_PATH ? {executablePath:process.env.BROWSER_EXECUTABLE_PATH}: {})});
  try {
    async function fixture(brokers,admin=false) {
      const page=await browser.newPage({viewport:{width:1360,height:960}});
      const errors=[];page.on('pageerror',e=>errors.push(e.message));
      const starts=[];let rejectStart=false;
      await page.route('**/*',route=>{
        const url=new URL(route.request().url()), endpoint=url.pathname;
        if(url.hostname!=='ui.test')return route.abort();
        const json=(body,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)});
        if(endpoint==='/portal'||endpoint==='/admin-dashboard.html')return route.fulfill({contentType:'text/html',body:fs.readFileSync(path.join(root,'src/main/resources',endpoint==='/portal'?'templates/portal.html':'static/admin-dashboard.html'),'utf8')});
        if(endpoint.startsWith('/portal/')||endpoint==='/admin-dashboard.js')return route.fulfill({contentType:endpoint.endsWith('.js')?'text/javascript':'text/css',body:fs.readFileSync(path.join(root,'src/main/resources/static',endpoint),'utf8')});
        if(endpoint==='/api/user/me')return json({id:1,username:'Sample user',displayName:'Sample user',admin});
        if(endpoint==='/api/auth/csrf-token')return json({header:'X-CSRF-TOKEN',token:'mock-only'});
        if(endpoint==='/api/user/brokers')return json(brokers);
        if(/^\/admin\/app-users\/\d+\/brokers$/.test(endpoint))return json(endpoint.includes('/2/')?[account(8),account(9)]:brokers);
        if(endpoint==='/admin/app-users')return json([{id:1,username:'User one'},{id:2,username:'User two'}]);
        if(endpoint==='/api/user/onboarding')return json({connectedBrokers:brokers.filter(b=>b.connected).length,activeStrategies:2,orderRequests:0,steps:[]});
        if(endpoint==='/api/strategies/templates')return json(templates);
        if(endpoint==='/api/strategies/subscriptions') {
          const filter=url.searchParams.get('status')||'ACTIVE';
          return json(records.filter(r=>filter==='ALL'||(filter==='ACTIVE'?['ACTIVE','TRIGGERED'].includes(r.status):r.status===filter)));
        }
        if(endpoint==='/api/strategies/start') {
          starts.push(route.request().postDataJSON());return rejectStart?json({message:'Mock broker unavailable'},503):json({id:4,status:'ACTIVE'});
        }
        if(endpoint==='/api/orders/requests'||endpoint==='/api/orders/executed')return json({content:[],totalElements:0,number:0,totalPages:1,first:true,last:true});
        return json([]);
      });
      await page.goto('http://ui.test/'+(admin?'admin-dashboard.html':'portal#strategies'));
      return {page,errors,starts,reject:()=>rejectStart=true};
    }
    const single=await fixture([account(7)]);const p=single.page;
    await p.getByRole('button',{name:'Configure subscription →'}).click();
    await p.waitForFunction(()=>document.querySelector('#strategyBroker').value==='7');
    assert.equal(await p.locator('#strategyDialog').evaluate(el=>el.open),true);
    await p.locator('[name=investmentPerStock]').fill('10000');await p.locator('[name=consent]').check();
    single.reject();await p.getByRole('button',{name:'Activate subscription',exact:true}).click();
    await p.getByRole('alert').filter({hasText:'Mock broker unavailable'}).waitFor();
    assert.equal(single.starts[0].brokerCredentialsId,7);assert.equal(single.starts[0].investmentPerStock,10000);
    assert.equal(await p.locator('#strategyDialog').evaluate(el=>el.open),true);
    await p.screenshot({path:'/private/tmp/strategy-dialog-verified.png'});
    await p.locator('#strategyDialog [data-close]').click();
    assert.equal(await p.locator('#subscriptions tbody tr').count(),2);
    await p.locator('#subscriptionStatusFilter').selectOption('CANCELLED');
    await p.waitForFunction(()=>document.querySelector('#subscriptions').textContent.includes('CANCELLED'));
    assert.equal(await p.locator('#subscriptions tbody tr').count(),1);
    await p.locator('#subscriptionStatusFilter').selectOption('ALL');
    await p.waitForFunction(()=>document.querySelectorAll('#subscriptions tbody tr').length===3);
    assert.deepEqual(single.errors,[]);await p.close();

    const multiple=await fixture([account(7),account(8)]);
    await multiple.page.getByRole('button',{name:'Configure subscription →'}).click();
    await multiple.page.waitForFunction(()=>document.querySelector('#strategyBroker').options.length===3);
    assert.equal(await multiple.page.locator('#strategyBroker').inputValue(),'');
    await multiple.page.locator('#strategyBroker').selectOption('8');
    await multiple.page.locator('[name=investmentPerStock]').fill('12000');await multiple.page.locator('[name=consent]').check();
    await multiple.page.getByRole('button',{name:'Activate subscription',exact:true}).click();
    await multiple.page.waitForFunction(()=>!document.querySelector('#strategyDialog').open);
    assert.equal(multiple.starts[0].brokerCredentialsId,8);assert.deepEqual(multiple.errors,[]);await multiple.page.close();

    const unready=await fixture([{...account(7),connected:false,tradingEnabled:false}]);
    await unready.page.getByRole('button',{name:'Configure subscription →'}).click();
    assert.equal(await unready.page.locator('#strategyDialog').evaluate(el=>el.open),true);
    assert.equal(await unready.page.locator('#strategyDialogGate').isVisible(),true);
    assert.equal(await unready.page.getByRole('button',{name:'Activate subscription',exact:true}).isDisabled(),true);
    await unready.page.locator('#strategyDialogGate button').click();
    assert.equal(await unready.page.locator('#strategyDialog').evaluate(el=>el.open),false);
    assert.equal(await unready.page.locator('#view-brokers').isVisible(),true);
    assert.equal(unready.starts.length,0);assert.deepEqual(unready.errors,[]);await unready.page.close();

    const admin=await fixture([account(7)],true);const a=admin.page;
    await a.waitForFunction(()=>document.querySelector('#strategyBroker').value==='7');
    assert.equal(await a.locator('#strategyStatusFilter').inputValue(),'ACTIVE');
    await a.getByText('User two',{exact:true}).click();
    await a.waitForFunction(()=>document.querySelector('#strategyBroker').options.length===3);
    assert.equal(await a.locator('#strategyBroker').inputValue(),'');
    await a.locator('#strategyBroker').selectOption('9');
    await a.locator('#strategyInvestmentPerStock').fill('15000');
    await a.locator('#applyStrategyBtn').click();
    await a.waitForFunction(()=>document.querySelector('#strategyResult').textContent.includes('STARTED'));
    assert.equal(admin.starts[0].brokerCredentialsId,9);assert.equal(admin.starts[0].userId,2);
    await a.locator('#strategyStatusFilter').selectOption('CANCELLED');
    await a.waitForFunction(()=>document.querySelector('#strategy-subscriptions-table tbody').textContent.includes('CANCELLED'));
    assert.equal(await a.locator('#strategy-subscriptions-table tbody tr').count(),1);
    await a.locator('#strategyStatusFilter').selectOption('ACTIVE');
    await a.waitForFunction(()=>document.querySelectorAll('#strategy-subscriptions-table tbody tr').length===2);
    await a.setViewportSize({width:1440,height:960});
    const layout=await a.evaluate(()=>{
      const side=document.querySelector('.users-list').getBoundingClientRect(),content=document.querySelector('.content').getBoundingClientRect();
      return {gap:content.left-side.right,width:content.width,header:document.querySelector('.admin-header').getBoundingClientRect().height};
    });
    assert.ok(layout.gap>=12 && layout.gap<=20,'Sidebar should have one compact gutter');
    assert.ok(layout.width>1100,'Workspace should use available desktop width');
    assert.ok(layout.header<90,'Header should stay compact');
    assert.equal(await a.locator('#newUserPassword').isVisible(),false);
    await a.locator('.user-create summary').click();assert.equal(await a.locator('#newUserPassword').isVisible(),true);
    await a.locator('.user-create summary').click();
    assert.equal(await a.locator('#refreshSharekhanMasterBtn').isVisible(),false);
    await a.locator('#scriptMasterPanel summary').click();assert.equal(await a.locator('#refreshSharekhanMasterBtn').isVisible(),true);
    await a.locator('#scriptMasterPanel summary').click();
    assert.equal(await a.locator('#exchange').isVisible(),false);
    await a.locator('#manualOrderComposer summary').click();assert.equal(await a.locator('#exchange').isVisible(),true);
    await a.locator('#manualOrderComposer summary').click();
    await a.locator('.execution-filters summary').click();
    assert.equal(await a.locator('#statusFilter').isVisible(),true);
    await a.locator('.execution-filters summary').click();
    await a.evaluate(()=>window.scrollTo(0,0));
    await a.screenshot({path:'/private/tmp/admin-layout-desktop.png',fullPage:true});
    for(const width of [1024,760,390]) {
      await a.setViewportSize({width,height:900});
      assert.ok(await a.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'No body overflow at '+width);
      assert.equal(await a.locator('#strategyBroker').isVisible(),true);
    }
    await a.screenshot({path:'/private/tmp/admin-layout-mobile.png',fullPage:true});
    await a.getByRole('button',{name:'Brokers',exact:true}).click();assert.equal(await a.locator('#brokersSection').isVisible(),true);
    await a.getByRole('button',{name:'Account',exact:true}).click();assert.equal(await a.locator('#accountSection').isVisible(),true);
    await a.getByRole('button',{name:'Trading',exact:true}).click();assert.equal(await a.locator('#strategyBroker').inputValue(),'9');
    assert.deepEqual(admin.errors,[]);await a.close();
    console.log('PASS: responsive admin layout, expandable tools, tab navigation; portal and admin broker defaults/selection, active/history filters, configure readiness, activation payloads and visible errors.');
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
