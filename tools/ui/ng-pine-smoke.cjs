// Mock APIs only: no application server, credentials, broker or real activation.
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const root=path.resolve(__dirname,'../..','src/main/resources');
const id='MCX_NATURALGAS_PINE_TREND';
(async()=>{
  const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE_PATH?{executablePath:process.env.BROWSER_EXECUTABLE_PATH}:{})});
  try {
    const page=await browser.newPage({viewport:{width:1280,height:1000}}),errors=[],starts=[];
    page.on('pageerror',e=>errors.push(e.message));
    await page.route('**/*',route=>{
      const url=new URL(route.request().url()),p=url.pathname;
      if(url.hostname!=='ui.test')return route.abort();
      const json=value=>route.fulfill({contentType:'application/json',body:JSON.stringify(value)});
      if(p==='/portal')return route.fulfill({contentType:'text/html',body:fs.readFileSync(path.join(root,'templates/portal.html'),'utf8')});
      if(/\.(js|css)$/.test(p))return route.fulfill({contentType:p.endsWith('.js')?'text/javascript':'text/css',body:fs.readFileSync(path.join(root,'static',p),'utf8')});
      if(p==='/api/user/me')return json({id:1,username:'Mock user',displayName:'Mock user',admin:false});
      if(p==='/api/auth/csrf-token')return json({header:'X-CSRF-TOKEN',token:'mock',parameter:'_csrf'});
      if(p==='/api/user/brokers')return json([{id:7,brokerName:'Sharekhan',connected:true,active:true,tradingEnabled:true}]);
      if(p==='/api/user/onboarding')return json({connectedBrokers:1,activeStrategies:0,steps:[]});
      if(p==='/api/strategies/templates')return json([{id,name:'NG Pine',description:'Futures signals and futures SL/target; buy CE/PE.'},{id:'MCX_NATURALGAS_SCALPING',name:'Scalping',description:'Original premium strategy'}]);
      if(p==='/api/strategies/start'){starts.push(route.request().postDataJSON());return json({id:1,status:'ACTIVE'});}
      if(p==='/api/orders/requests'||p==='/api/orders/executed'||p==='/api/strategies/runs')return json({content:[],first:true,last:true,totalElements:0});
      return json([]);
    });
    await page.goto('http://ui.test/portal#strategies');
    await page.getByRole('button',{name:'Configure subscription →'}).first().click();
    await page.waitForFunction(()=>document.querySelector('#strategyBroker').value==='7');
    assert.equal(await page.locator('#subscriptionForm [name=symbol]').getAttribute('readonly'),null);
    assert.equal(await page.locator('#subscriptionRunRisk [data-risk-field=profitTarget]').isVisible(),false);
    assert.equal(await page.locator('#subscriptionRunRisk [data-risk-field=maxLoss]').isVisible(),true);
    assert.match(await page.locator('#subscriptionRunRisk').textContent(),/futures/i);
    await page.locator('#subscriptionForm [name=symbol]').fill('NATGASMINI');
    await page.locator('#subscriptionForm [name=lots]').fill('1');
    await page.locator('#subscriptionRunRisk [data-risk-field=maxLoss]').fill('3000');
    await page.locator('#subscriptionForm [name=consent]').check();
    if(process.env.SCREENSHOT_PATH)await page.screenshot({path:process.env.SCREENSHOT_PATH,fullPage:true});
    await page.getByRole('button',{name:'Activate subscription',exact:true}).click();
    await page.waitForFunction(()=>!document.querySelector('#strategyDialog').open);
    assert.equal(starts.length,1);assert.equal(starts[0].templateId,id);assert.equal(starts[0].symbol,'NATGASMINI');
    assert.equal(starts[0].intraday,true);assert.equal(starts[0].riskSettings.maxLoss,3000);assert.equal(starts[0].riskSettings.profitTarget,null);
    await page.getByRole('button',{name:'Configure subscription →'}).nth(1).click();
    assert.equal(await page.locator('#subscriptionRunRisk [data-risk-field=profitTarget]').isVisible(),true);
    assert.equal(await page.locator('#subscriptionForm [name=symbol]').getAttribute('readonly'),'');
    assert.equal(await page.locator('#subscriptionForm [name=symbol]').inputValue(),'NATURALGAS');
    assert.deepEqual(errors,[]);
    console.log('PASS: Pine risk fields, futures explanation, mini symbol payload, intraday lock, broker selection, and legacy field restoration; all API calls mocked.');
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
