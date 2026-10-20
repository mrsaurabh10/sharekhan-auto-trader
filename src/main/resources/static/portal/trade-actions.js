/* Personal trade controls reuse the authenticated, owner-checked trade APIs. */
(() => {
  'use strict';
  const openStatuses = new Set(['EXECUTED', 'TARGET_ORDER_PLACED']);
  const exitStatuses = new Set(['EXIT_TRIGGERED', 'EXIT_ORDER_PLACED', 'TARGET_ORDER_PLACED']);
  const liveStatuses = new Set([...openStatuses, ...exitStatuses]);
  const node = (tag, text, className) => {
    const el = document.createElement(tag);
    if (text != null) el.textContent = text;
    if (className) el.className = className;
    return el;
  };
  function dialog(title, description) {
    const el = node('dialog', null, 'trade-action-dialog');
    const heading = node('h2', title); heading.id = 'tradeActionTitle';
    el.setAttribute('aria-labelledby', heading.id);
    const form = node('form');
    const fields = node('div', null, 'grid trade-edit-fields');
    const error = node('p', null, 'notice error'); error.hidden = true; error.setAttribute('role', 'alert'); error.tabIndex = -1;
    const actions = node('div', null, 'actions');
    const cancel = node('button', 'Cancel', 'secondary'); cancel.type = 'button'; cancel.onclick = () => el.close();
    actions.append(cancel); form.append(fields, error, actions);
    el.append(heading, node('p', description, 'muted'), form); document.body.append(el);
    el.addEventListener('close', () => el.remove(), {once: true});
    el.showModal();
    return {el, form, fields, actions, error};
  }
  function field(container, text, input) {
    const label = node('label', text); label.append(input); container.append(label); return input;
  }
  function number(container, text, value, required = false) {
    const input = node('input'); input.type = 'number'; input.min = '0.01'; input.step = '0.01';
    input.value = value == null || Number(value) <= 0 ? '' : value; input.required = required;
    return field(container, text, input);
  }
  function choice(container, text, options, value) {
    const select = node('select');
    for (const [key, label] of options) { const option = node('option', label); option.value = key; select.append(option); }
    select.value = value; return field(container, text, select);
  }
  function submit(d, label, action) {
    const save = node('button', label, 'primary'); save.type = 'submit'; d.actions.append(save);
    let pending = false;
    d.el.addEventListener('cancel', event => { if (pending) event.preventDefault(); });
    d.form.addEventListener('submit', async event => {
      event.preventDefault(); if (pending) return;
      pending = true; d.error.hidden = true;
      for (const button of d.actions.querySelectorAll('button')) button.disabled = true;
      try { await action(); } catch (error) { d.error.textContent = error.message; d.error.hidden = false; d.error.focus(); }
      finally { pending = false; for (const button of d.actions.querySelectorAll('button')) button.disabled = false; }
    });
  }
  function mount(cell, trade, {api, refresh, notify, button}) {
    const status = String(trade.status || '').toUpperCase();
    const id = encodeURIComponent(trade.id);
    const title = 'Trade ' + trade.id + ' · ' + (trade.symbol || '');
    const actions = node('div', null, 'personal-trade-actions'); cell.append(actions);
    async function complete(d, message) {
      d.el.close(); notify(typeof message === 'string' ? message : 'Trade settings updated.');
      try { await refresh(); } catch (error) { notify('Action completed, but refresh failed: ' + error.message + '. Use Refresh orders.', true); }
    }
    if (openStatuses.has(status)) {
      actions.append(button('Edit SL / targets', async () => {
        const d = dialog('Edit ' + title, 'Changes affect app-managed exits and may trigger an exit if a level is already crossed. Existing broker exit prices are changed separately with Modify Exit. Entry and filled quantity are shown for reference.');
        d.fields.append(node('p', 'Entry reference: ' + (trade.entryPrice ?? '—') + ' · Actual fill: ' + (trade.actualEntryPrice ?? '—') + ' · Quantity: ' + (trade.quantity ?? '—'), 'full muted'));
        const inputs = {};
        for (const [key, label] of [['stopLoss','Stop-loss ₹'], ['target1','Target 1 ₹'], ['target2','Target 2 ₹'], ['target3','Target 3 ₹']]) inputs[key] = number(d.fields, label, trade[key]);
        for (const [key, label] of [['intraday','Same-day exit managed by app'], ['tslEnabled','Enable trailing SL (partial booking)']]) {
          const input = node('input'); input.type = 'checkbox'; input.checked = trade[key] === true;
          field(d.fields, label, input).parentElement.className = 'check'; inputs[key] = input;
        }
        for (const [key, label] of [['useSpotForEntry','Entry reference'], ['useSpotForSl','Stop-loss reference'], ['useSpotForTarget','Target reference']]) {
          inputs[key] = choice(d.fields, label, [['true','Spot'], ['false','Traded instrument / premium']], String(trade[key] ?? trade.useSpotPrice ?? false));
        }
        const initial = Object.fromEntries(Object.entries(inputs).map(([key, input]) => [key, input.type === 'checkbox' ? input.checked : input.value]));
        submit(d, 'Save changes', async () => {
          const payload = {};
          for (const [key, input] of Object.entries(inputs)) {
            const value = input.type === 'checkbox' ? input.checked : input.value;
            if (value === initial[key]) continue;
            if (input.type === 'number') {
              if (!value || !Number.isFinite(Number(value)) || Number(value) <= 0) throw new Error('Enter a positive price. Existing levels cannot be cleared here.');
              payload[key] = Number(value);
            } else if (input.type === 'checkbox') payload[key] = value;
            else payload[key] = value === 'true';
          }
          if (!Object.keys(payload).length) throw new Error('No changes to save.');
          const result = await api('/api/trades/execution/' + id, 'PUT', payload);
          await complete(d, typeof result === 'string' ? result : 'Trade settings updated.');
        });
        d.fields.querySelector('input').focus();
      }));
      actions.append(button('Move SL to Cost', async () => {
        const spot = trade.useSpotForSl === true || (trade.useSpotForSl == null && trade.useSpotPrice === true);
        const d = dialog('Move SL to Cost · ' + title, 'Spot uses the original spot entry. Premium uses the traded entry with estimated round-trip charges included. A crossed stop can trigger an exit.');
        const reference = choice(d.fields, 'Cost basis', spot ? [['SPOT','Spot entry'], ['PREMIUM','Premium break-even (including charges)']] : [['PREMIUM','Traded price break-even (including charges)']], spot ? 'SPOT' : 'PREMIUM');
        submit(d, 'Confirm SL change', async () => complete(d, await api('/api/trades/move-sl-to-cost/' + id + '?reference=' + reference.value, 'POST', {})));
      }));
      actions.append(button('Close position', async () => {
        const d = dialog('Close position · ' + title, 'This submits a live square-off order. Check the broker for confirmation of the fill. Leave the price blank to use the normal app exit price.');
        const price = number(d.fields, 'Optional exit price ₹', null);
        const purpose = choice(d.fields, 'Exit purpose', [['EXIT_ORDER_PLACED','Close position (chase fill)'], ['TARGET_ORDER_PLACED','Target exit (wait for entered price)']], 'EXIT_ORDER_PLACED');
        submit(d, 'Submit square-off', async () => {
          if (purpose.value === 'TARGET_ORDER_PLACED' && !price.value) throw new Error('Enter a price for a target exit.');
          const params = new URLSearchParams({exitOrderStatus: purpose.value});
          if (price.value) params.set('price', price.value);
          await complete(d, await api('/api/trades/square-off/' + id + '?' + params, 'POST', {}));
        });
      }, 'danger'));
    }
    if (exitStatuses.has(status) && String(trade.exitOrderId || '').trim()) actions.append(button('Modify Exit', async () => {
      const d = dialog('Modify Exit · ' + title, 'Submit a new limit price for the existing broker exit order. This does not change the app stop-loss or target levels.');
      const price = number(d.fields, 'New exit price ₹', null, true);
      submit(d, 'Submit exit price', async () => complete(d, await api('/api/trades/exit-order/' + id + '/modify', 'POST', {price: Number(price.value)})));
      price.focus();
    }));
    if (liveStatuses.has(status) && trade.intraday === false) actions.append(button('Reset to Executed', async () => {
      const d = dialog('Reset to Executed · ' + title, 'For carry-forward positions only. The server checks the broker exit order and resets only when it is inactive. An active, filled, or unverifiable broker order prevents the reset.');
      submit(d, 'Check and reset', async () => complete(d, await api('/api/trades/execution/' + id + '/reset-to-executed', 'POST', {})));
    }));
    actions.append(button('Audit', async () => {
      const d = dialog('Audit · ' + title, 'Recorded decisions and execution history for this trade.');
      const query = trade.triggerRequestId != null ? 'triggerRequestId=' + encodeURIComponent(trade.triggerRequestId) : 'tradeId=' + id;
      const loading = node('p', 'Loading audit events…'); d.fields.append(loading);
      try {
        const events = await api('/api/trades/audit?' + query);
        loading.remove();
        if (!Array.isArray(events)) throw new Error('Invalid audit response.');
        if (!events.length) d.fields.append(node('p', 'No audit events have been recorded for this trade yet.'));
        for (const event of events) {
          const article = node('article', null, 'trade-audit-event');
          article.append(node('h3', [event.eventType, event.outcome].filter(Boolean).join(' · ')), node('p', [event.occurredAt, event.strategyId || event.source].filter(Boolean).join(' · '), 'muted'));
          for (const [label, value] of [['Reason',event.reason], ['Instrument',[event.symbol,event.optionType,event.strikePrice,event.expiry].filter(v=>v!=null).join(' ')], ['Quote',[['Spot',event.spotPrice],['LTP',event.optionLtp],['Bid',event.bestBid],['Ask',event.bestAsk]].filter(([,v])=>v!=null).map(([k,v])=>k+' '+v).join(' · ')]]) if (value) article.append(node('p', label + ': ' + value));
          if (event.details) { const details = node('details'); details.append(node('summary', 'Details'), node('pre', event.details)); article.append(details); }
          d.fields.append(article);
        }
      } catch (error) { loading.remove(); d.error.textContent = 'Could not load audit events: ' + error.message; d.error.hidden = false; }
    }));
  }
  window.PersonalTradeActions = {mount};
})();
