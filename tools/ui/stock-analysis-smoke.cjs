// Fixture-only UI tests. No application server, credentials, broker or network API is used.
// NODE_PATH=<Playwright modules> BROWSER_EXECUTABLE_PATH=<Chrome> node tools/ui/stock-analysis-smoke.cjs
const {chromium} = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname,'../..');
(async () => {
  const browser = await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE_PATH ? {executablePath:process.env.BROWSER_EXECUTABLE_PATH} : {})});
  const output = process.env.STOCK_ANALYSIS_OUTPUT_DIR;
  if (output) fs.mkdirSync(output,{recursive:true});
  try {
    const page = await browser.newPage({viewport:{width:1440,height:1100}}), errors = [], writes = [];
    page.on('pageerror',error => errors.push(error.message));
    let fail = false, empty = false, delayReport = false;
    const report = (exchange,mode) => {
      const daily = mode === 'SWING';
      const candles = Array.from({length:daily ? 70 : 60},(_,i) => ({date:daily ? new Date(Date.UTC(2026,7,1+i)).toISOString().slice(0,10) : '2026-10-29',time:daily ? '00:00:00' : String(9+Math.floor((15+i*5)/60)).padStart(2,'0')+':'+String((15+i*5)%60).padStart(2,'0')+':00',open:250+i*.5,high:252+i*.5,low:249+i*.5,close:251+i*.5,volume:1000+i*20}));
      return {instrument:{symbol:'DEMO',exchange,scripCode:exchange==='NC'?10:20},mode,interval:daily?'daily':'5minute',provider:'Sharekhan',timezone:'Asia/Kolkata',fetchedAt:'2026-10-29T09:00:00Z',asOf:'2026-10-29T14:15:00',dataStatus:empty?'UNAVAILABLE':'HISTORICAL',trend:empty?'INSUFFICIENT_DATA':'BULLISH',received:empty?0:candles.length,rejected:0,duplicates:0,gaps:0,candles:empty?[]:candles,indicators:{sma20:empty?null:275,sma50:empty?null:265,sma200:null,rsi14:empty?null:65,atr14:empty?null:3,vwap:daily||empty?null:265,openingHigh:daily||empty?null:254,openingLow:daily||empty?null:249,priorClose:empty?null:249,rangeLow20:empty?null:260,rangeHigh20:empty?null:280},checks:[{name:'Data quality',status:empty?'UNAVAILABLE':'PASS',detail:'Completed fixture bars only.'}],warnings:['Synthetic fixture data. Recent provider coverage only.']};
    };
    await page.route('**/*',async route => {
      const req = route.request(),url = new URL(req.url()),p = url.pathname;
      const json = (body,status=200) => route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)});
      if(url.hostname !== 'ui.test') return route.abort();
      if(req.method() !== 'GET') { writes.push(p); return json({},500); }
      if(p === '/stock-analysis') return route.fulfill({contentType:'text/html',body:fs.readFileSync(path.join(root,'src/main/resources/templates/stock-analysis.html'),'utf8')});
      if(p.startsWith('/portal/')) return route.fulfill({contentType:p.endsWith('.js')?'text/javascript':'text/css',body:fs.readFileSync(path.join(root,'src/main/resources/static',p),'utf8')});
      if(p === '/api/stock-analysis/instruments') return json([{symbol:url.searchParams.get('q')==='XSS'?'<img src=x onerror=alert(1)>':'DEMO',exchange:url.searchParams.get('exchange'),scripCode:url.searchParams.get('exchange')==='NC'?10:20}]);
      if(p === '/api/stock-analysis/report') { if(delayReport) await new Promise(r=>setTimeout(r,400)); return fail?json({message:'Sharekhan data busy'},429):json(report(url.searchParams.get('exchange'),url.searchParams.get('mode'))); }
      return json({},404);
    });
    await page.goto('http://ui.test/stock-analysis');
    assert.equal(await page.locator('#analyzeButton').isDisabled(),true);
    await page.locator('#symbolSearch').fill('DEMO'); await page.locator('#searchResults button').click();
    await page.locator('#analyzeButton').click(); await page.locator('#analysisReport').waitFor({state:'visible'});
    assert.match(await page.locator('#reportTitle').textContent(),/DEMO · NSE/);
    assert.match(await page.locator('#metrics').textContent(),/Unavailable/);
    assert.equal(await page.locator('#candleTable tbody tr').count(),60);
    assert.equal(await page.locator('#warnings img').count(),0);
    assert.equal(await page.locator('#priceChart').evaluate(el => el.width > 0),true);
    for(const [name,value] of Object.entries({capital:'500000',risk:'1000',entry:'250',stop:'245'})) await page.locator('#sizingForm [name='+name+']').fill(value);
    await page.getByRole('button',{name:'Calculate quantity'}).click(); assert.match(await page.locator('#sizingResult').textContent(),/200 shares/);
    await page.locator('#sizingForm [name=stop]').fill('260'); await page.getByRole('button',{name:'Calculate quantity'}).click(); assert.match(await page.locator('#sizingResult').textContent(),/below/);
    await page.locator('#sizingForm [name=stop]').fill('245'); await page.getByRole('button',{name:'Calculate quantity'}).click();
    await page.evaluate(() => scrollTo(0,0));
    if(output) await page.screenshot({path:path.join(output,'stock-analysis-desktop.png'),fullPage:true});
    await page.locator('#analysisMode').selectOption('SWING'); await page.locator('#analyzeButton').click(); await page.locator('#analysisReport').waitFor({state:'visible'});
    assert.equal(await page.locator('#candleTable tbody tr').count(),70);
    await page.locator('#exchange').selectOption('BC'); assert.equal(await page.locator('#analyzeButton').isDisabled(),true); assert.equal(await page.locator('#analysisReport').isHidden(),true);
    await page.locator('#symbolSearch').fill('DEMO'); await page.locator('#searchResults button').click();
    fail=true; await page.locator('#analyzeButton').click(); await page.waitForFunction(() => document.querySelector('#analysisStatus').textContent.includes('busy')); assert.equal(await page.locator('#analysisReport').isHidden(),true);
    fail=false;empty=true;await page.locator('#analyzeButton').click();await page.locator('#analysisReport').waitFor({state:'visible'}); assert.match(await page.locator('#dataBadge').textContent(),/UNAVAILABLE/);
    empty=false;await page.locator('#analyzeButton').click();await page.locator('#analysisReport').waitFor({state:'visible'});assert.match(await page.locator('#reportTitle').textContent(),/BSE/);
    delayReport=true; await page.locator('#analyzeButton').click();await page.locator('#exchange').selectOption('NC');await page.waitForTimeout(500);assert.equal(await page.locator('#analysisReport').isHidden(),true); delayReport=false;
    await page.locator('#recentAnalyses button').first().click();assert.match(await page.locator('#reportTitle').textContent(),/BSE/);
    await page.setViewportSize({width:390,height:844}); await page.waitForTimeout(100);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth<=innerWidth),true);
    if(output)await page.screenshot({path:path.join(output,'stock-analysis-mobile.png'),fullPage:true});
    await page.locator('#symbolSearch').fill('XSS');await page.locator('#searchResults button').waitFor();assert.equal(await page.locator('#searchResults img').count(),0);assert.match(await page.locator('#searchResults').textContent(),/onerror/);
    assert.deepEqual(errors,[]);assert.deepEqual(writes,[]);
    console.log('PASS: search, NSE/BSE identity, intraday/daily, chart/table, sizing, failure/empty states, stale-response isolation, recent snapshots, mobile, XSS text, no writes.');
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exit(1);});
