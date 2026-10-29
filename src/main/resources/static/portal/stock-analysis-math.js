'use strict';
(function (root) {
  function paise(raw, label) {
    const value = String(raw).trim();
    if (!/^\d+(\.\d{1,2})?$/.test(value)) throw new Error(label + ' must be a positive INR amount with at most 2 decimal places.');
    const [whole, fraction = ''] = value.split('.');
    const n = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
    if (n <= 0n || n > 100000000000000n) throw new Error(label + ' must be above zero and at most ₹10,00,00,00,00,000.');
    return n;
  }
  function sizeCash(inputs) {
    const capital = paise(inputs.capital, 'Capital'), risk = paise(inputs.risk, 'Risk budget');
    const entry = paise(inputs.entry, 'Entry'), stop = paise(inputs.stop, 'Stop');
    if (stop >= entry) throw new Error('For long cash shares, the stop must be below the entry.');
    if (risk > capital) throw new Error('Risk budget cannot exceed account capital.');
    const distance = entry - stop, byRisk = risk / distance, byCapital = capital / entry;
    const quantity = byRisk < byCapital ? byRisk : byCapital;
    return {quantity: Number(quantity), riskPerShare: Number(distance) / 100,
      positionValue: Number(quantity * entry) / 100, plannedRisk: Number(quantity * distance) / 100,
      capitalLimited: byCapital < byRisk};
  }
  function sizeSwing(inputs) {
    const capital = paise(inputs.capital, 'Planning capital'), risk = paise(inputs.risk, 'Risk budget');
    const entry = paise(inputs.entry, 'Maximum entry'), stop = paise(inputs.stop, 'Stop');
    const bps = (raw, label) => { const n = paise(raw, label); if (n > 10000n) throw new Error(label + ' cannot exceed 100%.'); return n; };
    const position = bps(inputs.maxPositionPct, 'Maximum position %'), heat = bps(inputs.maxHeatPct, 'Maximum portfolio risk %');
    const openRisk = /^0(\.0{1,2})?$/.test(String(inputs.openRisk).trim()) ? 0n : paise(inputs.openRisk, 'Existing planned stop risk');
    if (stop >= entry || risk > capital) throw new Error('Stop must be below entry, and risk budget cannot exceed capital.');
    const remaining = capital * heat / 10000n - openRisk;
    const budget = remaining <= 0n ? 0n : remaining < risk ? remaining : risk;
    const distance = entry - stop, maxValue = capital * position / 10000n;
    const byRisk = budget / distance, byCapital = maxValue / entry;
    const quantity = byRisk < byCapital ? byRisk : byCapital;
    return {quantity:Number(quantity), positionValue:Number(quantity*entry)/100, plannedRisk:Number(quantity*distance)/100,
      remainingRisk:Number(remaining>0n?remaining:0n)/100, heatLimited:budget<risk, capitalLimited:byCapital<byRisk};
  }
  const api = {sizeCash, sizeSwing};
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.StockAnalysisMath = api;
})(typeof window !== 'undefined' ? window : globalThis);
