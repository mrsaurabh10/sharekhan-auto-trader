// admin-dashboard.js - full implementation
// Updated at 2025-01-13T12:55:00Z to show both Option LTP and Spot LTP
(function () {
  'use strict';

  let currentExecPage = 0;
  const execPageSize = 10;

  // --- Utilities ---
  async function ensureCsrf() {
    const meta = document.querySelector('meta[name="_csrf"]');
    if (meta && meta.getAttribute('content')) return;
    try {
      const res = await fetch('/api/auth/csrf-token', { credentials: 'include' });
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
    opts.headers['X-Requested-With'] = opts.headers['X-Requested-With'] || 'XMLHttpRequest';

    try {
      const method = (opts.method || 'GET').toUpperCase();
      if (method !== 'GET') {
        const metaHeader = document.querySelector('meta[name="_csrf_header"]');
        const metaToken = document.querySelector('meta[name="_csrf"]');
        const headerName = metaHeader && metaHeader.getAttribute('content') ? metaHeader.getAttribute('content') : null;
        const token = metaToken && metaToken.getAttribute('content') ? metaToken.getAttribute('content') : null;
        if (headerName && token) {
          if (!opts.headers[headerName]) opts.headers[headerName] = token;
          if (!opts.headers['X-CSRF-TOKEN']) opts.headers['X-CSRF-TOKEN'] = token;
        }
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
    if (res.status === 204) return null;
    const ct = (res.headers && res.headers.get && res.headers.get('content-type')) || '';
    if (ct.toLowerCase().includes('json')) {
      try { return await res.json(); } catch (e) { return {}; }
    }
    const text = await res.text().catch(() => '');
    if (!text) return null;
    try { return JSON.parse(text); } catch (e) { /* not JSON */ }
    return text;
  }

  function escapeHtml(v) { return (v == null) ? '' : String(v).replace(/[&<>'"`]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":"&#39;","`":"&#96;"})[c]); }

  function updatePnlStyle(element, value) {
    if (!element) return;
    const num = Number(value);
    if (!isNaN(num)) {
      element.style.color = num >= 0 ? 'green' : 'red';
      element.style.fontWeight = 'bold';
    } else {
      element.style.color = '';
      element.style.fontWeight = 'normal';
    }
  }

  // --- Instruments / option helpers ---
  async function fetchInstrumentsForExchange(exchange) {
    if (!exchange) return [];
    const tried = [];
    const url1 = '/api/scripts/instruments/' + encodeURIComponent(exchange);
    tried.push(url1);
    try {
      const j = await fetchJson(url1).catch(() => null);
      if (Array.isArray(j)) return j;
      if (j && Array.isArray(j.instruments)) return j.instruments;
    } catch (e) { console.debug('fetchInstruments json attempt1 failed', e); }

    const url2 = '/api/scripts/instruments?exchange=' + encodeURIComponent(exchange);
    tried.push(url2);
    try {
      const j2 = await fetchJson(url2).catch(() => null);
      if (Array.isArray(j2)) return j2;
      if (j2 && Array.isArray(j2.instruments)) return j2.instruments;
    } catch (e) { console.debug('fetchInstruments json attempt2 failed', e); }

    return [];
  }

  function populateInstruments(instruments) {
    const sel = document.getElementById('instrument'); if (!sel) return;
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

  // --- Edit modal ---
  function createEditModalIfNeeded() {
    if (document.getElementById('adminEditModal')) return;
    const modal = document.createElement('div'); modal.id = 'adminEditModal'; modal.style.cssText = 'position:fixed;left:0;top:0;width:100%;height:100%;display:none;align-items:center;justify-content:center;background:rgba(0,0,0,0.35);z-index:9999;';
    modal.innerHTML = '<div style="background:#fff;padding:16px;border-radius:6px;min-width:320px;max-width:520px;">' +
      '<h3 id="adminEditModalTitle">Edit</h3>' +
      '<div style="margin-bottom:8px"><label>Entry Price</label><br/><input id="modal_entryPrice" type="number" step="0.01" style="width:100%"/></div>' +
      '<div style="margin-bottom:8px"><label>Stop Loss</label><br/><input id="modal_stopLoss" type="number" step="0.01" style="width:100%"/></div>' +
      '<div style="margin-bottom:8px"><label>Target1</label><br/><input id="modal_target1" type="number" step="0.01" style="width:100%"/></div>' +
      '<div style="margin-bottom:8px"><label>Target2</label><br/><input id="modal_target2" type="number" step="0.01" style="width:100%"/></div>' +
      '<div style="margin-bottom:8px"><label>Target3</label><br/><input id="modal_target3" type="number" step="0.01" style="width:100%"/></div>' +
      '<div style="margin-bottom:8px"><label>Quantity</label><br/><input id="modal_quantity" type="number" min="1" style="width:100%"/></div>' +
      '<div style="margin-bottom:8px"><label><input id="modal_intraday" type="checkbox"/> Intraday</label></div>' +
      '<div style="margin-bottom:8px"><label><input id="modal_useSpotPrice" type="checkbox"/> Use Spot Price (All)</label></div>' +
      '<div style="margin-bottom:8px"><label><input id="modal_useSpotForEntry" type="checkbox"/> Spot for Entry</label></div>' +
      '<div style="margin-bottom:8px"><label><input id="modal_useSpotForSl" type="checkbox"/> Spot for SL</label></div>' +
      '<div style="margin-bottom:8px"><label><input id="modal_useSpotForTarget" type="checkbox"/> Spot for Target</label></div>' +
      '<div style="text-align:right;margin-top:8px"><button id="modalCancel" class="btn">Cancel</button> <button id="modalSave" class="btn primary">Save</button></div>' +
      '</div>';
    document.body.appendChild(modal);
    document.getElementById('modalCancel').addEventListener('click', function () { modal.style.display = 'none'; });
  }

  function openEditModal(title, values, onSave) {
    createEditModalIfNeeded(); const modal = document.getElementById('adminEditModal'); document.getElementById('adminEditModalTitle').innerText = title || 'Edit';
    document.getElementById('modal_entryPrice').value = values.entryPrice != null ? values.entryPrice : '';
    document.getElementById('modal_stopLoss').value = values.stopLoss != null ? values.stopLoss : '';
    document.getElementById('modal_target1').value = values.target1 != null ? values.target1 : '';
    document.getElementById('modal_target2').value = values.target2 != null ? values.target2 : '';
    document.getElementById('modal_target3').value = values.target3 != null ? values.target3 : '';
    document.getElementById('modal_quantity').value = values.quantity != null ? values.quantity : '';
    document.getElementById('modal_intraday').checked = !!values.intraday;
    document.getElementById('modal_useSpotPrice').checked = !!values.useSpotPrice;
    document.getElementById('modal_useSpotForEntry').checked = !!values.useSpotForEntry;
    document.getElementById('modal_useSpotForSl').checked = !!values.useSpotForSl;
    document.getElementById('modal_useSpotForTarget').checked = !!values.useSpotForTarget;
    modal.style.display = 'flex';
    const saveBtn = document.getElementById('modalSave'); const newSave = saveBtn.cloneNode(true); saveBtn.parentNode.replaceChild(newSave, saveBtn);
    newSave.addEventListener('click', async function () {
      const payload = {};
      const ep = document.getElementById('modal_entryPrice').value.trim();
      const sl = document.getElementById('modal_stopLoss').value.trim();
      const t1 = document.getElementById('modal_target1').value.trim();
      const t2 = document.getElementById('modal_target2').value.trim();
      const t3 = document.getElementById('modal_target3').value.trim();
      const q = document.getElementById('modal_quantity').value.trim();
      const intraday = document.getElementById('modal_intraday').checked;
      const useSpotPrice = document.getElementById('modal_useSpotPrice').checked;
      const useSpotForEntry = document.getElementById('modal_useSpotForEntry').checked;
      const useSpotForSl = document.getElementById('modal_useSpotForSl').checked;
      const useSpotForTarget = document.getElementById('modal_useSpotForTarget').checked;

      if (ep !== '') payload.entryPrice = Number(ep);
      if (sl !== '') payload.stopLoss = Number(sl);
      if (t1 !== '') payload.target1 = Number(t1);
      if (t2 !== '') payload.target2 = Number(t2);
      if (t3 !== '') payload.target3 = Number(t3);
      if (q !== '') payload.quantity = Number(q);
      payload.intraday = intraday;
      payload.useSpotPrice = useSpotPrice;
      payload.useSpotForEntry = useSpotForEntry;
      payload.useSpotForSl = useSpotForSl;
      payload.useSpotForTarget = useSpotForTarget;

      try { await onSave(payload); modal.style.display = 'none'; } catch (e) { alert('Save failed: ' + (e && e.message ? e.message : e)); }
    });
  }

  // --- Loaders ---
  async function loadRequestedOrdersForUser(userId) {
    const uid = userId || window.selectedUserId; const tbody = document.querySelector('#user-requests-table tbody'); if (!tbody) return; tbody.innerHTML = '';
    if (!uid) { tbody.innerHTML = '<tr><td colspan="13">No user selected</td></tr>'; return; }
    try {
      const data = await fetchJson('/api/orders/requests?userId=' + encodeURIComponent(uid));
      if (!Array.isArray(data) || data.length === 0) { tbody.innerHTML = '<tr><td colspan="13">No requests</td></tr>'; return; }
      const seen = new Set(); const uniq = [];
      for (const r of data) { const rid = r && (r.id || r.requestId || r.request_id); if (!rid) { uniq.push(r); continue; } if (seen.has(rid)) continue; seen.add(rid); uniq.push(r); }

      const underlyingMap = { NF: 'NSE', BF: 'BSE', NC: 'NSE', BC: 'BSE' };
      const optionMap = { NF: 'NFO', BF: 'BFO' };
      const keySet = new Set();

      for (const r of uniq) {
        const tr = document.createElement('tr');
        const id = r.id || '';
        const rowScripCode = r.scripCode || r.scrip_code || r.scripCodeStr || r.scrip;
        if (rowScripCode) tr.setAttribute('data-scrip-code', String(rowScripCode));

        const symbol = r.instrument || r.symbol || r.tradingSymbol || '-';
        const exchange = (r.exchange == null || r.exchange === '' || r.exchange === 'null') ? null : r.exchange;
        const strike = r.strikePrice != null ? r.strikePrice : (r.strike || '');
        const optType = r.optionType || r.option_type || '';
        const entry = r.entryPrice != null ? r.entryPrice : (r.entry || '-');
        const sl = r.stopLoss != null ? r.stopLoss : (r.stop_loss || '-');
        const t1 = r.target1 != null ? r.target1 : (r.t1 || '-');
        const qty = r.quantity != null ? r.quantity : (r.qty || '-');
        const status = r.status || r.requestStatus || '-';

        // Action cell
        const actionCell = document.createElement('td');
        const editBtn = document.createElement('button'); editBtn.className = 'btn small'; editBtn.style.marginRight = '6px'; editBtn.innerText = 'Edit';
        editBtn.addEventListener('click', function () {
          openEditModal('Edit Request ' + id, {
              entryPrice: r.entryPrice, intraday: r.intraday, stopLoss: r.stopLoss, target1: r.target1, target2: r.target2, target3: r.target3, quantity: r.quantity,
              useSpotPrice: r.useSpotPrice, useSpotForEntry: r.useSpotForEntry, useSpotForSl: r.useSpotForSl, useSpotForTarget: r.useSpotForTarget
          }, async function (payload) {
            if (Object.keys(payload).length === 0) throw new Error('No changes'); await ensureCsrf(); await fetchJson('/api/trades/request/' + id, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) }); await loadRequestedOrdersForUser(uid);
          });
        }); actionCell.appendChild(editBtn);

        const moveBtn = document.createElement('button'); moveBtn.className = 'btn small'; moveBtn.style.marginRight = '6px'; moveBtn.innerText = 'Move SL to Cost';
        moveBtn.addEventListener('click', async function () { const entryPrice = r.entryPrice != null ? r.entryPrice : (r.entry || null); if (entryPrice == null) { alert('No entry'); return; } await ensureCsrf(); await fetchJson('/api/trades/request/' + id, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ stopLoss: Number(entryPrice) }) }); await loadRequestedOrdersForUser(uid); }); actionCell.appendChild(moveBtn);

        const cancelBtn = document.createElement('button'); cancelBtn.className = 'btn small danger'; cancelBtn.style.marginRight = '6px'; cancelBtn.innerText = 'Cancel';
        cancelBtn.addEventListener('click', async function () { if (!confirm('Cancel request id ' + id + '?')) return; await ensureCsrf(); await fetchJson('/api/trades/cancel-request/' + id + '?userId=' + encodeURIComponent(uid), { method: 'POST' }); await loadRequestedOrdersForUser(uid); }); actionCell.appendChild(cancelBtn);

        if (String(status).toUpperCase() !== 'TRIGGERED' && String(status).toUpperCase() !== 'EXECUTED') {
          const trig = document.createElement('button'); trig.className = 'btn small'; trig.innerText = 'Trigger';
          trig.addEventListener('click', async function () {
            trig.disabled = true;
            try {
              await ensureCsrf();
              const brokerId = await resolveBrokerForUser(uid);
              const url = brokerId ? ('/admin/trigger/' + id + '?brokerCredentialsId=' + encodeURIComponent(brokerId)) : ('/admin/trigger/' + id);
              const res = await fetchJson(url, { method: 'POST' });
              if (res && typeof res === 'object' && String(res.status).toLowerCase() === 'rejected') {
                alert((res.reason ? ('Rejected: ' + res.reason) : (res.message || 'Order rejected')));
                try { prefillPlaceOrderFormFromRequestRow(r); } catch (e) { }
              }
              await loadRequestedOrdersForUser(uid); await loadExecutedForUser(uid);
            } catch (e) { alert('Trigger failed: ' + (e && e.message ? e.message : e)); } finally { trig.disabled = false; }
          });
          actionCell.appendChild(trig);
        }

        tr.innerHTML = '<td>' + escapeHtml(id) + '</td>' +
                       '<td>' + escapeHtml(String(symbol)) + '</td>' +
                       '<td>' + escapeHtml(String(exchange || '-')) + '</td>' +
                       '<td>' + escapeHtml(String(strike || '-')) + '</td>' +
                       '<td>' + escapeHtml(String(optType || '-')) + '</td>' +
                       '<td>' + escapeHtml(String(entry)) + '</td>' +
                       '<td>' + escapeHtml(String(sl)) + '</td>' +
                       '<td>' + escapeHtml(String(t1)) + '</td>' +
                       '<td>' + escapeHtml(String(qty)) + '</td>' +
                       '<td>' + escapeHtml(String(status)) + '</td>';
        tr.appendChild(actionCell);

        // LTP Cells
        const ltpTd = document.createElement('td'); ltpTd.innerText = '-'; tr.appendChild(ltpTd);
        const spotLtpTd = document.createElement('td'); spotLtpTd.innerText = '-'; tr.appendChild(spotLtpTd);

        // Determine Keys
        let spotKey = null;
        let tradedKey = null;
        if (exchange) {
            const ex = String(exchange).toUpperCase();
            spotKey = (underlyingMap[ex] || ex) + ':' + symbol;
            if (strike && strike !== '') {
                const mapped = (optionMap[ex] || ex);
                tradedKey = mapped + ':' + symbol + (r.expiry ? r.expiry : '') + strike + (r.optionType ? r.optionType : '');
            } else {
                // Cash/Spot trade
                tradedKey = spotKey;
            }
        } else {
            // Fallback
            spotKey = 'NSE:' + symbol;
            tradedKey = spotKey;
        }

        if (spotKey) {
            spotLtpTd.setAttribute('data-ltp-key', spotKey);
            keySet.add(spotKey);
        }
        if (tradedKey) {
            ltpTd.setAttribute('data-ltp-key', tradedKey);
            keySet.add(tradedKey);
        }

        tbody.appendChild(tr);
      }

      if (keySet.size > 0) {
        const map = await batchFetchMstockLtp(Array.from(keySet));
        applyLtpMap(map);
      }
    } catch (e) { console.error('Failed to load requests', e); tbody.innerHTML = '<tr><td colspan="13">Error loading requests</td></tr>'; }
  }

  async function loadExecutedForUser(userId, filterStatuses, page) {
    const uid = userId || window.selectedUserId;
    const tbody = document.querySelector('#user-executed-table tbody');
    if (!tbody) return;
    tbody.innerHTML = '';

    if (typeof page === 'number') currentExecPage = page;
    if (!uid) { tbody.innerHTML = '<tr><td colspan="14">No user selected</td></tr>'; updatePaginationUI(null); return; }

    try {
      let url = '/api/orders/executed?userId=' + encodeURIComponent(uid) + '&page=' + currentExecPage + '&size=' + execPageSize;
      if (filterStatuses && filterStatuses.length > 0) filterStatuses.forEach(s => { url += '&status=' + encodeURIComponent(s); });

      const responseData = await fetchJson(url);
      let data = [], pageInfo = null;
      if (responseData && Array.isArray(responseData.content)) {
          data = responseData.content;
          pageInfo = { number: responseData.number, totalPages: responseData.totalPages, first: responseData.first, last: responseData.last };
      } else if (Array.isArray(responseData)) {
          data = responseData;
      }

      if (data.length === 0) { tbody.innerHTML = '<tr><td colspan="14">No executed trades</td></tr>'; updatePaginationUI(pageInfo); return; }

      const seen = new Set(); const uniq = [];
      for (const t of data) { const tid = t && (t.id || t.tradeId); if (!tid) { uniq.push(t); continue; } if (seen.has(tid)) continue; seen.add(tid); uniq.push(t); }

      const underlyingMap = { NF: 'NSE', BF: 'BSE', NC: 'NSE', BC: 'BSE' };
      const optionMap = { NF: 'NFO', BF: 'BFO' };
      const keySet = new Set();

      for (const t of uniq) {
        const tr = document.createElement('tr');
        const id = t.id || '';
        const rowScripCode = t.scripCode || t.scrip_code || t.scrip || t.tradingScripCode;
        if (rowScripCode) tr.setAttribute('data-scrip-code', String(rowScripCode));

        const symbol = t.instrument || t.symbol || t.tradingSymbol || '-';
        const exchange = (t.exchange == null || t.exchange === '' || t.exchange === 'null') ? null : t.exchange;
        const strike = t.strikePrice != null ? t.strikePrice : (t.strike || '');
        const optType = t.optionType || t.option_type || '';
        const entry = t.entryPrice != null ? t.entryPrice : (t.entry || '-');
        const sl = t.stopLoss != null ? t.stopLoss : (t.stop_loss || '-');
        const t1 = t.target1 != null ? t.target1 : (t.t1 || '-');
        const qty = t.quantity != null ? t.quantity : (t.qty || '-');
        const status = t.status || '-';
        const statusUpper = String(status).toUpperCase();
        const pnlEntry = t.actualEntryPrice != null ? t.actualEntryPrice : (t.entryPrice != null ? t.entryPrice : (t.entry || null));

        const actionCell = document.createElement('td');
        const forbidden = new Set(['REJECTED', 'EXITED_SUCCESS', 'EXITED_FAILURE', 'EXITED']);
        if (!forbidden.has(statusUpper)) {
          const editBtn = document.createElement('button'); editBtn.className = 'btn small'; editBtn.innerText = 'Edit';
          editBtn.addEventListener('click', function () { openEditModal('Edit Trade ' + id, { entryPrice: t.entryPrice, intraday: t.intraday, stopLoss: t.stopLoss, target1: t.target1, target2: t.target2, target3: t.target3, quantity: t.quantity, useSpotPrice: t.useSpotPrice, useSpotForEntry: t.useSpotForEntry, useSpotForSl: t.useSpotForSl, useSpotForTarget: t.useSpotForTarget }, async function (payload) { if (Object.keys(payload).length === 0) throw new Error('No changes'); if (window.selectedUserId) payload.userId = window.selectedUserId; await ensureCsrf(); await fetchJson('/api/trades/execution/' + id, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) }); await loadExecutedForUser(uid, getSelectedStatuses()); }); }); actionCell.appendChild(editBtn);
          const moveBtn = document.createElement('button'); moveBtn.className = 'btn small'; moveBtn.style.marginLeft = '4px'; moveBtn.innerText = 'Move SL to Cost';
          moveBtn.addEventListener('click', async function () { await ensureCsrf(); try { await fetchJson('/api/trades/move-sl-to-cost/' + id, { method: 'POST' }); setTimeout(function () { try { loadExecutedForUser(uid, getSelectedStatuses()); } catch (e) { } }, 800); } catch(e) { alert('Failed to move SL to cost: ' + (e && e.message ? e.message : e)); } }); actionCell.appendChild(moveBtn);
          const modifyExitBtn = document.createElement('button'); modifyExitBtn.className = 'btn small'; modifyExitBtn.style.marginLeft = '4px'; modifyExitBtn.innerText = 'Modify Exit';
          modifyExitBtn.addEventListener('click', async function () {
            const price = prompt('Enter new exit price for trade ' + id + ':');
            if (price === null) return;
            const trimmed = String(price).trim();
            if (!trimmed) { alert('Price is required.'); return; }
            const parsed = Number(trimmed);
            if (!isFinite(parsed) || parsed <= 0) { alert('Please enter a valid positive number.'); return; }
            await ensureCsrf();
            try {
              await fetchJson('/api/trades/exit-order/' + id + '/modify', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ price: parsed })
              });
              setTimeout(function () { try { loadExecutedForUser(uid, getSelectedStatuses()); } catch (e) { } }, 800);
            } catch (e) {
              alert('Failed to modify exit order: ' + (e && e.message ? e.message : e));
            }
          }); actionCell.appendChild(modifyExitBtn);
          const closeBtn = document.createElement('button'); closeBtn.className = 'btn small danger'; closeBtn.style.marginLeft = '4px'; closeBtn.innerText = 'Close';
          closeBtn.addEventListener('click', async function () { const price = prompt('Enter price to close trade ' + id + ' (optional):'); if (price === null) return; await ensureCsrf(); let url = '/api/trades/square-off/' + id; if (price && price.trim() !== '') url += '?price=' + encodeURIComponent(price.trim()); await fetchJson(url, { method: 'POST' }); setTimeout(function () { try { loadExecutedForUser(uid, getSelectedStatuses()); } catch (e) { } }, 800); }); actionCell.appendChild(closeBtn);
        } else { actionCell.innerText = '-'; }

        tr.innerHTML = '<td>' + escapeHtml(id) + '</td>' +
                       '<td>' + escapeHtml(String(symbol)) + '</td>' +
                       '<td>' + escapeHtml(String(exchange || '-')) + '</td>' +
                       '<td>' + escapeHtml(String(strike || '-')) + '</td>' +
                       '<td>' + escapeHtml(String(optType || '-')) + '</td>' +
                       '<td>' + escapeHtml(String(entry)) + '</td>' +
                       '<td>' + escapeHtml(String(sl)) + '</td>' +
                       '<td>' + escapeHtml(String(t1)) + '</td>' +
                       '<td>' + escapeHtml(String(qty)) + '</td>' +
                       '<td>' + escapeHtml(String(status)) + '</td>';
        tr.appendChild(actionCell);

        const ltpTd = document.createElement('td'); ltpTd.innerText = '-'; tr.appendChild(ltpTd);
        const spotLtpTd = document.createElement('td'); spotLtpTd.innerText = '-'; tr.appendChild(spotLtpTd);
        const pnlTd = document.createElement('td'); pnlTd.innerText = '-'; tr.appendChild(pnlTd);

        // Store data for PNL
        try { tr.setAttribute('data-entry', (pnlEntry != null && pnlEntry !== '-' ? String(pnlEntry) : '')); } catch (e) {}
        try { tr.setAttribute('data-qty', (qty != null && qty !== '-' ? String(qty) : '')); } catch (e) {}
        try { tr.setAttribute('data-status', statusUpper); } catch (e) {}

        // Final PNL logic
        let finalPnl = (t.finalPnl != null ? t.finalPnl : (t.realizedPnl != null ? t.realizedPnl : (t.pnl != null ? t.pnl : null)));
        if (statusUpper === 'EXITED_SUCCESS' || (statusUpper !== 'EXECUTED' && statusUpper !== 'EXIT_ORDER_PLACED' && finalPnl != null)) {
             if (finalPnl != null && !isNaN(finalPnl)) {
                 pnlTd.innerText = Number(finalPnl).toFixed(2);
                 updatePnlStyle(pnlTd, finalPnl);
             } else if (finalPnl != null) {
                 pnlTd.innerText = String(finalPnl);
             }
        }

        // Determine Keys
        let spotKey = null;
        let tradedKey = null;
        if (exchange) {
            const ex = String(exchange).toUpperCase();
            spotKey = (underlyingMap[ex] || ex) + ':' + symbol;
            if (strike && strike !== '') {
                const mapped = (optionMap[ex] || ex);
                tradedKey = mapped + ':' + symbol + (t.expiry ? t.expiry : '') + strike + (t.optionType ? t.optionType : '');
            } else {
                tradedKey = spotKey;
            }
        } else {
            spotKey = 'NSE:' + symbol;
            tradedKey = spotKey;
        }

        if (spotKey) {
            spotLtpTd.setAttribute('data-ltp-key', spotKey);
            keySet.add(spotKey);
        }
        if (tradedKey) {
            ltpTd.setAttribute('data-ltp-key', tradedKey);
            // PNL always uses traded key
            pnlTd.setAttribute('data-pnl-key', tradedKey);
            keySet.add(tradedKey);
        }

        tbody.appendChild(tr);
      }

      if (keySet.size > 0) {
        const map = await batchFetchMstockLtp(Array.from(keySet));
        applyLtpMap(map);
      }
      updatePaginationUI(pageInfo);
    } catch (e) { console.error('Failed to load executed trades', e); tbody.innerHTML = '<tr><td colspan="14">Error loading executed trades</td></tr>'; updatePaginationUI(null); }
  }

  function updatePaginationUI(pageInfo) {
      const prevBtn = document.getElementById('exec-prev-btn');
      const nextBtn = document.getElementById('exec-next-btn');
      const infoSpan = document.getElementById('exec-page-info');
      if (!prevBtn || !nextBtn || !infoSpan) return;
      if (!pageInfo) { prevBtn.disabled = true; nextBtn.disabled = true; infoSpan.innerText = ''; return; }
      prevBtn.disabled = pageInfo.first;
      nextBtn.disabled = pageInfo.last;
      infoSpan.innerText = 'Page ' + (pageInfo.number + 1) + ' of ' + pageInfo.totalPages;
  }

  function wireExecutedPagination() {
      const prevBtn = document.getElementById('exec-prev-btn');
      const nextBtn = document.getElementById('exec-next-btn');
      if (prevBtn) prevBtn.addEventListener('click', function() { if (currentExecPage > 0) loadExecutedForUser(window.selectedUserId, getSelectedStatuses(), currentExecPage - 1); });
      if (nextBtn) nextBtn.addEventListener('click', function() { loadExecutedForUser(window.selectedUserId, getSelectedStatuses(), currentExecPage + 1); });
  }

  function getSelectedStatuses() {
    const sel = document.getElementById('statusFilter');
    if (!sel) return [];
    return Array.from(sel.selectedOptions).map(o => o.value);
  }

  function wireStatusFilter() {
    const btn = document.getElementById('applyStatusFilterBtn');
    if (btn) btn.addEventListener('click', function() { if (window.selectedUserId) loadExecutedForUser(window.selectedUserId, getSelectedStatuses(), 0); });
  }

  // --- LTP helpers ---
  let adminWs = null;
  let pollHandle = null;
  let ltpCache = {};

  async function batchFetchMstockLtp(keys) {
    const out = {};
    if (!Array.isArray(keys) || keys.length === 0) return out;
    for (const k of keys) {
      if (ltpCache[k] && ltpCache[k].last_price != null) out[k] = ltpCache[k];
    }
    return out;
  }

  async function getLtpFromCacheOrFallback(key) {
    try {
      if (!key) return null;
      if (ltpCache && ltpCache[key] && ltpCache[key].last_price != null) return Number(ltpCache[key].last_price);
      return null;
    } catch (e) { console.debug('getLtpFromCacheOrFallback failed', e); }
    return null;
  }

  function fetchMStockLtpForKey(qualifiedKey) {
      if (!qualifiedKey) return Promise.resolve(null);
      const url = '/api/mstock/ltp?i=' + encodeURIComponent(qualifiedKey);
      return fetchJson(url).then(json => {
          if (!json || json.status !== 'success' || !json.data) return null;
          const entry = json.data[qualifiedKey];
          return entry ? Number(entry.last_price) : null;
      }).catch(err => null);
  }

  function applyLtpMap(map) {
    try {
      if (!map || typeof map !== 'object') return;
      Object.assign(ltpCache, map || {});

      // Update elements bound by key
      document.querySelectorAll('[data-ltp-key]').forEach(function(el) {
        const key = el.getAttribute('data-ltp-key');
        if (key && ltpCache[key] && ltpCache[key].last_price != null) {
            const val = Number(ltpCache[key].last_price).toFixed(2);
            if (el.innerText !== val) el.innerText = val;
        }
      });

      // Update PNL elements bound by key
      document.querySelectorAll('[data-pnl-key]').forEach(function(el) {
        const key = el.getAttribute('data-pnl-key');
        if (key && ltpCache[key] && ltpCache[key].last_price != null) {
            const ltpNum = Number(ltpCache[key].last_price);
            const tr = el.closest('tr');
            if (tr) {
                const status = (tr.getAttribute('data-status') || '').toUpperCase();
                if (status === 'EXECUTED' || status === 'EXIT_ORDER_PLACED') {
                    const entry = parseFloat(tr.getAttribute('data-entry') || '');
                    const qty = parseFloat(tr.getAttribute('data-qty') || '');
                    if (!Number.isNaN(entry) && !Number.isNaN(qty) && !Number.isNaN(ltpNum)) {
                        const pnl = qty * (ltpNum - entry);
                        el.innerText = Number(pnl).toFixed(2);
                        updatePnlStyle(el, pnl);
                    }
                }
            }
        }
      });
    } catch (e) { console.debug('applyLtpMap failed', e); }
  }

  function startAdminWs() {
    try {
      if (adminWs && adminWs.readyState === WebSocket.OPEN) return;
      const proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
      const url = proto + location.host + '/ws/ltp';
      adminWs = new WebSocket(url);

      adminWs.onopen = function() { console.debug('admin LTP ws open'); if (pollHandle) { clearTimeout(pollHandle); pollHandle = null; } };
      adminWs.onmessage = function(ev) {
        try {
          const js = JSON.parse(ev.data);
          if (!js) return;

          // ScripCode update
          if (js.scripCode != null && (js.ltp != null || js.last_price != null)) {
            const sc = String(js.scripCode);
            const lv = (js.ltp != null) ? js.ltp : js.last_price;
            ltpCache[sc] = { last_price: lv }; // Cache by scripCode too if needed

            document.querySelectorAll('tr[data-scrip-code="' + sc + '"]').forEach(function(tr) {
                const ltpNum = Number(lv);
                if (Number.isNaN(ltpNum)) return;
                const val = ltpNum.toFixed(2);

                // Update PNL (always uses traded instrument scrip code)
                const pnlTd = tr.querySelector('[data-pnl-key]');
                if (pnlTd) {
                    const status = (tr.getAttribute('data-status') || '').toUpperCase();
                    if (status === 'EXECUTED' || status === 'EXIT_ORDER_PLACED') {
                        const entry = parseFloat(tr.getAttribute('data-entry') || '');
                        const qty = parseFloat(tr.getAttribute('data-qty') || '');
                        if (!Number.isNaN(entry) && !Number.isNaN(qty)) {
                            const pnl = qty * (ltpNum - entry);
                            pnlTd.innerText = Number(pnl).toFixed(2);
                            updatePnlStyle(pnlTd, pnl);
                        }
                    }
                }

                // Update LTP cell (Option LTP)
                const ltpTd = tr.querySelector('td[data-ltp-key]');
                if (ltpTd) {
                    ltpTd.innerText = val;
                }
            });
            // Removed return to allow processing of 'i' key if present
          }

          if (js.i && js.last_price != null) { const m = {}; m[js.i] = { last_price: js.last_price }; applyLtpMap(m); return; }
          if (js.data && typeof js.data === 'object') { applyLtpMap(js.data); return; }
          if (Array.isArray(js)) { const m = {}; js.forEach(function(it) { if (it && it.i && it.last_price != null) m[it.i] = { last_price: it.last_price }; }); applyLtpMap(m); return; }

        } catch (e) { console.debug('adminWs parse failed', e); }
      };
      adminWs.onclose = function() { console.debug('admin LTP ws closed'); adminWs = null; if (!pollHandle) pollHandle = setTimeout(function() { pollHandle = null; startAdminWs(); }, 2000); };
      adminWs.onerror = function(e) { console.debug('admin LTP ws error', e); if (adminWs) try { adminWs.close(); } catch (ign) {} };
    } catch (e) { console.debug('startAdminWs failed', e); if (!pollHandle) pollHandle = setTimeout(function() { pollHandle = null; startAdminWs(); }, 3000); }
  }

  // --- Helper functions for instrument/strike/expiry/broker/user loading ---
  // (These remain largely same as before, just ensuring they are present)

  // ... (Broker loading, User loading, Place Order form wiring - kept same as previous version but included in full file write) ...

  async function loadUsers() {
    const container = document.getElementById('usersContainer'); if (!container) return; container.innerText = 'Loading users...';
    try {
      let users = null; try { users = await fetchJson('/admin/app-users'); } catch (e) { }
      if (!Array.isArray(users) || users.length === 0) { try { users = await fetchJson('/admin/users'); } catch (e) { } }
      if (!Array.isArray(users)) users = [];
      if (users.length === 0) { container.innerText = 'No users'; return; }
      const ul = document.createElement('ul'); ul.style.padding = '0';
      users.forEach(function(u){
        const li = document.createElement('li'); li.innerText = (u.username || ('user-' + u.id)); li.style.padding = '6px'; li.style.cursor = 'pointer';
        li.addEventListener('click', function(){ selectUser(u.id, li, u.customerId, u.username); });
        ul.appendChild(li);
      });
      container.innerHTML = ''; container.appendChild(ul);
      const first = ul.querySelector('li'); if (first) first.click();
    } catch (e) { container.innerText = 'Error loading users: ' + (e && e.message ? e.message : e); }
  }

  async function loadBrokers(userId) {
    const tbody = document.querySelector('#brokersTable tbody'); if (!tbody) return; tbody.innerHTML = '';
    try {
      const data = await fetchJson('/admin/app-users/' + encodeURIComponent(userId) + '/brokers');
      if (!Array.isArray(data) || data.length === 0) { tbody.innerHTML = '<tr><td colspan="7">No brokers configured</td></tr>'; return; }
      data.forEach(function (b) {
        const tr = document.createElement('tr'); tr.setAttribute('data-broker-id', b.id);
        tr.innerHTML = '<td>' + escapeHtml(b.id || '') + '</td><td>' + escapeHtml(b.brokerName || '') + '</td><td>' + escapeHtml(b.customerId || '') + '</td><td>' + escapeHtml(b.clientCode || '') + '</td><td>' + (b.hasApiKey ? 'Yes' : 'No') + '</td><td>' + (b.active ? 'Yes' : 'No') + '</td><td><button class="btn" data-action="edit">Edit</button> <button class="btn danger" data-action="delete">Delete</button></td>';
        tr.querySelector('[data-action="edit"]').addEventListener('click', function(){ openBrokerEditor(b); });
        tr.querySelector('[data-action="delete"]').addEventListener('click', function(){ deleteBroker(b.id); });
        tbody.appendChild(tr);
      });
    } catch (e) { tbody.innerHTML = '<tr><td colspan="7">Error loading brokers</td></tr>'; }
  }

  function showBrokersSectionFor(userId, userName) {
    const sec = document.getElementById('brokersSection'); const lbl = document.getElementById('selectedUserName');
    if (lbl) lbl.innerText = String(userName != null ? userName : userId);
    if (sec) sec.style.display = 'block';
  }

  function resetAddBrokerForm() { ['b_brokerName','b_customerId','b_apiKey','b_brokerUser','b_brokerPw','b_clientCode','b_totp','b_secret'].forEach(function(id){ const el = document.getElementById(id); if (el) el.value = ''; }); const chk = document.getElementById('b_active'); if (chk) chk.checked = true; }
  function openAddBrokerForm() { resetAddBrokerForm(); const f = document.getElementById('addBrokerForm'); if (f) f.style.display = 'block'; }
  function closeAddBrokerForm() { const f = document.getElementById('addBrokerForm'); if (f) f.style.display = 'none'; }

  async function openBrokerEditor(b) {
    const editor = document.getElementById('brokerEditor'); if (!editor) return;
    const setVal = (id, val) => { const el = document.getElementById(id); if (el) el.value = (val == null ? '' : val); };
    try {
      document.getElementById('editBrokerIdLbl').innerText = '#' + b.id; editor.setAttribute('data-edit-id', b.id); editor.style.display = 'block'; closeAddBrokerForm();
      const full = await fetchJson('/admin/brokers/' + encodeURIComponent(b.id)).catch(() => null);
      const data = full || b || {};
      setVal('e_brokerName', data.brokerName); setVal('e_customerId', data.customerId); setVal('e_apiKey', data.apiKey); setVal('e_brokerUser', data.brokerUsername); setVal('e_brokerPw', data.brokerPassword); setVal('e_clientCode', data.clientCode); setVal('e_totp', data.totpSecret); setVal('e_secret', data.secretKey);
      const chk = document.getElementById('e_active'); if (chk) chk.checked = !!data.active;
    } catch (e) { setVal('e_brokerName', b && b.brokerName); setVal('e_customerId', b && b.customerId); const chk = document.getElementById('e_active'); if (chk) chk.checked = !!(b && b.active); }
  }
  function closeBrokerEditor() { const editor = document.getElementById('brokerEditor'); if (editor) { editor.style.display = 'none'; editor.removeAttribute('data-edit-id'); } }

  async function saveEditedBroker() {
    const editor = document.getElementById('brokerEditor'); if (!editor) return;
    const id = editor.getAttribute('data-edit-id'); if (!id) return;
    const getVal = id => { const el = document.getElementById(id); return el ? el.value : null; };
    const toNullIfEmpty = v => (v === '' || v === undefined) ? null : v;
    let customerIdRaw = getVal('e_customerId');
    const body = { brokerName: toNullIfEmpty(getVal('e_brokerName')), customerId: toNullIfEmpty(customerIdRaw), apiKey: toNullIfEmpty(getVal('e_apiKey')), brokerUsername: toNullIfEmpty(getVal('e_brokerUser')), brokerPassword: toNullIfEmpty(getVal('e_brokerPw')), clientCode: toNullIfEmpty(getVal('e_clientCode')), totpSecret: toNullIfEmpty(getVal('e_totp')), secretKey: toNullIfEmpty(getVal('e_secret')), active: (document.getElementById('e_active')||{}).checked };
    if (body.customerId != null) { const n = Number(body.customerId); body.customerId = Number.isNaN(n) ? null : n; }
    try { await ensureCsrf(); await fetchJson('/admin/brokers/' + encodeURIComponent(id), { method: 'PUT', body: JSON.stringify(body) }); closeBrokerEditor(); await loadBrokers(window.selectedUserId); } catch (e) { alert('Failed to save broker: ' + (e && e.message ? e.message : e)); }
  }

  async function deleteBroker(id) { if (!id) return; if (!confirm('Delete broker #' + id + '?')) return; try { await ensureCsrf(); await fetch('/admin/brokers/' + encodeURIComponent(id), { method: 'DELETE', credentials: 'include', headers: { 'X-Requested-With': 'XMLHttpRequest' } }); await loadBrokers(window.selectedUserId); } catch (e) { alert('Failed to delete broker: ' + (e && e.message ? e.message : e)); } }

  async function submitAddBroker() {
    const userId = window.selectedUserId; if (!userId) { alert('Select a user first'); return; }
    const body = { brokerName: (document.getElementById('b_brokerName')||{}).value || null, customerId: (document.getElementById('b_customerId')||{}).value || null, apiKey: (document.getElementById('b_apiKey')||{}).value || null, brokerUsername: (document.getElementById('b_brokerUser')||{}).value || null, brokerPassword: (document.getElementById('b_brokerPw')||{}).value || null, clientCode: (document.getElementById('b_clientCode')||{}).value || null, totpSecret: (document.getElementById('b_totp')||{}).value || null, secretKey: (document.getElementById('b_secret')||{}).value || null, active: (document.getElementById('b_active')||{}).checked };
    if (!body.brokerName) { alert('Broker Name is required'); return; }
    try { await ensureCsrf(); await fetchJson('/admin/app-users/' + encodeURIComponent(userId) + '/brokers', { method: 'POST', body: JSON.stringify(body) }); closeAddBrokerForm(); await loadBrokers(userId); } catch (e) { alert('Failed to add broker: ' + (e && e.message ? e.message : e)); }
  }

  function wireBrokersUI() {
    const btnRefresh = document.getElementById('refreshBrokersBtn'); if (btnRefresh) btnRefresh.addEventListener('click', function(){ if (window.selectedUserId) loadBrokers(window.selectedUserId); });
    const btnShowAdd = document.getElementById('showAddBrokerBtn'); if (btnShowAdd) btnShowAdd.addEventListener('click', function(){ openAddBrokerForm(); });
    const btnAddSubmit = document.getElementById('addBrokerSubmit'); if (btnAddSubmit) btnAddSubmit.addEventListener('click', function(){ submitAddBroker(); });
    const btnAddCancel = document.getElementById('addBrokerCancel'); if (btnAddCancel) btnAddCancel.addEventListener('click', function(){ closeAddBrokerForm(); });
    const btnSaveEdit = document.getElementById('saveBrokerBtn'); if (btnSaveEdit) btnSaveEdit.addEventListener('click', function(){ saveEditedBroker(); });
    const btnCancelEdit = document.getElementById('cancelEditBrokerBtn'); if (btnCancelEdit) btnCancelEdit.addEventListener('click', function(){ closeBrokerEditor(); });
  }

  function selectUser(appUserId, liElem, customerId, username) {
    window.selectedUserId = appUserId; window.selectedCustomerId = (customerId == null || customerId === 'null' || customerId === '') ? appUserId : customerId; window.selectedUserName = username || (typeof window.selectedCustomerId !== 'undefined' ? String(window.selectedCustomerId) : String(appUserId));
    const lbl = document.getElementById('placeOrderSelectedUser'); if (lbl) lbl.innerText = window.selectedUserName;
    const uc = document.getElementById('userContent'); if (uc) uc.style.display = 'block';
    const parent = document.getElementById('usersContainer'); if (parent) parent.querySelectorAll('li').forEach(function(li){ li.classList && li.classList.remove('active'); }); if (liElem && liElem.classList) liElem.classList.add('active');
    showBrokersSectionFor(appUserId, window.selectedUserName);
    loadRequestedOrdersForUser(appUserId).catch(function(){}); loadExecutedForUser(appUserId, getSelectedStatuses(), 0).catch(function(){}); loadBrokers(appUserId).catch(function(){});
  }

  function wireAdminForm() {
    const exSel = document.getElementById('exchange'); const instrSel = document.getElementById('instrument'); const strikeSel = document.getElementById('strikePrice'); const expirySel = document.getElementById('expiry'); const optionTypeSel = document.getElementById('optionType');
    if (!exSel) return;
    exSel.addEventListener('change', async function () {
      const ex = (this.value || '').trim();
      if (!instrSel) return; instrSel.disabled = true; instrSel.innerHTML = '<option value="">Loading...</option>';
      const isNCBC = (ex === 'NC' || ex === 'BC');
      if (strikeSel) { strikeSel.disabled = !!isNCBC; strikeSel.innerHTML = '<option value="">Select Strike</option>'; }
      if (expirySel) { expirySel.disabled = !!isNCBC; expirySel.innerHTML = '<option value="">Select Expiry</option>'; }
      if (optionTypeSel) { optionTypeSel.disabled = !!isNCBC; if (isNCBC) optionTypeSel.value = ''; }
      if (!ex) { instrSel.innerHTML = '<option value="">Select Instrument</option>'; instrSel.disabled = true; return; }
      try { const instruments = await fetchInstrumentsForExchange(ex); populateInstruments(instruments); instrSel.disabled = false; } catch (e) { instrSel.innerHTML = '<option value="">Select Instrument</option>'; instrSel.disabled = true; }
    });
    try {
        const cell = document.getElementById('instrumentCell');
        if (cell) {
          let btn = document.getElementById('reloadInstrumentsBtn');
          if (!btn) { btn = document.createElement('button'); btn.id = 'reloadInstrumentsBtn'; btn.className = 'btn'; btn.type = 'button'; btn.textContent = 'Reload'; try { cell.appendChild(btn); } catch (e) {} }
          if (btn && !btn.__wired) { btn.__wired = true; btn.addEventListener('click', async function () { const ex = (exSel.value || '').trim(); if (!ex) { alert('Select exchange first'); return; } try { const instruments = await fetchInstrumentsForExchange(ex); populateInstruments(instruments || []); } catch (e) { } }); }
        }
    } catch (e) {}
    if (instrSel) {
      instrSel.addEventListener('change', async function () {
        const instrument = (this.value || '').trim(); const ex = (exSel.value || '').trim();
        if (strikeSel) strikeSel.innerHTML = '<option value="">Select Strike</option>'; if (expirySel) expirySel.innerHTML = '<option value="">Select Expiry</option>';
        if (!instrument) return;
        const isNCBC = (ex === 'NC' || ex === 'BC');
        if (isNCBC) {
            const underlyingExchangeMap = { 'NF': 'NSE', 'BF': 'BSE', 'NC': 'NSE', 'BC': 'BSE' }; const mappedExchange = underlyingExchangeMap[ex] || ex; const qualified = mappedExchange + ':' + instrument;
            fetchMStockLtpForKey(qualified).then(ltp => { if (ltp != null) document.getElementById('entryPrice').value = ltp; }).catch(err => {});
            return;
        }
        try { const strikes = await fetchStrikes(ex, instrument); if (Array.isArray(strikes) && strikeSel) { strikeSel.innerHTML = '<option value="">Select Strike</option>' + strikes.map(s => '<option value="' + escapeHtml(s) + '">' + escapeHtml(s) + '</option>').join(''); strikeSel.disabled = false; fetchUnderlyingAndSelectNearestStrike(ex, instrument, strikes); } } catch (e) { }
      });
    }
    if (strikeSel) { strikeSel.addEventListener('change', async function () { const strike = (this.value || '').trim(); const instrument = instrSel ? (instrSel.value || '').trim() : ''; const ex = (exSel.value || '').trim(); if (!strike) return; try { const expiries = await fetchExpiries(ex, instrument, strike); if (Array.isArray(expiries) && expirySel) { expirySel.innerHTML = '<option value="">Select Expiry</option>' + expiries.map(s => '<option value="' + escapeHtml(s) + '">' + escapeHtml(s) + '</option>').join(''); expirySel.disabled = false; fetchOptionLtpAndPopulateEntry(); } } catch (e) { } }); }
    if (expirySel) expirySel.addEventListener('change', function () { fetchOptionLtpAndPopulateEntry(); });
    if (optionTypeSel) optionTypeSel.addEventListener('change', function () { fetchOptionLtpAndPopulateEntry(); });
  }

  // --- Place Order wiring ---
  async function resolveBrokerForUser(userId) {
    try {
      const list = await fetchJson('/admin/app-users/' + encodeURIComponent(userId) + '/brokers').catch(() => null);
      if (!Array.isArray(list) || list.length === 0) return null;
      const activeSk = list.find(b => b && b.brokerName && String(b.brokerName).toLowerCase() === 'sharekhan' && b.active); if (activeSk) return activeSk.id;
      const activeAny = list.find(b => b && b.active); if (activeAny) return activeAny.id;
      const anySk = list.find(b => b && b.brokerName && String(b.brokerName).toLowerCase() === 'sharekhan'); if (anySk) return anySk.id;
      return list[0].id || null;
    } catch (e) { return null; }
  }

  function wirePlaceOrderForm() {
    const form = document.getElementById('adminPlaceOrderForm'); const resultDiv = document.getElementById('result'); const errDiv = document.getElementById('serverError'); const btn = document.getElementById('adminPlaceOrderBtn'); const resetBtn = document.getElementById('adminPlaceOrderReset');
    const triggerAllBtn = document.getElementById('triggerForAllUsersBtn');
    if (!form) return;
    function readVal(id) { const el = document.getElementById(id); return el ? el.value : null; }
    function readNum(id) { const v = readVal(id); if (v == null || v === '') return null; const n = Number(v); return (Number.isNaN(n) ? null : n); }
    async function buildPayload(userIdOverride) {
      const ex = readVal('exchange'); const instrument = readVal('instrument'); const strikeStr = readVal('strikePrice'); const expiry = readVal('expiry'); const optionType = readVal('optionType'); const isNCBC = (ex === 'NC' || ex === 'BC');
      const payload = { exchange: ex || null, instrument: instrument || null, strikePrice: isNCBC ? null : (strikeStr ? Number(strikeStr) : null), expiry: isNCBC ? null : (expiry || null), optionType: isNCBC ? null : (optionType || null), entryPrice: readNum('entryPrice'), stopLoss: readNum('stopLoss'), target1: readNum('target1'), target2: readNum('target2'), target3: readNum('target3'), quantity: readNum('quantity'), intraday: !!(document.getElementById('intraday') && document.getElementById('intraday').checked), tslEnabled: !!(document.getElementById('tslEnabled') && document.getElementById('tslEnabled').checked), useSpotPrice: !!(document.getElementById('useSpotPrice') && document.getElementById('useSpotPrice').checked), useSpotForEntry: !!(document.getElementById('useSpotForEntry') && document.getElementById('useSpotForEntry').checked), useSpotForSl: !!(document.getElementById('useSpotForSl') && document.getElementById('useSpotForSl').checked), useSpotForTarget: !!(document.getElementById('useSpotForTarget') && document.getElementById('useSpotForTarget').checked), spotScripCode: readNum('spotScripCode'), trailingSl: null, userId: userIdOverride || window.selectedUserId || null, brokerCredentialsId: null, source: 'admin-ui' };
      if (payload.userId) payload.brokerCredentialsId = await resolveBrokerForUser(payload.userId);
      return payload;
    }

    if (triggerAllBtn) {
        triggerAllBtn.addEventListener('click', async function(e) {
            e.preventDefault();
            if (errDiv) { errDiv.style.display = 'none'; errDiv.innerText = ''; } if (resultDiv) { resultDiv.innerText = ''; }
            try {
                if (triggerAllBtn) triggerAllBtn.disabled = true; await ensureCsrf(); const body = await buildPayload(null);
                if (!body.exchange) throw new Error('Exchange is required'); if (!body.instrument) throw new Error('Instrument is required'); if (body.entryPrice == null) throw new Error('Entry Price is required'); if (body.stopLoss == null) throw new Error('Stop Loss is required');
                const resp = await fetchJson('/admin/trigger-all', { method: 'POST', body: JSON.stringify(body) });
                if (resultDiv) resultDiv.innerText = 'Triggered for all users successfully.';
            } catch (err) { const msg = (err && err.message) ? err.message : String(err); if (errDiv) { errDiv.style.display = 'block'; errDiv.innerText = msg; } else alert('Trigger All failed: ' + msg); } finally { if (triggerAllBtn) triggerAllBtn.disabled = false; }
        });
    }

    form.addEventListener('submit', async function (e) {
      e.preventDefault(); if (errDiv) { errDiv.style.display = 'none'; errDiv.innerText = ''; } if (resultDiv) { resultDiv.innerText = ''; }
      try {
        if (btn) btn.disabled = true; await ensureCsrf(); const body = await buildPayload(window.selectedUserId);
        if (!body.exchange) throw new Error('Exchange is required'); if (!body.instrument) throw new Error('Instrument is required'); if (body.entryPrice == null) throw new Error('Entry Price is required'); if (body.stopLoss == null) throw new Error('Stop Loss is required');
        const alreadyExecuted = document.getElementById('alreadyExecuted') && document.getElementById('alreadyExecuted').checked;
        const url = alreadyExecuted ? '/api/trades/manual-execute' : '/api/trades/trigger-on-price';
        const resp = await fetchJson(url, { method: 'POST', body: JSON.stringify(body) });
        if (alreadyExecuted) { if (resultDiv) resultDiv.innerText = 'Executed trade added: ' + (resp && resp.id ? ('Trade #' + resp.id) : 'OK'); } else { if (resultDiv) resultDiv.innerText = 'Order placed: ' + (resp && resp.id ? ('Request #' + resp.id) : 'OK'); }
        if (window.selectedUserId) { await loadRequestedOrdersForUser(window.selectedUserId); await loadExecutedForUser(window.selectedUserId, getSelectedStatuses()); }
      } catch (err) { const msg = (err && err.message) ? err.message : String(err); if (errDiv) { errDiv.style.display = 'block'; errDiv.innerText = msg; } else alert('Place Order failed: ' + msg); } finally { if (btn) btn.disabled = false; }
    });
    if (resetBtn) {
      resetBtn.addEventListener('click', function () {
        ['exchange','instrument','strikePrice','expiry','entryPrice','stopLoss','target1','target2','target3','spotScripCode'].forEach(function(id){ const el = document.getElementById(id); if (el) { if (el.tagName === 'SELECT') el.selectedIndex = 0; else el.value=''; } });
        const opt = document.getElementById('optionType'); if (opt) { opt.value = 'CE'; if (opt.value !== 'CE' && opt.options.length > 0) opt.selectedIndex = 0; }
        const qty = document.getElementById('quantity'); if (qty) qty.value = '1';
        const intr = document.getElementById('intraday'); if (intr) intr.checked = false; const ae = document.getElementById('alreadyExecuted'); if (ae) ae.checked = false; const tsl = document.getElementById('tslEnabled'); if (tsl) tsl.checked = false; const usp = document.getElementById('useSpotPrice'); if (usp) usp.checked = false; const use = document.getElementById('useSpotForEntry'); if (use) use.checked = false; const uss = document.getElementById('useSpotForSl'); if (uss) uss.checked = false; const ust = document.getElementById('useSpotForTarget'); if (ust) ust.checked = false;
        if (resultDiv) resultDiv.innerText = ''; if (errDiv) { errDiv.style.display = 'none'; errDiv.innerText = ''; }
      });
    }
  }

  function prefillPlaceOrderFormFromRequestRow(req) {
    try {
      if (!req) return;
      const exSel = document.getElementById('exchange'); const instrSel = document.getElementById('instrument'); const strikeSel = document.getElementById('strikePrice'); const expirySel = document.getElementById('expiry'); const optSel = document.getElementById('optionType');
      function ensureSelectValue(sel, val) { if (!sel) return; const v = val == null ? '' : String(val); if (v && !Array.from(sel.options).some(o => String(o.value) === v)) { const opt = document.createElement('option'); opt.value = v; opt.textContent = v; sel.appendChild(opt); } sel.value = v; }
      ensureSelectValue(exSel, req.exchange || req.exch || null); try { exSel && exSel.dispatchEvent(new Event('change')); } catch (e) {}
      ensureSelectValue(instrSel, req.symbol || req.instrument || ''); ensureSelectValue(strikeSel, req.strikePrice != null ? req.strikePrice : (req.strike || '')); ensureSelectValue(expirySel, req.expiry || ''); ensureSelectValue(optSel, req.optionType || '');
      const setVal = (id, v) => { const el = document.getElementById(id); if (el) el.value = (v == null ? '' : String(v)); };
      setVal('entryPrice', req.entryPrice != null ? req.entryPrice : (req.entry || '')); setVal('stopLoss', req.stopLoss != null ? req.stopLoss : (req.stop_loss || '')); setVal('target1', req.target1 != null ? req.target1 : (req.t1 || '')); setVal('target2', req.target2 != null ? req.target2 : (req.t2 || '')); setVal('target3', req.target3 != null ? req.target3 : (req.t3 || '')); setVal('quantity', req.quantity != null ? req.quantity : (req.qty || '')); setVal('spotScripCode', req.spotScripCode != null ? req.spotScripCode : '');
      const intr = document.getElementById('intraday'); if (intr) intr.checked = !!(req.intraday); const tsl = document.getElementById('tslEnabled'); if (tsl) tsl.checked = !!(req.tslEnabled); const usp = document.getElementById('useSpotPrice'); if (usp) usp.checked = !!(req.useSpotPrice); const use = document.getElementById('useSpotForEntry'); if (use) use.checked = !!(req.useSpotForEntry); const uss = document.getElementById('useSpotForSl'); if (uss) uss.checked = !!(req.useSpotForSl); const ust = document.getElementById('useSpotForTarget'); if (ust) ust.checked = !!(req.useSpotForTarget);
      const panel = document.getElementById('placeOrderPanel'); if (panel && panel.scrollIntoView) panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch (e) { console.debug('prefillPlaceOrderFormFromRequestRow failed', e); }
  }

  function wireUserCreation() {
    const btn = document.getElementById('createUserBtn'); if (!btn) return;
    btn.addEventListener('click', async function() {
      const nameInput = document.getElementById('newUserName'); const custIdInput = document.getElementById('newUserCustomerId'); const username = nameInput ? nameInput.value.trim() : ''; const customerId = custIdInput ? custIdInput.value.trim() : '';
      if (!username) { alert('Username is required'); return; }
      try { await ensureCsrf(); const payload = { username: username }; if (customerId) payload.customerId = customerId; const res = await fetchJson('/admin/app-users', { method: 'POST', body: JSON.stringify(payload) }); if (res && res.id) { if (nameInput) nameInput.value = ''; if (custIdInput) custIdInput.value = ''; await loadUsers(); alert('User created: ' + res.username); } else { alert('Failed to create user'); } } catch (e) { alert('Error creating user: ' + (e.message || e)); }
    });
  }

  // Expose functions
  window.loadUsers = loadUsers; window.loadRequestedOrdersForUser = loadRequestedOrdersForUser; window.loadExecutedForUser = loadExecutedForUser; window.loadBrokers = loadBrokers; window.selectUser = selectUser; window.fetchInstrumentsForExchange = fetchInstrumentsForExchange; window.populateInstruments = populateInstruments; window.fetchStrikes = fetchStrikes; window.fetchExpiries = fetchExpiries;
  // Build qualified key helper needed by fetchOptionLtpAndPopulateEntry
  function buildQualifiedOptionKey(exchange, symbol, expiryStr, strike, optionType) {
      if (!exchange || !symbol || !expiryStr || !strike || !optionType) return null;
      const exchangeMap = { 'NF': 'NFO', 'BF': 'BFO' }; const mappedExchange = exchangeMap[exchange] || exchange;
      function monthNumToLetter(m) { const monthLetterMap = { 1: 'J', 2: 'F', 3: 'M', 4: 'A', 5: 'M', 6: 'J', 7: 'J', 8: 'A', 9: 'S', 10: 'O', 11: 'N', 12: 'D' }; return monthLetterMap[m] || 'X'; }
      let expiryDate = null;
      if (expiryStr.includes('/')) { const parts = expiryStr.split('/').map(s => s.trim()); if (parts.length === 3) { const day = Number(parts[0]); const month = Number(parts[1]) - 1; const year = Number(parts[2]); expiryDate = new Date(year, month, day); } } else { const norm = expiryStr.replace(/[^0-9]/g, ''); if (norm.length === 8) { const year = Number(norm.substring(0,4)); const month = Number(norm.substring(4,6)) - 1; const day = Number(norm.substring(6,8)); expiryDate = new Date(year, month, day); } }
      if (!expiryDate || isNaN(expiryDate.getTime())) return null;
      const year = expiryDate.getFullYear(); const month = expiryDate.getMonth();
      function getLastExpiryDay(y, m, expiryWeekday) { const lastDay = new Date(y, m + 1, 0); const lastDate = lastDay.getDate(); for(let d = lastDate; d > lastDate - 7; d--) { const date = new Date(y, m, d); if (date.getDay() === expiryWeekday) return date; } return null; }
      let weeklyExpiryDay = null; if(symbol === 'NIFTY') weeklyExpiryDay = 2; else if(symbol === 'SENSEX') weeklyExpiryDay = 4; else weeklyExpiryDay = 4;
      const lastExpiry = getLastExpiryDay(year, month, weeklyExpiryDay);
      const isWeekly = expiryDate.getDay() === weeklyExpiryDay && expiryDate.getDate() !== lastExpiry.getDate();
      let expiryFormatted = '';
      if (isWeekly) { const yy = String(year).slice(-2); const monLetter = monthNumToLetter(month + 1); const dd = expiryDate.getDate().toString().padStart(2, '0'); expiryFormatted = `${yy}${monLetter}${dd}`; } else { const yy = String(year).slice(-2); const monthNames = ['JAN','FEB','MAR','APR','MAY','JUN','JUL','AUG','SEP','OCT','NOV','DEC']; expiryFormatted = `${yy}${monthNames[month]}`; }
      const strikeNum = Number(strike); const strikeStr = strikeNum % 1 === 0 ? `${strikeNum}` : strikeNum.toFixed(1);
      return mappedExchange + ':' + symbol + expiryFormatted + strikeStr + optionType;
  }
  function fetchUnderlyingAndSelectNearestStrike(exchange, instrument, strikes) {
      if (!exchange || !instrument || !strikes || strikes.length === 0) return;
      const underlyingExchangeMap = { 'NF': 'NSE', 'BF': 'BSE' }; const mappedExchange = underlyingExchangeMap[exchange] || exchange; const qualifiedKey = mappedExchange + ':' + instrument;
      fetchMStockLtpForKey(qualifiedKey).then(ltp => {
          if (ltp == null) return;
          let nearestStrike = null; let minDiff = Infinity;
          for (const strike of strikes) { const strikeVal = parseFloat(strike); const diff = Math.abs(strikeVal - ltp); if (diff < minDiff) { minDiff = diff; nearestStrike = strike; } }
          if (nearestStrike) { const strikeSelect = document.getElementById('strikePrice'); if (strikeSelect) { strikeSelect.value = nearestStrike; strikeSelect.dispatchEvent(new Event('change')); } }
      });
  }
  function fetchOptionLtpAndPopulateEntry() {
      const exchange = document.getElementById('exchange').value; const instrument = document.getElementById('instrument').value; const strikePrice = document.getElementById('strikePrice').value; const optionType = document.getElementById('optionType').value || 'CE'; const expiry = document.getElementById('expiry').value;
      const noStrikeExchanges = new Set(['NC','BC']); const isNoStrike = noStrikeExchanges.has((exchange || '').toUpperCase());
      if (isNoStrike) {
          if (!exchange || !instrument) return;
          const underlyingExchangeMap = { 'NF': 'NSE', 'BF': 'BSE', 'NC': 'NSE', 'BC': 'BSE' }; const mappedExchange = underlyingExchangeMap[exchange] || exchange; const qualified = mappedExchange + ':' + instrument;
          fetchMStockLtpForKey(qualified).then(ltp => { if (ltp != null) document.getElementById('entryPrice').value = ltp; }).catch(err => {});
          return;
      }
      if (!exchange || !instrument || !strikePrice || !expiry) return;
      const qualifiedKey = buildQualifiedOptionKey(exchange, instrument, expiry, strikePrice, optionType);
      if (!qualifiedKey) return;
      fetchMStockLtpForKey(qualifiedKey).then(ltp => { if (ltp != null) { const entryPriceInput = document.getElementById('entryPrice'); if (entryPriceInput) entryPriceInput.value = ltp; } }).catch(err => {});
  }

  document.addEventListener('DOMContentLoaded', function(){ ensureCsrf(); loadUsers().catch(function(){}); wireAdminForm(); wireBrokersUI(); wirePlaceOrderForm(); wireStatusFilter(); wireExecutedPagination(); wireUserCreation(); startAdminWs(); });

})();
