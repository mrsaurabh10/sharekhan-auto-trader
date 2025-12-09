// admin-dashboard.js - full implementation
(function () {
  'use strict';

  // --- Utilities ---
  async function ensureCsrf() {
    const meta = document.querySelector('meta[name="_csrf"]');
    if (meta && meta.getAttribute('content')) return;
    try {
      const res = await fetch('/admin/csrf-token', { credentials: 'include' });
      if (!res.ok) return;
      const body = await res.json().catch(() => null);
      if (!body || !body.token) return;
      const set = (n, v) => {
        let m = document.querySelector('meta[name="' + n + '"]');
        if (!m) { m = document.createElement('meta'); m.setAttribute('name', n); document.head.appendChild(m); }
        m.setAttribute('content', v);
      };
      set('_csrf', body.token);
      set('_csrf_header', body.header || 'X-CSRF-TOKEN');
      set('_csrf_parameter', body.parameter || '_csrf');
    } catch (e) { console.debug('ensureCsrf failed', e); }
  }

  async function fetchJson(url, opts) {
    opts = opts || {};
    opts.credentials = opts.credentials || 'include';
    opts.headers = opts.headers || {};
    // preserve headers passed by caller
    opts.headers['X-Requested-With'] = opts.headers['X-Requested-With'] || 'XMLHttpRequest';

    // ensure CSRF header for non-GET methods
    try {
      const method = (opts.method || 'GET').toUpperCase();
      if (method !== 'GET') {
        // try to read meta tags populated by ensureCsrf
        const metaHeader = document.querySelector('meta[name="_csrf_header"]');
        const metaToken = document.querySelector('meta[name="_csrf"]');
        const headerName = metaHeader && metaHeader.getAttribute('content') ? metaHeader.getAttribute('content') : null;
        const token = metaToken && metaToken.getAttribute('content') ? metaToken.getAttribute('content') : null;
        if (headerName && token) {
          // do not overwrite if caller explicitly set this header
          if (!opts.headers[headerName]) opts.headers[headerName] = token;
          // also set common fallback header name if not present (some configs expect X-CSRF-TOKEN)
          if (!opts.headers['X-CSRF-TOKEN']) opts.headers['X-CSRF-TOKEN'] = token;
        }
        // if body present and no content-type set, default to json
        if (opts.body && !Object.keys(opts.headers).some(h => h.toLowerCase() === 'content-type')) {
          opts.headers['Content-Type'] = 'application/json';
        }
      }
    } catch (e) { console.debug('fetchJson csrf attach failed', e); }

    const res = await fetch(url, opts);
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error('HTTP ' + res.status + ' ' + text);
    }
    // Gracefully handle non-JSON (e.g., plain text "updated") and empty responses
    if (res.status === 204) return null;
    const ct = (res.headers && res.headers.get && res.headers.get('content-type')) || '';
    if (ct.toLowerCase().includes('json')) {
      // return parsed JSON or empty object if parsing fails unexpectedly
      try { return await res.json(); } catch (e) { return {}; }
    }
    // Fallbacks: try text then best-effort JSON parse
    const text = await res.text().catch(() => '');
    if (!text) return null;
    try { return JSON.parse(text); } catch (e) { /* not JSON */ }
    return text;
  }

  function escapeHtml(v) { return (v == null) ? '' : String(v).replace(/[&<>'"`]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":"&#39;","`":"&#96;"})[c]); }

  // --- Instruments / option helpers ---
  async function fetchInstrumentsForExchange(exchange) {
    if (!exchange) return [];
    const tried = [];
    // primary: path-style
    const url1 = '/api/scripts/instruments/' + encodeURIComponent(exchange);
    tried.push(url1);
    try {
      const j = await fetchJson(url1).catch(() => null);
      if (Array.isArray(j)) return j;
      if (j && Array.isArray(j.instruments)) return j.instruments;
    } catch (e) { console.debug('fetchInstruments json attempt1 failed', e); }

    // fallback: query param style
    const url2 = '/api/scripts/instruments?exchange=' + encodeURIComponent(exchange);
    tried.push(url2);
    try {
      const j2 = await fetchJson(url2).catch(() => null);
      if (Array.isArray(j2)) return j2;
      if (j2 && Array.isArray(j2.instruments)) return j2.instruments;
    } catch (e) { console.debug('fetchInstruments json attempt2 failed', e); }

    // final attempt: raw fetch and bruteforce parse
    try {
      const resp = await fetch(url1, { credentials: 'include' });
      if (resp && resp.ok) {
        const text = await resp.text().catch(() => null);
        if (text) {
          try { const parsed = JSON.parse(text); if (Array.isArray(parsed)) return parsed; if (parsed && Array.isArray(parsed.instruments)) return parsed.instruments; } catch (e) { /* not json */ }
          const lines = text.split(/\r?\n/).map(s => s.trim()).filter(Boolean);
          if (lines.length > 1) return lines;
          const parts = text.split(',').map(s => s.trim()).filter(Boolean);
          if (parts.length > 1) return parts;
          if (text.trim()) return [text.trim()];
        }
      }
    } catch (e) { console.debug('fetchInstruments raw attempt failed', e); }

    // try url2 raw as last resort
    try {
      const resp2 = await fetch(url2, { credentials: 'include' });
      if (resp2 && resp2.ok) {
        const text2 = await resp2.text().catch(() => null);
        if (text2) {
          try { const parsed = JSON.parse(text2); if (Array.isArray(parsed)) return parsed; if (parsed && Array.isArray(parsed.instruments)) return parsed.instruments; } catch (e) { }
          const lines = text2.split(/\r?\n/).map(s => s.trim()).filter(Boolean);
          if (lines.length > 1) return lines;
          const parts = text2.split(',').map(s => s.trim()).filter(Boolean);
          if (parts.length > 1) return parts;
          if (text2.trim()) return [text2.trim()];
        }
      }
    } catch (e) { console.debug('fetchInstruments raw attempt2 failed', e); }

    console.debug('fetchInstruments: tried endpoints', tried);
    return [];
  }

  function populateInstruments(instruments) {
    const sel = document.getElementById('instrument'); if (!sel) return;
    // remove previous search input if present so populate remains consistent
    const prevSearch = document.getElementById('admin-instrument-search'); if (prevSearch && Array.isArray(instruments) && instruments.length <= 500) prevSearch.remove();
    sel.innerHTML = '<option value="">Select Instrument</option>';
    if (!Array.isArray(instruments) || instruments.length === 0) { sel.disabled = true; return; }
    sel.disabled = false;
    const THRESH = 500, LIMIT = 300;
    const parent = sel.parentNode;
    let search = document.getElementById('admin-instrument-search');
    if (instruments.length > THRESH) {
      if (!search && parent) {
        search = document.createElement('input'); search.id = 'admin-instrument-search'; search.type = 'search'; search.placeholder = 'Filter instruments...'; search.style.width = '100%'; search.style.marginBottom = '6px'; parent.insertBefore(search, sel);
        let timer = null; search.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(() => { const q = (search.value||'').trim().toUpperCase(); const matched = q ? instruments.filter(s => s.toUpperCase().includes(q)) : instruments.slice(0, LIMIT); sel.innerHTML = '<option value="">Select Instrument</option>' + matched.slice(0, LIMIT).map(s => '<option value="' + escapeHtml(s) + '">' + escapeHtml(s) + '</option>').join(''); }, 120); });
      }
      sel.innerHTML += instruments.slice(0, LIMIT).map(s => '<option value="' + escapeHtml(s) + '">' + escapeHtml(s) + '</option>').join('');
    } else {
      if (search) search.remove(); sel.innerHTML += instruments.map(s => '<option value="' + escapeHtml(s) + '">' + escapeHtml(s) + '</option>').join('');
    }
  }

  async function fetchStrikes(exchange, instrument) {
    try { const j = await fetchJson('/api/scripts/strikes?exchange=' + encodeURIComponent(exchange) + '&instrument=' + encodeURIComponent(instrument)); return Array.isArray(j?.strikes) ? j.strikes : (Array.isArray(j) ? j : []); } catch (e) { console.debug('fetchStrikes failed', e); return []; }
  }

  async function fetchExpiries(exchange, instrument, strike) {
    try { const j = await fetchJson('/api/scripts/expiries?exchange=' + encodeURIComponent(exchange) + '&instrument=' + encodeURIComponent(instrument) + '&strikePrice=' + encodeURIComponent(strike)); return Array.isArray(j?.expiries) ? j.expiries : (Array.isArray(j) ? j : []); } catch (e) { console.debug('fetchExpiries failed', e); return []; }
  }

  // --- Edit modal (no prompts) ---
  function createEditModalIfNeeded() {
    if (document.getElementById('adminEditModal')) return;
    const modal = document.createElement('div'); modal.id = 'adminEditModal'; modal.style.cssText = 'position:fixed;left:0;top:0;width:100%;height:100%;display:none;align-items:center;justify-content:center;background:rgba(0,0,0,0.35);z-index:9999;';
    modal.innerHTML = '<div style="background:#fff;padding:16px;border-radius:6px;min-width:320px;max-width:520px;">' +
      '<h3 id="adminEditModalTitle">Edit</h3>' +
      '<div style="margin-bottom:8px"><label>Stop Loss</label><br/><input id="modal_stopLoss" type="number" step="0.01" style="width:100%"/></div>' +
      '<div style="margin-bottom:8px"><label>Target1</label><br/><input id="modal_target1" type="number" step="0.01" style="width:100%"/></div>' +
      '<div style="margin-bottom:8px"><label>Target2</label><br/><input id="modal_target2" type="number" step="0.01" style="width:100%"/></div>' +
      '<div style="margin-bottom:8px"><label>Target3</label><br/><input id="modal_target3" type="number" step="0.01" style="width:100%"/></div>' +
      '<div style="margin-bottom:8px"><label>Quantity</label><br/><input id="modal_quantity" type="number" min="1" style="width:100%"/></div>' +
      '<div style="text-align:right;margin-top:8px"><button id="modalCancel" class="btn">Cancel</button> <button id="modalSave" class="btn primary">Save</button></div>' +
      '</div>';
    document.body.appendChild(modal);
    document.getElementById('modalCancel').addEventListener('click', function () { modal.style.display = 'none'; });
  }

  function openEditModal(title, values, onSave) {
    createEditModalIfNeeded(); const modal = document.getElementById('adminEditModal'); document.getElementById('adminEditModalTitle').innerText = title || 'Edit';
    document.getElementById('modal_stopLoss').value = values.stopLoss != null ? values.stopLoss : '';
    document.getElementById('modal_target1').value = values.target1 != null ? values.target1 : '';
    document.getElementById('modal_target2').value = values.target2 != null ? values.target2 : '';
    document.getElementById('modal_target3').value = values.target3 != null ? values.target3 : '';
    document.getElementById('modal_quantity').value = values.quantity != null ? values.quantity : '';
    modal.style.display = 'flex';
    const saveBtn = document.getElementById('modalSave'); const newSave = saveBtn.cloneNode(true); saveBtn.parentNode.replaceChild(newSave, saveBtn);
    newSave.addEventListener('click', async function () {
      const payload = {};
      const sl = document.getElementById('modal_stopLoss').value.trim();
      const t1 = document.getElementById('modal_target1').value.trim();
      const t2 = document.getElementById('modal_target2').value.trim();
      const t3 = document.getElementById('modal_target3').value.trim();
      const q = document.getElementById('modal_quantity').value.trim();
      if (sl !== '') payload.stopLoss = Number(sl);
      if (t1 !== '') payload.target1 = Number(t1);
      if (t2 !== '') payload.target2 = Number(t2);
      if (t3 !== '') payload.target3 = Number(t3);
      if (q !== '') payload.quantity = Number(q);
      try { await onSave(payload); modal.style.display = 'none'; } catch (e) { alert('Save failed: ' + (e && e.message ? e.message : e)); }
    });
  }

  // --- Loaders for requests and executed trades ---
  async function loadRequestedOrdersForUser(userId) {
    const uid = userId || window.selectedUserId; const tbody = document.querySelector('#user-requests-table tbody'); if (!tbody) return; tbody.innerHTML = '';
    if (!uid) { tbody.innerHTML = '<tr><td colspan="11">No user selected</td></tr>'; return; }
    try {
      const data = await fetchJson('/api/orders/requests?userId=' + encodeURIComponent(uid));
      if (!Array.isArray(data) || data.length === 0) { tbody.innerHTML = '<tr><td colspan="11">No requests</td></tr>'; return; }
      const seen = new Set(); const uniq = [];
      for (const r of data) { const rid = r && (r.id || r.requestId || r.request_id); if (!rid) { uniq.push(r); continue; } if (seen.has(rid)) continue; seen.add(rid); uniq.push(r); }

      const underlyingMap = { NF: 'NSE', BF: 'BSE', NC: 'NSE', BC: 'BSE' };
      const optionMap = { NF: 'NFO', BF: 'BFO' };
      const rows = []; const keySet = new Set();

      for (const r of uniq) {
        const tr = document.createElement('tr');
        const id = r.id || '';
        // attach scripCode on row if available from API
        const rowScripCode = r.scripCode || r.scrip_code || r.scripCodeStr || r.scrip || r.scrip_code;
        if (rowScripCode) {
          try { tr.setAttribute('data-scrip-code', String(rowScripCode)); } catch (e) {}
        }
        const symbol = r.instrument || r.symbol || r.tradingSymbol || '-';
        const exchange = (r.exchange == null || r.exchange === '' || r.exchange === 'null') ? null : r.exchange;
        const strike = r.strikePrice != null ? r.strikePrice : (r.strike || '');
        const entry = r.entryPrice != null ? r.entryPrice : (r.entry || '-');
        const sl = r.stopLoss != null ? r.stopLoss : (r.stop_loss || '-');
        const t1 = r.target1 != null ? r.target1 : (r.t1 || '-');
        const qty = r.quantity != null ? r.quantity : (r.qty || '-');
        const status = r.status || r.requestStatus || '-';

        // action cell
        const actionCell = document.createElement('td');
        // Edit
        const editBtn = document.createElement('button'); editBtn.className = 'btn small'; editBtn.style.marginRight = '6px'; editBtn.innerText = 'Edit';
        editBtn.addEventListener('click', function () {
          openEditModal('Edit Request ' + id, { stopLoss: r.stopLoss, target1: r.target1, target2: r.target2, target3: r.target3, quantity: r.quantity }, async function (payload) {
            if (Object.keys(payload).length === 0) throw new Error('No changes'); await ensureCsrf(); await fetchJson('/api/trades/request/' + id, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) }); await loadRequestedOrdersForUser(uid);
          });
        }); actionCell.appendChild(editBtn);

        // Move SL
        const moveBtn = document.createElement('button'); moveBtn.className = 'btn small'; moveBtn.style.marginRight = '6px'; moveBtn.innerText = 'Move SL to Cost';
        moveBtn.addEventListener('click', async function () { const entryPrice = r.entryPrice != null ? r.entryPrice : (r.entry || null); if (entryPrice == null) { alert('No entry'); return; } await ensureCsrf(); await fetchJson('/api/trades/request/' + id, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ stopLoss: Number(entryPrice) }) }); await loadRequestedOrdersForUser(uid); }); actionCell.appendChild(moveBtn);

        // Cancel
        const cancelBtn = document.createElement('button'); cancelBtn.className = 'btn small danger'; cancelBtn.style.marginRight = '6px'; cancelBtn.innerText = 'Cancel';
        cancelBtn.addEventListener('click', async function () { if (!confirm('Cancel request id ' + id + '?')) return; await ensureCsrf(); await fetchJson('/api/trades/cancel-request/' + id + '?userId=' + encodeURIComponent(uid), { method: 'POST' }); await loadRequestedOrdersForUser(uid); }); actionCell.appendChild(cancelBtn);

        // Trigger (admin)
        if (String(status).toUpperCase() !== 'TRIGGERED' && String(status).toUpperCase() !== 'EXECUTED') {
          const trig = document.createElement('button'); trig.className = 'btn small'; trig.innerText = 'Trigger';
          trig.addEventListener('click', async function () { trig.disabled = true; try { await ensureCsrf(); await fetchJson('/admin/trigger/' + id, { method: 'POST' }); await loadRequestedOrdersForUser(uid); await loadExecutedForUser(uid); } catch (e) { alert('Trigger failed: ' + (e && e.message ? e.message : e)); } finally { trig.disabled = false; } }); actionCell.appendChild(trig);
        }

        tr.innerHTML = '<td>' + escapeHtml(id) + '</td><td>' + escapeHtml(String(symbol)) + '</td><td>' + escapeHtml(String(exchange || '-')) + '</td><td>' + escapeHtml(String(strike || '-')) + '</td><td>' + escapeHtml(String(entry)) + '</td><td>' + escapeHtml(String(sl)) + '</td><td>' + escapeHtml(String(t1)) + '</td><td>' + escapeHtml(String(qty)) + '</td><td>' + escapeHtml(String(status)) + '</td>';
        tr.appendChild(actionCell);
        const ltpTd = document.createElement('td'); ltpTd.innerText = '-'; tr.appendChild(ltpTd);

        // compute LTP candidates and attach
        const candidates = [];
        if (exchange) {
          const ex = String(exchange).toUpperCase();
          candidates.push((underlyingMap[ex] || ex) + ':' + symbol);
          candidates.push((optionMap[ex] || ex) + ':' + symbol);
        } else { ['NSE','BSE','NFO','BFO'].forEach(function(p){ candidates.push(p + ':' + symbol); }); }
        if (strike && strike !== '') { const ex = exchange ? String(exchange).toUpperCase() : 'NF'; const mapped = (optionMap[ex] || ex); candidates.push(mapped + ':' + symbol + (r.expiry ? r.expiry : '') + strike + (r.optionType ? r.optionType : '')); }
        const primary = candidates.length > 0 ? candidates[0] : null; if (primary) { tr.setAttribute('data-ltp-key', primary); ltpTd.setAttribute('data-ltp', primary); }
        tbody.appendChild(tr);
        candidates.forEach(function(k){ keySet.add(k); }); rows.push({ ltpTd: ltpTd, candidates: candidates, exchange: exchange, symbol: symbol, strike: strike, expiry: r.expiry || null, optionType: r.optionType || null });
      }

      // fill LTP in batch
      if (keySet.size > 0) {
        const map = await batchFetchMstockLtp(Array.from(keySet));
        for (const info of rows) {
          let filled = false;
          for (const k of info.candidates) {
            if (map && map[k] && map[k].last_price != null) {
              info.ltpTd.innerText = Number(map[k].last_price).toFixed(2);
              filled = true;
              break;
            }
          }
          if (!filled) {
            try {
              const params = new URLSearchParams();
              if (info.exchange) params.set('exchange', info.exchange);
              params.set('instrument', info.symbol);
              if (info.strike) params.set('strikePrice', String(info.strike));
              if (info.optionType) params.set('optionType', info.optionType);
              if (info.expiry) params.set('expiry', info.expiry);

              const optRes = await fetch('/api/scripts/option?' + params.toString(), { credentials: 'include' });
              if (optRes && optRes.ok) {
                const oj = await optRes.json().catch(() => null);
                if (oj && oj.tradingSymbol) {
                  // if option lookup provided scripCode, annotate the row so ws updates can match
                  if (oj.scripCode || oj.scrip_code) {
                    try { tr.setAttribute('data-scrip-code', String(oj.scripCode || oj.scrip_code)); } catch (e) {}
                  }
                  const mapped = (info.exchange ? (info.exchange.toUpperCase() === 'NF' ? 'NFO' : (info.exchange.toUpperCase() === 'BF' ? 'BFO' : info.exchange)) : info.exchange) || info.exchange;
                  const key = mapped + ':' + oj.tradingSymbol;
                  // prefer WS cache; fall back to REST only if websocket not available or data missing
                  const maybe = await getLtpFromCacheOrFallback(key);
                  if (maybe != null) {
                    info.ltpTd.innerText = Number(maybe).toFixed(2);
                    filled = true;
                  }
                }
              }
            } catch (e) {
              console.debug('option-resolve failed', e);
            }
          }
        }
      }

    } catch (e) { console.error('Failed to load requests for user', uid, e); tbody.innerHTML = '<tr><td colspan="11">Error loading requests</td></tr>'; }
  }

  async function loadExecutedForUser(userId) {
    const uid = userId || window.selectedUserId; const tbody = document.querySelector('#user-executed-table tbody'); if (!tbody) return; tbody.innerHTML = '';
    if (!uid) { tbody.innerHTML = '<tr><td colspan="11">No user selected</td></tr>'; return; }
    try {
      const data = await fetchJson('/api/orders/executed?userId=' + encodeURIComponent(uid));
      if (!Array.isArray(data) || data.length === 0) { tbody.innerHTML = '<tr><td colspan="11">No executed trades</td></tr>'; return; }
      const seen = new Set(); const uniq = [];
      for (const t of data) { const tid = t && (t.id || t.tradeId || t.trade_id); if (!tid) { uniq.push(t); continue; } if (seen.has(tid)) continue; seen.add(tid); uniq.push(t); }

      const underlyingMap = { NF: 'NSE', BF: 'BSE', NC: 'NSE', BC: 'BSE' };
      const optionMap = { NF: 'NFO', BF: 'BFO' };
      const rows = []; const keySet = new Set();

      for (const t of uniq) {
        const tr = document.createElement('tr');
        const id = t.id || '';
        const rowScripCode = t.scripCode || t.scrip_code || t.scrip || t.tradingScripCode;
        if (rowScripCode) {
          try { tr.setAttribute('data-scrip-code', String(rowScripCode)); } catch (e) {}
        }
        const symbol = t.instrument || t.symbol || t.tradingSymbol || '-';
        const exchange = (t.exchange == null || t.exchange === '' || t.exchange === 'null') ? null : t.exchange;
        const strike = t.strikePrice != null ? t.strikePrice : (t.strike || '');
        const entry = t.entryPrice != null ? t.entryPrice : (t.entry || '-');
        const sl = t.stopLoss != null ? t.stopLoss : (t.stop_loss || '-');
        const t1 = t.target1 != null ? t.target1 : (t.t1 || '-');
        const qty = t.quantity != null ? t.quantity : (t.qty || '-');
        const status = t.status || '-';

        const actionCell = document.createElement('td');
        const forbidden = new Set(['REJECTED', 'EXITED_SUCCESS', 'EXITED_FAILURE', 'EXITED']);
        if (!forbidden.has(String(status).toUpperCase())) {
          const editBtn = document.createElement('button'); editBtn.className = 'btn small'; editBtn.style.marginRight = '6px'; editBtn.innerText = 'Edit';
          editBtn.addEventListener('click', function () { openEditModal('Edit Trade ' + id, { stopLoss: t.stopLoss, target1: t.target1, target2: t.target2, target3: t.target3, quantity: t.quantity }, async function (payload) { if (Object.keys(payload).length === 0) throw new Error('No changes'); if (window.selectedUserId) payload.userId = window.selectedUserId; await ensureCsrf(); await fetchJson('/api/trades/execution/' + id, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) }); await loadExecutedForUser(uid); }); }); actionCell.appendChild(editBtn);
          const moveBtn = document.createElement('button'); moveBtn.className = 'btn small'; moveBtn.style.marginRight = '6px'; moveBtn.innerText = 'Move SL to Cost'; moveBtn.addEventListener('click', async function () { if (!confirm('Move SL to entry price for trade ' + id + '?')) return; await ensureCsrf(); await fetchJson('/api/trades/move-sl-to-cost/' + id, { method: 'POST' }); await loadExecutedForUser(uid); }); actionCell.appendChild(moveBtn);
          const closeBtn = document.createElement('button'); closeBtn.className = 'btn small danger'; closeBtn.innerText = 'Close'; closeBtn.addEventListener('click', async function () { if (!confirm('Square off trade ' + id + ' now?')) return; await ensureCsrf(); await fetchJson('/api/trades/square-off/' + id, { method: 'POST' }); setTimeout(function () { try { loadExecutedForUser(uid); } catch (e) { } }, 800); }); actionCell.appendChild(closeBtn);
        } else { actionCell.innerText = '-'; }

        tr.innerHTML = '<td>' + escapeHtml(id) + '</td><td>' + escapeHtml(String(symbol)) + '</td><td>' + escapeHtml(String(exchange || '-')) + '</td><td>' + escapeHtml(String(strike || '-')) + '</td><td>' + escapeHtml(String(entry)) + '</td><td>' + escapeHtml(String(sl)) + '</td><td>' + escapeHtml(String(t1)) + '</td><td>' + escapeHtml(String(qty)) + '</td><td>' + escapeHtml(String(status)) + '</td>';
        tr.appendChild(actionCell);
        const ltpTd = document.createElement('td'); ltpTd.innerText = '-'; tr.appendChild(ltpTd);

        const candidates = [];
        if (exchange) { const ex = String(exchange).toUpperCase(); candidates.push((underlyingMap[ex] || ex) + ':' + symbol); candidates.push((optionMap[ex] || ex) + ':' + symbol); } else { ['NSE','BSE','NFO','BFO'].forEach(function(p){ candidates.push(p + ':' + symbol); }); }
        if (strike && strike !== '') { const ex = exchange ? String(exchange).toUpperCase() : 'NF'; const mapped = (optionMap[ex] || ex); candidates.push(mapped + ':' + symbol + (t.expiry ? t.expiry : '') + strike + (t.optionType ? t.optionType : '')); }
        const primary = candidates.length > 0 ? candidates[0] : null; if (primary) { tr.setAttribute('data-ltp-key', primary); ltpTd.setAttribute('data-ltp', primary); }
        tbody.appendChild(tr); candidates.forEach(function(k){ keySet.add(k); }); rows.push({ ltpTd: ltpTd, candidates: candidates, exchange: exchange, symbol: symbol, strike: strike, expiry: t.expiry || null, optionType: t.optionType || null });
      }

      if (keySet.size > 0) {
        const map = await batchFetchMstockLtp(Array.from(keySet));
        for (const info of rows) {
          let filled = false;
          for (const k of info.candidates) {
            if (map && map[k] && map[k].last_price != null) {
              info.ltpTd.innerText = Number(map[k].last_price).toFixed(2);
              filled = true;
              break;
            }
          }
          if (!filled) {
            try {
              const params = new URLSearchParams();
              if (info.exchange) params.set('exchange', info.exchange);
              params.set('instrument', info.symbol);
              if (info.strike) params.set('strikePrice', String(info.strike));
              if (info.optionType) params.set('optionType', info.optionType);
              if (info.expiry) params.set('expiry', info.expiry);

              const optRes = await fetch('/api/scripts/option?' + params.toString(), { credentials: 'include' });
              if (optRes && optRes.ok) {
                const oj = await optRes.json().catch(() => null);
                if (oj && oj.tradingSymbol) {
                  if (oj.scripCode || oj.scrip_code) {
                    try { tr.setAttribute('data-scrip-code', String(oj.scripCode || oj.scrip_code)); } catch (e) {}
                  }
                  const mapped = (info.exchange ? (info.exchange.toUpperCase() === 'NF' ? 'NFO' : (info.exchange.toUpperCase() === 'BF' ? 'BFO' : info.exchange)) : info.exchange) || info.exchange;
                  const key = mapped + ':' + oj.tradingSymbol;
                  const val = await getLtpFromCacheOrFallback(key);
                  if (val != null) return Number(val);
                }
              }
            } catch (e) {
              console.debug('option-resolve failed', e);
            }
          }
        }
      }

    } catch (e) { console.error('Failed to load executed trades for user', uid, e); tbody.innerHTML = '<tr><td colspan="11">Error loading executed trades</td></tr>'; }
  }

  // --- LTP helpers + websocket + polling fallback ---
  async function batchFetchMstockLtp(keys) {
    const out = {};
    if (!Array.isArray(keys) || keys.length === 0) return out;

    // If websocket is connected, prefer cached values pushed by WS and avoid REST calls
    if (adminWs && adminWs.readyState === WebSocket.OPEN) {
      for (const k of keys) {
        if (ltpCache[k] && ltpCache[k].last_price != null) out[k] = ltpCache[k];
      }
      return out;
    }

    // Fallback to REST batch fetch when websocket not available
    const CHUNK = 90;
    for (let i = 0; i < keys.length; i += CHUNK) {
      const chunk = keys.slice(i, i + CHUNK);
      const url = '/api/mstock/ltp?' + chunk.map(k => 'i=' + encodeURIComponent(k)).join('&');
      try {
        const r = await fetch(url, { credentials: 'include' });
        if (!r.ok) continue;
        const j = await r.json().catch(() => null);
        if (j && j.status === 'success' && j.data) Object.assign(out, j.data);
      } catch (e) { console.debug('batchFetch failed', e); }
    }
    return out;
  }

  // Helper to get a single key's LTP from cache (preferred) or fallback to REST when WS not connected
  async function getLtpFromCacheOrFallback(key) {
    try {
      if (!key) return null;
      if (ltpCache && ltpCache[key] && ltpCache[key].last_price != null) return Number(ltpCache[key].last_price);
      // if WS is up but value not yet received, prefer no REST call and let WS update later
      if (adminWs && adminWs.readyState === WebSocket.OPEN) return null;
      // fallback to REST for single key
      const res = await fetch('/api/mstock/ltp?i=' + encodeURIComponent(key), { credentials: 'include' });
      if (!res.ok) return null;
      const j = await res.json().catch(() => null);
      if (j && j.status === 'success' && j.data && j.data[key] && j.data[key].last_price != null) return Number(j.data[key].last_price);
    } catch (e) { console.debug('getLtpFromCacheOrFallback failed', e); }
    return null;
  }

  let adminWs = null;
  let pollHandle = null;
  let ltpCache = {};

  function applyLtpMap(map) {
    try {
      if (!map || typeof map !== 'object') return;
      Object.assign(ltpCache, map || {});
      // update rows that were annotated with data-ltp-key (mstock style keys)
      document.querySelectorAll('tr[data-ltp-key]').forEach(function(tr) {
        try {
          const key = tr.getAttribute('data-ltp-key');
          if (!key) return;
          const td = tr.querySelector('td[data-ltp]');
          if (td && ltpCache[key] && ltpCache[key].last_price != null) {
            const v = Number(ltpCache[key].last_price).toFixed(2);
            if (td.textContent !== v) td.textContent = v;
          }
        } catch (e) { /* ignore per-elem error */ }
      });
    } catch (e) {
      console.debug('applyLtpMap failed', e);
    }
  }

  function startAdminWs() {
    try {
      if (adminWs && adminWs.readyState === WebSocket.OPEN) return;
      const proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
      const url = proto + location.host + '/ws/ltp';
      adminWs = new WebSocket(url);

      adminWs.onopen = function() {
        console.debug('admin LTP ws open');
        if (pollHandle) { clearInterval(pollHandle); pollHandle = null; }
      };

      adminWs.onmessage = function(ev) {
        try {
          const js = JSON.parse(ev.data);
          if (!js) return;

          // 1) numeric scripCode messages from backend: { scripCode: 12345, ltp: 123.45 }
          if (js.scripCode != null && (js.ltp != null || js.last_price != null)) {
            const sc = String(js.scripCode);
            const lv = (js.ltp != null) ? js.ltp : js.last_price;
            try {
              // update any rows annotated with data-scrip-code
              document.querySelectorAll('[data-scrip-code]').forEach(function(elem) {
                try {
                  if (elem.getAttribute('data-scrip-code') === sc) {
                    // prefer a child td[data-ltp] if present
                    const td = elem.querySelector ? elem.querySelector('td[data-ltp]') : null;
                    const v = (lv != null && !isNaN(lv)) ? Number(lv).toFixed(2) : '-';
                    if (!td && elem.getAttribute && elem.getAttribute('data-ltp') !== null) {
                      if (elem.textContent !== v) elem.textContent = v;
                    } else if (td) {
                      if (td.textContent !== v) td.textContent = v;
                    }
                  }
                } catch (e) { /* ignore per-elem error */ }
              });
            } catch (e) { console.debug('apply scripCode update failed', e); }
            return;
          }

          // 2) mstock style single tick: { i: 'NFO:XYZ', last_price: 12.34 }
          if (js.i && js.last_price != null) {
            const m = {};
            m[js.i] = { last_price: js.last_price };
            applyLtpMap(m);
            return;
          }

          // 3) wrapper with data map: { data: { 'NFO:XYZ': { last_price: 12.34 }, ... } }
          if (js.data && typeof js.data === 'object') {
            applyLtpMap(js.data);
            return;
          }

          // 4) array of small items [{i:'NFO:XYZ', last_price:..}, ...]
          if (Array.isArray(js)) {
            const m = {};
            js.forEach(function(it) { if (it && it.i && it.last_price != null) m[it.i] = { last_price: it.last_price }; });
            applyLtpMap(m);
            return;
          }

        } catch (e) {
          console.debug('adminWs parse failed', e);
        }
      };

      adminWs.onclose = function() {
        console.debug('admin LTP ws closed');
        adminWs = null;
        startAdminPolling();
      };

      adminWs.onerror = function(e) {
        console.debug('admin LTP ws error', e);
        if (adminWs) try { adminWs.close(); } catch (ign) {}
      };
    } catch (e) {
      console.debug('startAdminWs failed', e);
      startAdminPolling();
    }
  }

  function startAdminPolling() {
    if (pollHandle) return;
    pollHandle = setInterval(async function() {
      try {
        const keys = new Set();
        document.querySelectorAll('tr[data-ltp-key]').forEach(function(tr) { const k = tr.getAttribute('data-ltp-key'); if (k) keys.add(k); });
        if (keys.size === 0) return;
        const map = await batchFetchMstockLtp(Array.from(keys));
        applyLtpMap(map);
      } catch (e) { console.debug('admin poll failed', e); }
    }, 5000);
  }

  // --- load users and brokers ---
  async function loadUsers() {
    const container = document.getElementById('usersContainer'); if (!container) return; container.innerText = 'Loading users...';
    try {
      let users = null; try { users = await fetchJson('/admin/app-users'); } catch (e) { console.debug('/admin/app-users failed', e); }
      if (!Array.isArray(users) || users.length === 0) { try { users = await fetchJson('/admin/users'); } catch (e) { console.debug('/admin/users failed', e); } }
      // normalize users to an array to avoid null/undefined flows
      if (!Array.isArray(users)) users = [];
      if (users.length === 0) { container.innerText = 'No users'; return; }
      const ul = document.createElement('ul'); ul.style.padding = '0'; users.forEach(function(u){ const li = document.createElement('li'); li.innerText = (u.username || ('user-' + u.id)) + (u.customerId ? (' [' + u.customerId + ']') : ''); li.style.padding = '6px'; li.style.cursor = 'pointer'; li.addEventListener('click', function(){ selectUser(u.id, li, u.customerId); }); ul.appendChild(li); }); container.innerHTML = ''; container.appendChild(ul); const first = ul.querySelector('li'); if (first) first.click();
    } catch (e) { container.innerText = 'Error loading users: ' + (e && e.message ? e.message : e); }
  }

  async function loadBrokers(userId) {
    const tbody = document.querySelector('#brokersTable tbody');
    if (!tbody) return;
    tbody.innerHTML = '';
    try {
      const data = await fetchJson('/admin/app-users/' + encodeURIComponent(userId) + '/brokers');
      if (!Array.isArray(data) || data.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7">No brokers configured</td></tr>';
        return;
      }
      data.forEach(function (b) {
        const tr = document.createElement('tr');
        tr.setAttribute('data-broker-id', b.id);
        tr.innerHTML =
          '<td>' + escapeHtml(b.id || '') + '</td>' +
          '<td>' + escapeHtml(b.brokerName || '') + '</td>' +
          '<td>' + escapeHtml(b.customerId || '') + '</td>' +
          '<td>' + escapeHtml(b.clientCode || '') + '</td>' +
          '<td>' + (b.hasApiKey ? 'Yes' : 'No') + '</td>' +
          '<td>' + (b.active ? 'Yes' : 'No') + '</td>' +
          '<td>' +
            '<button class="btn" data-action="edit">Edit</button> ' +
            '<button class="btn danger" data-action="delete">Delete</button>' +
          '</td>';
        // attach actions
        tr.querySelector('[data-action="edit"]').addEventListener('click', function(){ openBrokerEditor(b); });
        tr.querySelector('[data-action="delete"]').addEventListener('click', function(){ deleteBroker(b.id); });
        tbody.appendChild(tr);
      });
    } catch (e) {
      console.debug('loadBrokers failed', e);
      tbody.innerHTML = '<tr><td colspan="7">Error loading brokers</td></tr>';
    }
  }

  function showBrokersSectionFor(userId) {
    const sec = document.getElementById('brokersSection');
    const lbl = document.getElementById('selectedUserId');
    if (lbl) lbl.innerText = String(userId);
    if (sec) sec.style.display = 'block';
  }

  function resetAddBrokerForm() {
    ['b_brokerName','b_customerId','b_apiKey','b_brokerUser','b_brokerPw','b_clientCode','b_totp','b_secret'].forEach(function(id){ const el = document.getElementById(id); if (el) el.value = ''; });
    const chk = document.getElementById('b_active'); if (chk) chk.checked = true;
  }

  function openAddBrokerForm() {
    resetAddBrokerForm();
    const f = document.getElementById('addBrokerForm'); if (f) f.style.display = 'block';
  }

  function closeAddBrokerForm() {
    const f = document.getElementById('addBrokerForm'); if (f) f.style.display = 'none';
  }

  async function openBrokerEditor(b) {
    const editor = document.getElementById('brokerEditor'); if (!editor) return;
    const setVal = (id, val) => { const el = document.getElementById(id); if (el) el.value = (val == null ? '' : val); };
    try {
      // Show basic info while loading
      document.getElementById('editBrokerIdLbl').innerText = '#' + b.id;
      editor.setAttribute('data-edit-id', b.id);
      editor.style.display = 'block';
      closeAddBrokerForm();

      // Fetch full broker details (including decrypted secrets) for admin prefill
      const full = await fetchJson('/admin/brokers/' + encodeURIComponent(b.id)).catch(() => null);
      const data = full || b || {};

      setVal('e_brokerName', data.brokerName);
      setVal('e_customerId', data.customerId);
      setVal('e_apiKey', data.apiKey);
      setVal('e_brokerUser', data.brokerUsername);
      setVal('e_brokerPw', data.brokerPassword);
      setVal('e_clientCode', data.clientCode);
      setVal('e_totp', data.totpSecret);
      setVal('e_secret', data.secretKey);
      const chk = document.getElementById('e_active'); if (chk) chk.checked = !!data.active;
    } catch (e) {
      console.debug('openBrokerEditor failed', e);
      // fallback to minimal prefill
      setVal('e_brokerName', b && b.brokerName);
      setVal('e_customerId', b && b.customerId);
      const chk = document.getElementById('e_active'); if (chk) chk.checked = !!(b && b.active);
    }
  }

  function closeBrokerEditor() {
    const editor = document.getElementById('brokerEditor'); if (editor) { editor.style.display = 'none'; editor.removeAttribute('data-edit-id'); }
  }

  async function saveEditedBroker() {
    const editor = document.getElementById('brokerEditor'); if (!editor) return;
    const id = editor.getAttribute('data-edit-id'); if (!id) return;
    // Collect all fields (prefilled earlier) so we send complete state
    const getVal = id => { const el = document.getElementById(id); return el ? el.value : null; };
    const toNullIfEmpty = v => (v === '' || v === undefined) ? null : v;
    let customerIdRaw = getVal('e_customerId');
    const body = {
      brokerName: toNullIfEmpty(getVal('e_brokerName')),
      customerId: toNullIfEmpty(customerIdRaw),
      apiKey: toNullIfEmpty(getVal('e_apiKey')),
      brokerUsername: toNullIfEmpty(getVal('e_brokerUser')),
      brokerPassword: toNullIfEmpty(getVal('e_brokerPw')),
      clientCode: toNullIfEmpty(getVal('e_clientCode')),
      totpSecret: toNullIfEmpty(getVal('e_totp')),
      secretKey: toNullIfEmpty(getVal('e_secret')),
      active: (document.getElementById('e_active')||{}).checked
    };
    // Normalize numeric types
    if (body.customerId != null) {
      const n = Number(body.customerId);
      body.customerId = Number.isNaN(n) ? null : n;
    }
    try {
      await ensureCsrf();
      await fetchJson('/admin/brokers/' + encodeURIComponent(id), { method: 'PUT', body: JSON.stringify(body) });
      closeBrokerEditor();
      await loadBrokers(window.selectedUserId);
    } catch (e) {
      alert('Failed to save broker: ' + (e && e.message ? e.message : e));
    }
  }

  async function deleteBroker(id) {
    if (!id) return;
    if (!confirm('Delete broker #' + id + '?')) return;
    try {
      await ensureCsrf();
      await fetch('/admin/brokers/' + encodeURIComponent(id), { method: 'DELETE', credentials: 'include', headers: { 'X-Requested-With': 'XMLHttpRequest' } });
      await loadBrokers(window.selectedUserId);
    } catch (e) {
      alert('Failed to delete broker: ' + (e && e.message ? e.message : e));
    }
  }

  async function submitAddBroker() {
    const userId = window.selectedUserId;
    if (!userId) { alert('Select a user first'); return; }
    const body = {
      brokerName: (document.getElementById('b_brokerName')||{}).value || null,
      customerId: (document.getElementById('b_customerId')||{}).value || null,
      apiKey: (document.getElementById('b_apiKey')||{}).value || null,
      brokerUsername: (document.getElementById('b_brokerUser')||{}).value || null,
      brokerPassword: (document.getElementById('b_brokerPw')||{}).value || null,
      clientCode: (document.getElementById('b_clientCode')||{}).value || null,
      totpSecret: (document.getElementById('b_totp')||{}).value || null,
      secretKey: (document.getElementById('b_secret')||{}).value || null,
      active: (document.getElementById('b_active')||{}).checked
    };
    if (!body.brokerName) { alert('Broker Name is required'); return; }
    try {
      await ensureCsrf();
      await fetchJson('/admin/app-users/' + encodeURIComponent(userId) + '/brokers', { method: 'POST', body: JSON.stringify(body) });
      closeAddBrokerForm();
      await loadBrokers(userId);
    } catch (e) {
      alert('Failed to add broker: ' + (e && e.message ? e.message : e));
    }
  }

  function wireBrokersUI() {
    try {
      const btnRefresh = document.getElementById('refreshBrokersBtn');
      if (btnRefresh) btnRefresh.addEventListener('click', function(){ if (window.selectedUserId) loadBrokers(window.selectedUserId); });
      const btnShowAdd = document.getElementById('showAddBrokerBtn');
      if (btnShowAdd) btnShowAdd.addEventListener('click', function(){ openAddBrokerForm(); });
      const btnAddSubmit = document.getElementById('addBrokerSubmit');
      if (btnAddSubmit) btnAddSubmit.addEventListener('click', function(){ submitAddBroker(); });
      const btnAddCancel = document.getElementById('addBrokerCancel');
      if (btnAddCancel) btnAddCancel.addEventListener('click', function(){ closeAddBrokerForm(); });
      const btnSaveEdit = document.getElementById('saveBrokerBtn');
      if (btnSaveEdit) btnSaveEdit.addEventListener('click', function(){ saveEditedBroker(); });
      const btnCancelEdit = document.getElementById('cancelEditBrokerBtn');
      if (btnCancelEdit) btnCancelEdit.addEventListener('click', function(){ closeBrokerEditor(); });
    } catch (e) { console.debug('wireBrokersUI failed', e); }
  }

  // select user helper
  function selectUser(appUserId, liElem, customerId) {
    try {
      window.selectedUserId = appUserId;
      window.selectedCustomerId = (customerId == null || customerId === 'null' || customerId === '') ? appUserId : customerId;
      const lbl = document.getElementById('placeOrderSelectedUser'); if (lbl) lbl.innerText = window.selectedCustomerId;
      const uc = document.getElementById('userContent'); if (uc) uc.style.display = 'block';
      try {
        const parent = document.getElementById('usersContainer');
        if (parent) parent.querySelectorAll('li').forEach(function(li){ li.classList && li.classList.remove('active'); });
        if (liElem && liElem.classList) liElem.classList.add('active');
      } catch (e) {}
      // show and set brokers section label
      showBrokersSectionFor(appUserId);
      // load data
      loadRequestedOrdersForUser(appUserId).catch(function(){});
      loadExecutedForUser(appUserId).catch(function(){});
      loadBrokers(appUserId).catch(function(){});
    } catch (e) { console.debug('selectUser failed', e); }
  }

  // UI wiring: exchange -> instruments -> strikes -> expiries
  function wireAdminForm() {
    try {
      const exSel = document.getElementById('exchange');
      const instrSel = document.getElementById('instrument');
      const strikeSel = document.getElementById('strikePrice');
      const expirySel = document.getElementById('expiry');
      const optionTypeSel = document.getElementById('optionType');
      if (!exSel) return;

      exSel.addEventListener('change', async function () {
        const ex = (this.value || '').trim();
        console.debug('exchange changed to', ex);
        if (!instrSel) return;
        instrSel.disabled = true;
        instrSel.innerHTML = '<option value="">Loading...</option>';

        // disable/enable option type & strike/expiry for NC/BC
        const isNCBC = (ex === 'NC' || ex === 'BC');
        if (strikeSel) { strikeSel.disabled = !!isNCBC; strikeSel.innerHTML = '<option value="">Select Strike</option>'; }
        if (expirySel) { expirySel.disabled = !!isNCBC; expirySel.innerHTML = '<option value="">Select Expiry</option>'; }
        if (optionTypeSel) { optionTypeSel.disabled = !!isNCBC; if (isNCBC) optionTypeSel.value = ''; }

        if (!ex) {
          instrSel.innerHTML = '<option value="">Select Instrument</option>';
          instrSel.disabled = true;
          return;
        }

        try {
          const instruments = await fetchInstrumentsForExchange(ex);
          console.debug('instruments loaded for', ex, 'count=', (instruments && instruments.length) || 0);
          if (!Array.isArray(instruments) || instruments.length === 0) {
            instrSel.innerHTML = '<option value="">No instruments found</option>';
            instrSel.disabled = true;
          } else {
            populateInstruments(instruments);
            instrSel.disabled = false;
          }
          // if NC/BC, instrument select remains enabled but strike/expiry disabled
        } catch (e) {
          console.debug('Failed to load instruments for exchange', ex, e);
          instrSel.innerHTML = '<option value="">Select Instrument</option>';
          instrSel.disabled = true;
        }
      });

      // debug: add small reload button next to instrument select (only if not present)
      try {
        const instrParent = instrSel ? instrSel.parentNode : null;
        if (instrParent && !document.getElementById('admin-reload-instruments')) {
          const btn = document.createElement('button'); btn.id = 'admin-reload-instruments'; btn.className = 'btn'; btn.style.marginLeft = '6px'; btn.innerText = 'Reload Instruments';
          btn.addEventListener('click', function () {
            const ex = (exSel.value || '').trim(); if (!ex) return alert('Select exchange first'); exSel.dispatchEvent(new Event('change'));
          });
          instrParent.appendChild(btn);
        }
      } catch (e) { console.debug('add reload button failed', e); }

      if (instrSel) {
        instrSel.addEventListener('change', async function () {
          const instrument = (this.value || '').trim();
          const ex = (exSel.value || '').trim();
          if (strikeSel) { strikeSel.innerHTML = '<option value="">Select Strike</option>'; }
          if (expirySel) { expirySel.innerHTML = '<option value="">Select Expiry</option>'; }
          if (!instrument) return;
          // for NC/BC no strikes
          if (ex === 'NC' || ex === 'BC') return;
          try {
            const strikes = await fetchStrikes(ex, instrument);
            if (Array.isArray(strikes) && strikeSel) {
              strikeSel.innerHTML = '<option value="">Select Strike</option>' + strikes.map(s => '<option value="' + escapeHtml(s) + '">' + escapeHtml(s) + '</option>').join('');
              strikeSel.disabled = false;
            }
          } catch (e) { console.debug('fetchStrikes failed', e); }
        });
      }

      // if exchange already selected on page load, trigger load
      try {
        if (exSel.value && exSel.value.trim() !== '') {
          console.debug('exchange preset, triggering change for', exSel.value);
          exSel.dispatchEvent(new Event('change'));
        }
      } catch (e) { console.debug('initial exchange trigger failed', e); }

      if (strikeSel) {
        strikeSel.addEventListener('change', async function () {
          const strike = (this.value || '').trim();
          const instrument = instrSel ? (instrSel.value || '').trim() : '';
          const ex = (exSel.value || '').trim();
          if (!strike) return;
          try {
            const expiries = await fetchExpiries(ex, instrument, strike);
            if (Array.isArray(expiries) && expirySel) {
              expirySel.innerHTML = '<option value="">Select Expiry</option>' + expiries.map(s => '<option value="' + escapeHtml(s) + '">' + escapeHtml(s) + '</option>').join('');
              expirySel.disabled = false;
            }
          } catch (e) { console.debug('fetchExpiries failed', e); }
        });
      }

    } catch (e) { console.debug('wireAdminForm failed', e); }
  }

  // expose functions needed by template / other scripts
  window.loadUsers = loadUsers;
  window.loadRequestedOrdersForUser = loadRequestedOrdersForUser;
  window.loadExecutedForUser = loadExecutedForUser;
  window.loadBrokers = loadBrokers;
  window.selectUser = selectUser;
  window.fetchInstrumentsForExchange = fetchInstrumentsForExchange;
  window.populateInstruments = populateInstruments;
  window.fetchStrikes = fetchStrikes;
  window.fetchExpiries = fetchExpiries;

  // start ws on load
  document.addEventListener('DOMContentLoaded', function(){ ensureCsrf(); loadUsers().catch(function(){}); wireAdminForm(); wireBrokersUI(); startAdminWs(); });

})();
