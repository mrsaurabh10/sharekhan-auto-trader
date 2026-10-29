'use strict';
(function () {
  const $ = id => document.getElementById(id), money = n => n == null ? 'Unavailable' : new Intl.NumberFormat('en-IN', {style:'currency', currency:'INR', maximumFractionDigits:2}).format(n);
  const numeric = n => n == null ? 'Unavailable' : Number(n).toLocaleString('en-IN', {maximumFractionDigits:2});
  const stamp = b => b ? b.date + (b.time && b.time !== '00:00:00' ? ' ' + b.time.slice(0,5) : '') + ' IST' : 'Unavailable';
  const node = (tag, text, cls) => { const el = document.createElement(tag); if (text != null) el.textContent = text; if (cls) el.className = cls; return el; };
  let selected = null, current = null, searchTimer, searchAbort, reportAbort, version = 0, chartBars = [], chartGeometry;
  const recent = [];
  function status(text, error = false) { $('analysisStatus').hidden = !text; $('analysisStatus').textContent = text || ''; $('analysisStatus').className = 'notice section-gap' + (error ? ' error' : ''); }
  async function api(url, signal) {
    const response = await fetch(url, {credentials:'same-origin', headers:{Accept:'application/json'}, signal});
    if (response.status === 401) { location.assign('/login'); throw new Error('Please sign in again.'); }
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.message || 'Analysis could not be loaded. Please retry.');
    return data;
  }
  function resetSelection() {
    version++; selected = null; reportAbort?.abort(); searchAbort?.abort(); clearTimeout(searchTimer);
    $('analyzeButton').disabled = true; $('selection').hidden = true; $('searchResults').replaceChildren();
    $('searchStatus').textContent = ''; $('analysisReport').hidden = true; $('emptyAnalysis').hidden = false;
    current = null; status('');
  }
  $('exchange').addEventListener('change', () => { resetSelection(); $('symbolSearch').value = ''; });
  $('symbolSearch').addEventListener('input', () => {
    resetSelection(); const q = $('symbolSearch').value.trim(), exchange = $('exchange').value, requestVersion = version;
    if (q.length < 2) return;
    $('searchStatus').textContent = 'Searching Sharekhan instrument metadata…';
    searchTimer = setTimeout(async () => {
      searchAbort = new AbortController();
      try {
        const rows = await api('/api/stock-analysis/instruments?' + new URLSearchParams({exchange, q}), searchAbort.signal);
        if (version !== requestVersion) return;
        $('searchStatus').textContent = rows.length ? 'Select an exact exchange / scrip match:' : 'No matching cash instrument in the current master. Try a different symbol or exchange.';
        $('searchResults').replaceChildren();
        rows.forEach(row => {
          const button = node('button', row.symbol + ' · ' + (row.exchange === 'NC' ? 'NSE' : 'BSE') + ' · ' + row.scripCode, 'secondary'); button.type = 'button';
          button.addEventListener('click', () => { selected = row; $('selection').hidden = false; $('selection').textContent = 'Selected: ' + row.symbol + ' · ' + row.exchange + ' · Scrip ' + row.scripCode; $('analyzeButton').disabled = false; $('searchResults').replaceChildren(); $('searchStatus').textContent = ''; });
          $('searchResults').append(button);
        });
      } catch (error) { if (error.name !== 'AbortError' && version === requestVersion) $('searchStatus').textContent = error.message; }
    }, 350);
  });
  $('analysisMode').addEventListener('change', () => { version++; reportAbort?.abort(); current = null; $('analysisReport').hidden = true; $('emptyAnalysis').hidden = false; $('analyzeButton').disabled = !selected; status(''); });
  $('analysisForm').addEventListener('submit', async event => {
    event.preventDefault(); if (!selected) return;
    reportAbort?.abort(); const abort = new AbortController(); reportAbort = abort;
    const requestVersion = version, mode = $('analysisMode').value, instrument = selected;
    const timer = setTimeout(() => abort.abort(), 45000);
    $('analyzeButton').disabled = true; $('analysisReport').hidden = true; status('Loading completed market-data candles and calculating technical context…');
    try {
      const report = await api('/api/stock-analysis/report?' + new URLSearchParams({exchange:instrument.exchange, scripCode:instrument.scripCode, mode}), abort.signal);
      if (version !== requestVersion) return;
      render(report); recent.unshift(report); if (recent.length > 6) recent.pop(); renderRecent();
    } catch (error) {
      if (version === requestVersion) status(error.name === 'AbortError' ? 'The data request timed out. Please retry; no analysis has been inferred.' : error.message, true);
    } finally { clearTimeout(timer); if (version === requestVersion) $('analyzeButton').disabled = !selected; }
  });
  function metric(label, value, detail) { const el = node('article', null, 'card metric'); el.append(node('div', label, 'stat-label'), node('div', value, 'stat-value'), node('small', detail)); $('metrics').append(el); }
  function render(report) {
    current = report; status(''); $('emptyAnalysis').hidden = true; $('analysisReport').hidden = false;
    const last = report.candles.at(-1), ind = report.indicators, isIntraday = report.mode === 'INTRADAY';
    $('reportTitle').textContent = report.instrument.symbol + ' · ' + (report.instrument.exchange === 'NC' ? 'NSE' : 'BSE');
    $('reportMeta').textContent = report.interval + ' · ' + report.provider + ' · Scrip ' + report.instrument.scripCode + ' · Last bar ' + stamp(last);
    $('dataBadge').textContent = report.dataStatus.replaceAll('_',' '); $('dataBadge').className = 'badge' + (report.dataStatus === 'RECENT' ? ' good' : '');
    $('metrics').replaceChildren();
    metric('Last completed close', money(last?.close), 'Historical candle close; not a live quote');
    metric('Trend alignment', report.trend.replaceAll('_',' '), 'Close / SMA20 / SMA50 on this timeframe');
    metric('RSI · Wilder 14', numeric(ind.rsi14), 'Requires 15 usable completed bars');
    metric('ATR · Wilder 14', money(ind.atr14), 'Volatility per bar on this timeframe');
    metric('SMA20 / SMA50', money(ind.sma20) + ' / ' + money(ind.sma50), 'Simple averages of completed closes');
    metric('SMA200', money(ind.sma200), 'Requires 200 usable completed bars');
    metric(isIntraday ? 'Session VWAP' : 'Prior available daily close', money(isIntraday ? ind.vwap : ind.priorClose), isIntraday ? 'Latest returned session, from 09:15' : 'Not an official adjusted reference price');
    metric(isIntraday ? 'Opening range · low / high' : '20-bar range · low / high', isIntraday ? money(ind.openingLow) + ' / ' + money(ind.openingHigh) : money(ind.rangeLow20) + ' / ' + money(ind.rangeHigh20), isIntraday ? '09:15–09:30, latest returned session' : 'Prior 20 daily bars; excludes latest bar');
    $('trendExplanation').textContent = report.trend === 'BULLISH' ? 'The last close is above SMA20, which is above SMA50. Review price levels, risk and data freshness before drawing a conclusion.' : report.trend === 'BEARISH' ? 'The last close is below SMA20, which is below SMA50. This describes price alignment and is not a short-sale instruction.' : report.trend === 'MIXED' ? 'The completed prices and averages do not have a consistent bullish or bearish alignment.' : 'There is not enough clean history to classify the trend. Unavailable indicators remain blank.';
    $('checks').replaceChildren(); report.checks.forEach(check => { const el = node('div',null,'research-check'); el.append(node('strong',check.name),node('span',check.status.replaceAll('_',' '),'badge'),node('p',check.detail)); $('checks').append(el); });
    $('coverage').replaceChildren();
    const coverage = [['Provider',report.provider],['Time zone',report.timezone],['First bar',stamp(report.candles[0])],['Last bar',stamp(last)],['Retrieved',new Date(report.fetchedAt).toLocaleString('en-IN',{timeZone:'Asia/Kolkata'}) + ' IST'],['Parsed rows',numeric(report.received)],['Usable / invalid',report.candles.length + ' / ' + report.rejected],['Duplicates / gaps',report.duplicates + ' / ' + report.gaps]];
    coverage.forEach(([label,value]) => $('coverage').append(node('dt',label),node('dd',value)));
    $('warnings').replaceChildren(); report.warnings.forEach(w => $('warnings').append(node('li',w)));
    renderTable(report.candles.slice(-120)); requestAnimationFrame(drawChart);
  }
  function renderTable(bars) {
    const table = node('table'), head = node('thead'), heading = node('tr'); ['IST bar start / date','Open ₹','High ₹','Low ₹','Close ₹','Volume'].forEach(h => heading.append(node('th',h))); head.append(heading); table.append(head);
    const body = node('tbody'); bars.forEach(bar => { const row = node('tr'); [stamp(bar),numeric(bar.open),numeric(bar.high),numeric(bar.low),numeric(bar.close),numeric(bar.volume)].forEach(value => row.append(node('td',value))); body.append(row); }); table.append(body); $('candleTable').replaceChildren(table);
  }
  function renderRecent() {
    $('recentAnalyses').replaceChildren(); recent.forEach(report => { const button = node('button',null,'secondary'); button.type = 'button'; button.append(node('strong',report.instrument.symbol + ' · ' + report.instrument.exchange),node('small',report.mode + ' · ' + new Date(report.fetchedAt).toLocaleTimeString('en-IN',{timeZone:'Asia/Kolkata'}))); button.addEventListener('click', () => { version++; reportAbort?.abort(); selected = report.instrument; $('exchange').value = selected.exchange; $('analysisMode').value = report.mode; $('symbolSearch').value = selected.symbol; $('selection').hidden = false; $('selection').textContent = 'Selected: ' + selected.symbol + ' · ' + selected.exchange + ' · Scrip ' + selected.scripCode; $('analyzeButton').disabled = false; render(report); $('reportTitle').scrollIntoView({block:'nearest'}); }); $('recentAnalyses').append(button); });
  }
  function drawChart() {
    if (!current || $('analysisReport').hidden) return;
    const canvas = $('priceChart'), width = canvas.parentElement.clientWidth, height = canvas.parentElement.clientHeight, ratio = window.devicePixelRatio || 1;
    canvas.width = Math.round(width * ratio); canvas.height = Math.round(height * ratio);
    const ctx = canvas.getContext('2d'); ctx.scale(ratio,ratio); ctx.clearRect(0,0,width,height);
    chartBars = current.candles.slice(-Number($('visibleBars').value)); const bars = chartBars;
    if (!bars.length) { ctx.fillStyle = '#66748b'; ctx.font = '14px system-ui'; ctx.fillText('No usable completed candles available.',20,50); chartGeometry = null; return; }
    const left = 10, right = Math.min(100,width * 0.25), top = 20, bottom = height - 80, usableWidth = width - left - right;
    let low = Math.min(...bars.map(b => b.low)), high = Math.max(...bars.map(b => b.high)); const pad = (high - low || high * .01) * .12; low -= pad; high += pad;
    const y = price => top + (high - price) / (high - low) * (bottom - top), dx = usableWidth / bars.length, x = i => left + dx * (i + .5);
    ctx.font = '11px system-ui'; ctx.lineWidth = 1;
    for (let i = 0; i <= 4; i++) { const p = low + (high - low) * i / 4; ctx.strokeStyle = '#e8edf4'; ctx.beginPath(); ctx.moveTo(left,y(p)); ctx.lineTo(width-right,y(p)); ctx.stroke(); ctx.fillStyle = '#66748b'; ctx.fillText(numeric(p),width-right+8,y(p)+4); }
    const maxVolume = Math.max(1,...bars.map(b => b.volume || 0));
    bars.forEach((b,i) => { ctx.strokeStyle = ctx.fillStyle = b.close >= b.open ? '#0b8b72' : '#d55566'; ctx.beginPath(); ctx.moveTo(x(i),y(b.high)); ctx.lineTo(x(i),y(b.low)); ctx.stroke(); const bodyTop = Math.min(y(b.open),y(b.close)); ctx.fillRect(x(i)-Math.max(1,dx*.6)/2,bodyTop,Math.max(1,dx*.6),Math.max(1,Math.abs(y(b.open)-y(b.close)))); if (b.volume != null && b.volume >= 0) { ctx.globalAlpha = .35; ctx.fillRect(x(i)-dx*.3,height-30-b.volume/maxVolume*36,Math.max(1,dx*.6),b.volume/maxVolume*36); ctx.globalAlpha = 1; } });
    if (!current.rejected && !current.gaps && current.indicators.sma20 != null) {
      ctx.strokeStyle = '#4169d8'; ctx.lineWidth = 1.5; ctx.beginPath(); let started = false;
      const all = current.candles, offset = all.length - bars.length;
      bars.forEach((b,i) => { const end = offset+i; if (end < 19) return; const avg = all.slice(end-19,end+1).reduce((sum,item) => sum+item.close,0)/20; if (avg < low || avg > high) { started = false; return; } if (!started) ctx.moveTo(x(i),y(avg)); else ctx.lineTo(x(i),y(avg)); started = true; }); ctx.stroke();
    }
    ctx.fillStyle = '#66748b'; if (usableWidth > 340) ctx.fillText(stamp(bars[0]),left,height-8); const endLabel = stamp(bars.at(-1)); ctx.fillText(endLabel,Math.max(left,width-right-ctx.measureText(endLabel).width),height-8);
    canvas.setAttribute('aria-label', bars.length + ' completed ' + current.interval + ' candles for ' + current.instrument.symbol + '. OHLCV values are available in the table below.');
    chartGeometry = {left,dx,width:usableWidth};
  }
  $('priceChart').addEventListener('mousemove', event => { if (!chartGeometry) return; const px = event.clientX - event.currentTarget.getBoundingClientRect().left, i = Math.floor((px-chartGeometry.left)/chartGeometry.dx), bar = chartBars[i]; if (bar && px <= chartGeometry.left+chartGeometry.width) $('chartReadout').textContent = stamp(bar) + ' · O ' + money(bar.open) + ' · H ' + money(bar.high) + ' · L ' + money(bar.low) + ' · C ' + money(bar.close) + ' · Vol ' + numeric(bar.volume); });
  $('visibleBars').addEventListener('change',drawChart); new ResizeObserver(() => { if (current) drawChart(); }).observe($('priceChart').parentElement);
  $('sizingForm').addEventListener('submit', event => { event.preventDefault(); const box = $('sizingResult'); box.hidden = false;
    try { const result = window.StockAnalysisMath.sizeCash(Object.fromEntries(new FormData(event.currentTarget))); box.className = 'sizing-result'; box.replaceChildren(node('strong',result.quantity.toLocaleString('en-IN') + ' shares'), node('div','Position value: ' + money(result.positionValue)),node('div','Planned stop-distance risk: ' + money(result.plannedRisk)),node('div','Risk per share: ' + money(result.riskPerShare)),node('small',result.quantity === 0 ? 'Budget or capital is insufficient for one whole share.' : result.capitalLimited ? 'Capital, rather than risk budget, limits this quantity.' : 'Whole shares rounded down to your risk budget.')); }
    catch (error) { box.className = 'sizing-result error'; box.textContent = error.message; }
  });
  $('downloadReport').addEventListener('click', () => { if (!current) return; const url = URL.createObjectURL(new Blob([JSON.stringify(current,null,2)],{type:'application/json'})), link = node('a'); link.href = url; link.download = current.instrument.symbol.replace(/[^a-z0-9_-]/gi,'_') + '_' + current.mode + '_analysis.json'; link.click(); setTimeout(() => URL.revokeObjectURL(url),1000); });
  // Show a useful first report, while respecting any interaction made during the initial lookup.
  (async () => {
    try {
      status('Loading an initial RELIANCE daily analysis…');
      const rows = await api('/api/stock-analysis/instruments?exchange=NC&q=RELIANCE');
      if (version !== 0 || selected) return;
      const match = rows.find(row => row.symbol.toUpperCase() === 'RELIANCE' && row.exchange === 'NC');
      if (!match) { status('Choose a stock above to start an analysis.'); return; }
      selected = match; $('symbolSearch').value = match.symbol; $('selection').hidden = false;
      $('selection').textContent = 'Selected: ' + match.symbol + ' · NC · Scrip ' + match.scripCode;
      $('analyzeButton').disabled = false; $('analysisForm').requestSubmit();
    } catch (error) { if (version === 0) status(error.message, true); }
  })();
})();
