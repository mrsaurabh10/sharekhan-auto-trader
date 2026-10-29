const {test}=require('node:test');
const assert=require('node:assert/strict');
const {sizeSwing}=require('../../src/main/resources/static/portal/stock-analysis-math.js');
const inputs={capital:'500000',risk:'2500',entry:'207',stop:'201',maxPositionPct:'10',maxHeatPct:'6',openRisk:'0'};
test('matches installed position-sizer reference result with a 10% position cap',()=>{
  const result=sizeSwing(inputs);assert.equal(result.quantity,241);assert.equal(result.positionValue,49887);assert.equal(result.plannedRisk,1446);
});
test('remaining portfolio heat can reduce quantity to zero',()=>{
  assert.equal(sizeSwing({...inputs,openRisk:'29994'}).quantity,1);
  assert.equal(sizeSwing({...inputs,openRisk:'30000'}).quantity,0);
  assert.equal(sizeSwing({...inputs,openRisk:'31000'}).quantity,0);
});
test('one-paise stop is calculated exactly without rounding up risk',()=>{
  const result=sizeSwing({...inputs,entry:'100.01',stop:'100',risk:'1.01'});assert.equal(result.quantity,101);assert.equal(result.plannedRisk,1.01);
});
test('invalid percentages, blank existing risk and inverted stops are rejected',()=>{
  for(const bad of ['0','100.01','NaN','Infinity','-1'])assert.throws(()=>sizeSwing({...inputs,maxPositionPct:bad}));
  assert.throws(()=>sizeSwing({...inputs,openRisk:''}));assert.throws(()=>sizeSwing({...inputs,stop:'207'}));
});
