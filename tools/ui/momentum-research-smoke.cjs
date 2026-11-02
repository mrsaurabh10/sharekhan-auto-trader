// Fixture-only browser verification: no live server, broker, orders or credentials.
const {chromium}=require('playwright');const fs=require('node:fs');const path=require('node:path');const assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../..');
(async()=>{const browser=await chromium.launch({headless:true,executablePath:process.env.BROWSER_EXECUTABLE_PATH});
 try{const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[],writes=[];page.on('pageerror',e=>errors.push(e.message));
 const real=process.env.MOMENTUM_REPORT?JSON.parse(fs.readFileSync(process.env.MOMENTUM_REPORT,'utf8')):null;
 const row={symbol:'DEMO',status:'WATCH_ONLY',reason:'Synthetic fixture; market gate blocks entry.',triggers:['4_PERCENT_BREAKOUT'],dayGainPercent:4,volumeRatio:2,score:82,plan:null};
 let report=real||{dataDate:'2026-10-30',validSession:'2026-11-02',status:'MARKET_CAUTION',coveredCount:197,universeCount:200,breadthAbove50:47/197,stocks:[row],rules:['Synthetic fixture only.']},fail=false;
 await page.route('**/*',async route=>{const req=route.request(),url=new URL(req.url()),p=url.pathname;const json=(v,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(v)});
 if(url.hostname!=='ui.test')return route.abort();if(req.method()!=='GET'){writes.push(p);return json({},500);}
 if(p==='/stock-analysis'){const html=fs.readFileSync(path.join(root,'src/main/resources/templates/stock-analysis.html'),'utf8').replace(/\s+th:[\w-]+="[^"]*"/g,'');return route.fulfill({contentType:'text/html',body:html});}
 if(p.startsWith('/portal/')){const file=path.join(root,'src/main/resources/static',p);if(!fs.existsSync(file))return route.fulfill({status:404});return route.fulfill({contentType:p.endsWith('.js')?'text/javascript':p.endsWith('.json')?'application/json':'text/css',body:fs.readFileSync(file)});}
 if(p==='/api/stock-analysis/momentum')return json(fail?{}:report,fail?503:200);
 if(p==='/api/stock-analysis/swing')return json({report:null,observations:[],universe:[],historyUpdate:null});
 if(p==='/api/stock-analysis/swing/history'||p==='/api/stock-analysis/instruments')return json([]);
 return json({},404);});
 await page.goto('http://ui.test/stock-analysis');await page.waitForFunction(()=>document.getElementById('momentumStatus').textContent.includes('MARKET CAUTION'));
 assert.match(await page.locator('#momentumStatus').textContent(),/coverage 197\/200/);assert.match(await page.locator('#skillsStatus').textContent(),/10 of 72/);
 await page.locator('#momentumAll').check();assert.equal(await page.locator('#momentumRows tbody tr').count(),report.stocks.length);
 if(real&&process.env.MOMENTUM_OUTPUT){await page.locator('#momentumAll').uncheck();await page.locator('#momentumTitle').scrollIntoViewIfNeeded();await page.screenshot({path:path.join(process.env.MOMENTUM_OUTPUT,'momentum-real-panel.png')});}
 report={...report,status:'READY',stocks:[{...row,status:'CONDITIONAL_BUY',reason:'DEMO ONLY',plan:{entry:100,maxEntry:101,stop:95,target1:113,target2:119}}]};await page.locator('#momentumRefresh').click();await page.waitForFunction(()=>document.getElementById('momentumStatus').textContent.startsWith('READY'));
 assert.match(await page.locator('#momentumPlans').textContent(),/₹113.00/);assert.match(await page.locator('#momentumPlans').textContent(),/Sell T2/);
 report={...report,validSession:'2026-01-02'};await page.locator('#momentumRefresh').click();await page.waitForFunction(()=>document.getElementById('momentumPlans').textContent.includes('EXPIRED'));
 report={...report,stocks:[{...row,symbol:'<img src=x onerror=alert(1)>'}]};await page.locator('#momentumRefresh').click();await page.waitForFunction(()=>document.getElementById('momentumRows').textContent.includes('onerror'));assert.equal(await page.locator('#momentumRows img').count(),0);
 fail=true;await page.locator('#momentumRefresh').click();await page.waitForFunction(()=>document.getElementById('momentumStatus').textContent.includes('not refreshed'));
 await page.locator('summary').filter({hasText:'View all 72 skills'}).click();await page.locator('#skillFilter').fill('vcp');assert.equal(await page.locator('#skillRows tbody tr').count(),1);assert.match(await page.locator('#skillRows').textContent(),/SELECTED METHODS IN APP/);
 await page.setViewportSize({width:390,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
 if(process.env.MOMENTUM_OUTPUT)await page.screenshot({path:path.join(process.env.MOMENTUM_OUTPUT,'momentum-mobile.png'),fullPage:true});
 assert.deepEqual(errors,[]);assert.deepEqual(writes,[]);console.log('PASS real/fixture gates, all-stock audit, INR exits, expiry, error, catalog filtering, mobile, XSS text, zero writes');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
