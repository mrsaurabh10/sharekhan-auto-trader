// Local DOM-only browser test. No backend or broker requests.
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const path=require('node:path');
(async()=>{
  const browser=await chromium.launch({headless:true,...(process.env.BROWSER_EXECUTABLE_PATH?{executablePath:process.env.BROWSER_EXECUTABLE_PATH}:{})});
  try {
    const page=await browser.newPage();
    await page.route('**/*',route=>route.abort());
    await page.setContent('<main id="test"></main>');
    await page.addScriptTag({path:path.resolve(__dirname,'../../src/main/resources/static/strategy-runs.js')});
    const checks=await page.evaluate(()=>{
      const statuses=[['EXIT_ORDER_PLACED','EXIT_RECONCILIATION_REQUIRED','Needs attention'],
        ['EXIT_FAILED','EXIT_BROKER_ORDER_INACTIVE','Needs attention'],
        ['EXIT_ORDER_PLACED','DHAN_PARTIAL_EXIT_RECONCILIATION_REQUIRED','Needs attention'],
        ['EXIT_ORDER_PLACED',null,'Exit pending'],['EXITED_SUCCESS','EXIT_RECONCILIATION_REQUIRED','Exited']];
      for(const [status,reason,label] of statuses) {
        const tag=window.StrategyRunView.statusTag(status,reason);document.querySelector('main').append(tag);
        if(tag.textContent!==label)throw new Error(status+': '+tag.textContent);
        tag.focus();tag.blur();if(tag.textContent!==label)throw new Error('Lost attention label on blur');
      }
      const hostile=window.StrategyRunView.statusTag('EXIT_ORDER_PLACED','EXIT_RECONCILIATION_REQUIRED <img src=x onerror=alert(1)>');
      document.querySelector('main').append(hostile);
      if(document.querySelector('img'))throw new Error('Unsafe reason rendering');
      return statuses.length+1;
    });
    assert.equal(checks,6);console.log('PASS: six exit attention display and safe-text checks');
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
