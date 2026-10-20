// Mock-only end-to-end portal checks. No server, broker or credentials are used.
// NODE_PATH=<Playwright modules> BROWSER_EXECUTABLE_PATH=<Chrome> node tools/ui/personal-trade-actions-smoke.cjs
const {chromium} = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
(async () => {
  const browser = await chromium.launch({headless:true, ...(process.env.BROWSER_EXECUTABLE_PATH ? {executablePath:process.env.BROWSER_EXECUTABLE_PATH} : {})});
  try {
    const page = await browser.newPage({viewport:{width:1360,height:960}});
    const errors = [], writes = [], auditQueries = [];
    page.on('pageerror', e => errors.push(e.message));
    const base = {entryPrice:100,actualEntryPrice:101,stopLoss:90,target1:120,target2:130,target3:0,quantity:65,intraday:false,useSpotPrice:true,useSpotForSl:null,useSpotForTarget:false,tslEnabled:false};
    const rows = [
      {...base,id:1,symbol:'OPEN',status:'EXECUTED',triggerRequestId:201},
      {...base,id:2,symbol:'TARGET',status:'TARGET_ORDER_PLACED',exitOrderId:'exit-2'},
      {...base,id:3,symbol:'CLOSING',status:'EXIT_ORDER_PLACED',exitOrderId:'exit-3'},
      {...base,id:4,symbol:'CLOSED',status:'EXITED_SUCCESS'},
      {...base,id:5,symbol:'DAY',status:'EXECUTED',intraday:true,useSpotPrice:false},
      {...base,id:6,symbol:'REJECTED',status:'REJECTED'},
      {...base,id:7,symbol:'NO-ORDER',status:'TARGET_ORDER_PLACED',exitOrderId:''},
      {...base,id:8,symbol:'UNKNOWN',status:'FUTURE_STATUS'}
    ];
    let failure = null, delay = false, csrfReject = false;
    await page.route('**/*', async route => {
      const req=route.request(),url=new URL(req.url()),p=url.pathname;
      const json=(body,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)});
      if(url.hostname!=='ui.test')return route.abort();
      if(p==='/dashboard')return route.fulfill({contentType:'text/html',body:fs.readFileSync(path.join(root,'src/main/resources/templates/portal.html'),'utf8')});
      if(p.startsWith('/portal/')||p==='/strategy-runs.js'||p==='/strategy-runs.css')return route.fulfill({contentType:p.endsWith('.js')?'text/javascript':'text/css',body:fs.readFileSync(path.join(root,'src/main/resources/static',p),'utf8')});
      if(p==='/api/auth/csrf-token')return csrfReject?json({message:'CSRF unavailable'},503):json({header:'X-CSRF-TOKEN',token:'mock-only'});
      if(req.method()!=='GET') {
        assert.equal(req.headers()['x-csrf-token'],'mock-only');
        writes.push({path:p,query:url.search,method:req.method(),body:req.postDataJSON()});
        if(delay)await new Promise(resolve=>setTimeout(resolve,200));
        if(failure)return json({message:failure},409);
        if(req.method()==='PUT')Object.assign(rows.find(r=>p.endsWith('/'+r.id)),req.postDataJSON());
        return json('Mock action accepted');
      }
      if(p==='/api/user/me')return json({id:4,username:'Sample user',displayName:'Sample user'});
      if(p==='/api/user/onboarding')return json({connectedBrokers:0,activeStrategies:0,orderRequests:0,steps:[]});
      if(p==='/api/orders/executed')return json({content:rows,first:true,last:true,totalElements:rows.length});
      if(p==='/api/orders/requests'||p==='/api/strategies/runs')return json({content:[],first:true,last:true,totalElements:0});
      if(p==='/api/trades/audit') {
        auditQueries.push(url.search);
        return json([{eventType:'STOP_UPDATED',outcome:'OK',occurredAt:'2026-10-21T09:30:00',reason:'<img src=x onerror=alert(1)>',details:'<script>danger</script>',symbol:'MOCK',spotPrice:0,optionLtp:100}]);
      }
      return json([]);
    });
    await page.goto('http://ui.test/dashboard#orders');
    const row = symbol => page.locator('#executions tbody tr').filter({has:page.locator('td:first-child').filter({hasText:new RegExp('^'+symbol+'$')})});
    const dialog = () => page.locator('.trade-action-dialog[open]');
    const cancel = async () => dialog().getByRole('button',{name:'Cancel',exact:true}).click();
    await page.waitForSelector('#executions tbody tr');
    assert.deepEqual(await row('OPEN').getByRole('button').allTextContents(),['Edit SL / targets','Move SL to Cost','Close position','Reset to Executed','Audit']);
    assert.deepEqual(await row('CLOSING').getByRole('button').allTextContents(),['Modify Exit','Reset to Executed','Audit']);
    for(const name of ['CLOSED','REJECTED','UNKNOWN'])assert.deepEqual(await row(name).getByRole('button').allTextContents(),['Audit']);
    assert.equal(await row('DAY').getByRole('button',{name:'Reset to Executed'}).count(),0);
    assert.equal(await row('NO-ORDER').getByRole('button',{name:'Modify Exit'}).count(),0);
    await row('OPEN').getByRole('button',{name:'Edit SL / targets'}).click();
    await dialog().getByRole('button',{name:'Save changes'}).click();
    await page.getByRole('alert').filter({hasText:'No changes'}).waitFor(); assert.equal(writes.length,0);
    await dialog().getByLabel('Target 1 ₹',{exact:true}).fill('125');
    await dialog().getByRole('button',{name:'Save changes'}).click();
    await dialog().waitFor({state:'detached'});
    assert.deepEqual(writes.at(-1),{path:'/api/trades/execution/1',query:'',method:'PUT',body:{target1:125}});
    // Failure retains edited values. A retry sends one request; cancellation sends none.
    failure='Mock broker conflict';
    await row('OPEN').getByRole('button',{name:'Edit SL / targets'}).click();
    await dialog().getByLabel('Stop-loss ₹',{exact:true}).fill('95');
    await dialog().getByLabel('Enable trailing SL (partial booking)').check();
    await dialog().getByLabel('Stop-loss reference').selectOption('false');
    await dialog().getByRole('button',{name:'Save changes'}).click();
    await dialog().getByRole('alert').filter({hasText:failure}).waitFor();
    assert.equal(await dialog().getByLabel('Stop-loss ₹',{exact:true}).inputValue(),'95');
    assert.deepEqual(writes.at(-1).body,{stopLoss:95,tslEnabled:true,useSpotForSl:false});
    failure=null; await cancel();
    const before=writes.length;
    await row('OPEN').getByRole('button',{name:'Move SL to Cost'}).click();await cancel();assert.equal(writes.length,before);
    await row('OPEN').getByRole('button',{name:'Move SL to Cost'}).click();
    await dialog().getByLabel('Cost basis').selectOption('PREMIUM');
    await dialog().getByRole('button',{name:'Confirm SL change'}).click();await dialog().waitFor({state:'detached'});
    assert.equal(writes.at(-1).path,'/api/trades/move-sl-to-cost/1');assert.equal(writes.at(-1).query,'?reference=PREMIUM');
    await row('OPEN').getByRole('button',{name:'Move SL to Cost'}).click();
    assert.equal(await dialog().getByLabel('Cost basis').inputValue(),'SPOT');
    await dialog().getByRole('button',{name:'Confirm SL change'}).click();await dialog().waitFor({state:'detached'});
    assert.equal(writes.at(-1).query,'?reference=SPOT');
    await row('DAY').getByRole('button',{name:'Move SL to Cost'}).click();assert.equal(await dialog().locator('select option').count(),1);await cancel();
    await row('TARGET').getByRole('button',{name:'Modify Exit'}).click();
    const count=writes.length;await dialog().getByRole('button',{name:'Submit exit price'}).click();assert.equal(writes.length,count);
    await dialog().getByLabel('New exit price ₹').fill('110.25');delay=true;
    await dialog().getByRole('button',{name:'Submit exit price'}).click();
    await page.keyboard.press('Escape');assert.equal(await dialog().count(),1);
    await dialog().waitFor({state:'detached'});delay=false;
    assert.deepEqual(writes.at(-1).body,{price:110.25});assert.equal(writes.at(-1).path,'/api/trades/exit-order/2/modify');
    failure='Broker exit is still active';
    await row('CLOSING').getByRole('button',{name:'Reset to Executed'}).click();
    await dialog().getByRole('button',{name:'Check and reset'}).click();
    await dialog().getByRole('alert').filter({hasText:failure}).waitFor();await cancel();failure=null;
    assert.equal(writes.at(-1).path,'/api/trades/execution/3/reset-to-executed');
    await row('OPEN').getByRole('button',{name:'Close position',exact:true}).click();
    await dialog().getByLabel('Exit purpose').selectOption('TARGET_ORDER_PLACED');
    const closeCount=writes.length;await dialog().getByRole('button',{name:'Submit square-off'}).click();
    await dialog().getByRole('alert').filter({hasText:'Enter a price'}).waitFor();assert.equal(writes.length,closeCount);
    await dialog().getByLabel('Optional exit price ₹').fill('145');
    await dialog().getByRole('button',{name:'Submit square-off'}).click();await dialog().waitFor({state:'detached'});
    assert.equal(writes.at(-1).query,'?exitOrderStatus=TARGET_ORDER_PLACED&price=145');
    await row('DAY').getByRole('button',{name:'Close position',exact:true}).click();
    await dialog().getByRole('button',{name:'Submit square-off'}).click();await dialog().waitFor({state:'detached'});
    assert.equal(writes.at(-1).query,'?exitOrderStatus=EXIT_ORDER_PLACED');
    // CSRF failure never reaches a trade write.
    csrfReject=true;const csrfCount=writes.length;
    await row('OPEN').getByRole('button',{name:'Move SL to Cost'}).click();await dialog().getByRole('button',{name:'Confirm SL change'}).click();
    await dialog().getByRole('alert').filter({hasText:'CSRF unavailable'}).waitFor();assert.equal(writes.length,csrfCount);await cancel();csrfReject=false;
    await row('OPEN').getByRole('button',{name:'Audit',exact:true}).click();
    await dialog().getByRole('heading',{name:'STOP_UPDATED · OK'}).waitFor();
    assert.equal(auditQueries.at(-1),'?triggerRequestId=201');assert.equal(await dialog().locator('img,script').count(),0);
    assert.match(await dialog().textContent(),/<img src=x onerror=alert\(1\)>/);await cancel();
    await row('CLOSED').getByRole('button',{name:'Audit',exact:true}).click();await dialog().getByRole('heading',{name:'STOP_UPDATED · OK'}).waitFor();assert.equal(auditQueries.at(-1),'?tradeId=4');await cancel();
    await page.setViewportSize({width:390,height:844});
    await row('OPEN').getByRole('button',{name:'Edit SL / targets'}).click();
    await dialog().getByLabel('Target 2 ₹',{exact:true}).focus();
    assert(await dialog().evaluate(el=>{const r=el.getBoundingClientRect();return r.left>=0 && r.right<=innerWidth && r.height<=innerHeight;}));
    const screenshot=process.env.SCREENSHOT_PATH;
    if(screenshot)await page.screenshot({path:screenshot});
    await dialog().getByRole('button',{name:'Save changes'}).scrollIntoViewIfNeeded();
    assert(await dialog().getByRole('button',{name:'Save changes'}).isVisible());
    await page.keyboard.press('Escape');assert.equal(await dialog().count(),0);
    assert.deepEqual(errors,[]);
    console.log('PASS: personal portal actions, status visibility, changed-only edits, confirmations/cancellation, CSRF/errors, broker conflict, pending dialog, audit text safety, mobile layout. Mock writes: '+writes.length);
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
