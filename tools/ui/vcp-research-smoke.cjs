// Local saved-data and labelled synthetic UI fixtures only; no broker/network writes.
const {chromium}=require('playwright'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../..');
(async()=>{const browser=await chromium.launch({headless:true,executablePath:process.env.BROWSER_EXECUTABLE_PATH});try{
 const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[],writes=[];page.on('pageerror',e=>errors.push(e.message));
 let report=JSON.parse(fs.readFileSync(process.env.VCP_REPORT,'utf8')).report,fail=false;
 const momentum=JSON.parse(fs.readFileSync(process.env.MOMENTUM_REPORT,'utf8'));
 await page.route('**/*',async route=>{const req=route.request(),url=new URL(req.url()),p=url.pathname,json=(v,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(v)});
  if(url.hostname!=='ui.test')return route.abort();if(req.method()!=='GET'){writes.push(p);return json({},500);}
  if(p==='/stock-analysis')return route.fulfill({contentType:'text/html',body:fs.readFileSync(path.join(root,'src/main/resources/templates/stock-analysis.html'),'utf8').replace(/\s+th:[\w-]+="[^"]*"/g,'')});
  if(p.startsWith('/portal/')){const f=path.join(root,'src/main/resources/static',p);if(!fs.existsSync(f))return route.fulfill({status:404});return route.fulfill({contentType:p.endsWith('.js')?'text/javascript':p.endsWith('.json')?'application/json':'text/css',body:fs.readFileSync(f)});}
  if(p==='/api/stock-analysis/vcp')return json(fail?{}:report,fail?503:200);
  if(p==='/api/stock-analysis/momentum')return json(momentum);
  if(p==='/api/stock-analysis/swing')return json({report:null,observations:[],universe:[]});
  if(p==='/api/stock-analysis/swing/history'||p==='/api/stock-analysis/instruments')return json([]);return json({},404);
 });
 await page.goto('http://ui.test/stock-analysis');await page.waitForFunction(()=>document.getElementById('vcpStatus').textContent.includes('MARKET CAUTION'));
 assert.match(await page.locator('#vcpStatus').textContent(),/coverage 193\/200/);assert.match(await page.locator('#skillsStatus').textContent(),/8 of 72/);assert.match(await page.locator('#vcpBenchmark').textContent(),/NIFTY 50/);assert.equal(await page.locator('#vcpRows tbody tr').count(),34);
 await page.locator('#vcpRows details').first().locator('summary').click();assert.match(await page.locator('#vcpRows details').first().textContent(),/Contraction 1:/);assert.match(await page.locator('#vcpRows details').first().textContent(),/PASS:/);
 await page.locator('#vcpTitle').evaluate(n=>n.scrollIntoView({block:'start'}));await page.locator('#vcpRows').evaluate(n=>n.scrollLeft=0);if(process.env.VCP_OUTPUT)await page.screenshot({path:path.join(process.env.VCP_OUTPUT,'vcp-real-desktop.png')});
 await page.locator('#vcpAll').check();assert.equal(await page.locator('#vcpRows tbody tr').count(),200);await page.locator('#vcpAll').uncheck();
 const row={...report.stocks.find(r=>r.validPattern),symbol:'DEMO ONLY',status:'CONDITIONAL_BUY',reason:'Synthetic fixture',plan:{entry:100,maxEntry:101,stop:95,target1:113,target2:119}};
 report={...report,status:'READY',entryCandidatesBeforeMarketGate:1,stocks:[row]};await page.locator('#vcpRefresh').click();await page.waitForFunction(()=>document.getElementById('vcpStatus').textContent.startsWith('READY'));assert.match(await page.locator('#vcpPlans').textContent(),/₹113.00/);
 report={...report,validSession:'2026-01-02'};await page.locator('#vcpRefresh').click();await page.waitForFunction(()=>document.getElementById('vcpPlans').textContent.includes('EXPIRED'));
 report={...report,status:'BENCHMARK_INCOMPLETE',stocks:[{...row,symbol:'<img src=x onerror=alert(1)>',plan:null}]};await page.locator('#vcpRefresh').click();await page.waitForFunction(()=>document.getElementById('vcpStatus').textContent.startsWith('BENCHMARK INCOMPLETE'));assert.match(await page.locator('#vcpPlans').textContent(),/BENCHMARK INCOMPLETE/);assert.equal(await page.locator('#vcpRows img').count(),0);
 fail=true;await page.locator('#vcpRefresh').click();await page.waitForFunction(()=>document.getElementById('vcpStatus').textContent.includes('not refreshed'));
 await page.setViewportSize({width:390,height:844});await page.locator('#vcpTitle').scrollIntoViewIfNeeded();assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);if(process.env.VCP_OUTPUT)await page.screenshot({path:path.join(process.env.VCP_OUTPUT,'vcp-mobile.png')});assert.deepEqual(errors,[]);assert.deepEqual(writes,[]);
 console.log('PASS VCP real data, benchmark, 34 watches, 200-stock audit, evidence, synthetic prices, expiry, missing benchmark, error, XSS, mobile and zero writes');
 }finally{await browser.close();}})().catch(e=>{console.error(e);process.exit(1);});
