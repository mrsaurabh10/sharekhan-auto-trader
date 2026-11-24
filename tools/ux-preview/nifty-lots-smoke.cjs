// Mock-only checks: no backend or broker is contacted.
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const root=path.resolve(__dirname,'../../src/main/resources');
const template={id:'NIFTY_THREE_CANDLE_ATM',name:'Nifty Three Candles',description:'₹3,000 daily loss per lot'};
(async()=>{
  const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE_PATH?{executablePath:process.env.BROWSER_EXECUTABLE_PATH}:{})});
  try {
    for(const admin of [false,true]){
      const page=await browser.newPage({viewport:{width:1440,height:1000}});
      page.setDefaultTimeout(10000);
      const starts=[],errors=[];
      let savedLots=1;
      page.on('pageerror',error=>errors.push(error.message));
      const broker={id:7,brokerName:'Simulator',clientCode:'Paper',connected:true,active:true,tradingEnabled:true};
      await page.route('**/*',route=>{
        const url=new URL(route.request().url()),endpoint=url.pathname;
        const json=body=>route.fulfill({contentType:'application/json',body:JSON.stringify(body)});
        if(url.hostname!=='ui.test')return route.abort();
        if(endpoint==='/portal'||endpoint==='/admin-dashboard.html')return route.fulfill({contentType:'text/html',body:fs.readFileSync(path.join(root,endpoint==='/portal'?'templates/portal.html':'static/admin-dashboard.html'),'utf8')});
        if(/\.(js|css)$/.test(endpoint))return route.fulfill({contentType:endpoint.endsWith('.js')?'text/javascript':'text/css',body:fs.readFileSync(path.join(root,'static',endpoint),'utf8')});
        if(endpoint==='/api/user/me')return json({id:1,username:'Test user',displayName:'Test user',admin});
        if(endpoint==='/api/auth/csrf-token')return json({header:'X-CSRF-TOKEN',token:'test',parameter:'_csrf'});
        if(endpoint==='/api/user/brokers'||endpoint==='/admin/app-users/1/brokers')return json([broker]);
        if(endpoint==='/admin/app-users')return json([{id:1,username:'Test user'}]);
        if(endpoint==='/api/user/onboarding')return json({connectedBrokers:1,activeStrategies:1,orderRequests:0,steps:[]});
        if(endpoint==='/api/strategies/templates')return json([template]);
        if(endpoint==='/api/strategies/subscriptions')return json([{id:10,appUserId:1,brokerCredentialsId:7,templateId:template.id,symbol:'NIFTY',lots:savedLots,status:'ACTIVE',source:'strategy:'+template.id}]);
        if(endpoint==='/api/strategies/start'){
          const body=route.request().postDataJSON();starts.push(body);savedLots=body.lots;
          return json({id:10,status:'ACTIVE'});
        }
        if(endpoint==='/api/orders/requests'||endpoint==='/api/orders/executed'||endpoint==='/api/strategies/runs')return json({content:[],totalElements:0,number:0,totalPages:1,first:true,last:true});
        if(endpoint==='/api/analytics/trades')return json({summary:{},bySymbol:[],byDay:[],recentClosedTrades:[]});
        return json([]);
      });
      await page.goto('http://ui.test/'+(admin?'admin-dashboard.html':'portal#strategies'));
      console.log(admin?'Checking admin':'Checking portal');
      if(!admin)await page.getByRole('button',{name:'Configure subscription →'}).click();
      const lots=page.locator(admin?'#strategyLots':'#subscriptionForm [name=lots]');
      await page.waitForFunction(()=>document.querySelector('#strategyBroker').value==='7');
      assert.equal(await lots.evaluate(el=>el.readOnly),false);
      assert.equal(await page.locator(admin?'#strategyTemplate option:checked':'#strategyDialogTitle').textContent(),template.name);
      const risk=page.locator(admin?'#adminRunRisk':'#subscriptionRunRisk');
      assert.equal(await risk.locator('[data-risk-field=maxLoss]').inputValue(),'3000');
      assert.equal(await risk.locator('[data-risk-field=amountMode]').inputValue(),'PER_LOT');
      assert.ok((await risk.locator('small').textContent()).includes('₹3,000 × selected lots'));
      console.log('Checked form');
      await lots.fill('2');
      if(!admin)await page.locator('#subscriptionForm [name=consent]').check();
      await page.locator(admin?'#applyStrategyBtn':'#subscriptionForm button[type=submit]').click();
      if(admin)await page.waitForFunction(()=>document.querySelector('#strategyResult').textContent.includes('STARTED'));
      else await page.waitForFunction(()=>!document.querySelector('#strategyDialog').open);
      assert.equal(starts[0].lots,2);
      assert.equal(starts[0].brokerCredentialsId,7);
      assert.deepEqual({amountMode:starts[0].riskSettings.amountMode,maxLoss:starts[0].riskSettings.maxLoss},{amountMode:'PER_LOT',maxLoss:3000});
      console.log('Checked start');
      page.once('dialog',dialog=>dialog.accept('3'));
      await page.getByRole('button',{name:'Change lots',exact:true}).click();
      await page.waitForFunction(()=>document.querySelector('#strategy-subscriptions-table tbody')?.textContent.includes('3')||document.querySelector('#message')?.textContent.includes('Lots saved'));
      assert.equal(starts[1].lots,3);
      assert.equal(starts[1].userId,1);
      assert.equal(starts[1].riskSettings.maxLoss,3000);
      // Validate invalid sizes directly through the same shared action to avoid modal timing differences.
      const invalid=await page.evaluate(async()=>{
        const original=window.prompt;window.prompt=()=> '0';
        try{await window.StrategyRunView.resizeNifty({lots:1},()=>{throw new Error('unexpected API call');});}
        catch(error){return error.message;}finally{window.prompt=original;}
      });
      assert.equal(invalid,'Choose between 1 and 1000 lots.');
      assert.deepEqual(errors,[]);
      await page.close();
    }
    console.log('PASS: portal/admin editable lots, per-lot risk display, activation and Change lots payloads, invalid sizing, display title, and no page errors.');
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
