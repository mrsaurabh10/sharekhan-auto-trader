/* Compact contract selection; existing form fields remain the order payload source. */
(() => {
  'use strict';
  function mount() {
    const portal = document.getElementById('orderForm');
    const exchange = document.getElementById(portal ? 'orderExchange' : 'exchange');
    const instrument = document.getElementById(portal ? 'orderInstrument' : 'instrument');
    const strike = document.getElementById(portal ? 'orderStrike' : 'strikePrice');
    const type = portal ? portal.elements.optionType : document.getElementById('optionType');
    const expiry = document.getElementById(portal ? 'orderExpiry' : 'expiry');
    if (!exchange || !instrument || !strike || !type || !expiry) return;
    const make = (tag, text, cls) => {
      const el = document.createElement(tag);
      if (text) el.textContent = text;
      if (cls) el.className = cls;
      return el;
    };
    const box = make('section', '', 'option-picker');
    box.setAttribute('aria-label', 'Option chain selection');
    const heading = make('div', '', 'option-picker-heading');
    heading.append(make('strong', 'Choose an option'));
    const nav = make('div', '', 'option-picker-nav');
    const previous = make('button', '← Lower');
    const next = make('button', 'Higher →');
    previous.type = next.type = 'button';
    nav.append(previous, next); heading.append(nav);
    const status = make('p', '', 'option-picker-status');
    status.setAttribute('aria-live', 'polite');
    const grid = make('div', '', 'option-picker-grid');
    const note = make('small', 'Select Call or Put to fill the contract. Use Strike for the full list; choose an expiry below.');
    box.append(heading, status, grid, note);
    const anchor = portal ? document.getElementById('optionTypeField') : type.closest('.form-row');
    if (!anchor) return;
    anchor.before(box);
    let centre = null;
    let identity = '';
    let strikes = [];
    function render() {
      const focused = grid.contains(document.activeElement) ? document.activeElement.getAttribute('aria-label') : null;
      const key = exchange.value + ':' + instrument.value;
      if (identity !== key) { identity = key; centre = null; }
      box.hidden = !['NF', 'BF', 'MX'].includes(exchange.value) || !instrument.value.trim();
      if (box.hidden) return;
      strikes = [...new Set([...strike.options].map(o => o.value).filter(v => v && Number.isFinite(Number(v)) && Number(v) > 0))]
        .sort((a,b) => Number(a) - Number(b));
      if (centre === null) {
        const selected = strikes.indexOf(strike.value);
        centre = selected < 0 ? Math.floor(strikes.length / 2) : selected;
      }
      const start = Math.max(0, Math.min(centre - 2, strikes.length - 5));
      previous.disabled = start === 0;
      next.disabled = start + 5 >= strikes.length;
      status.textContent = strikes.length ? instrument.value + ' · ' + (expiry.value || 'Choose expiry below') : 'Choose a strike from the list when it becomes available.';
      grid.replaceChildren();
      for (const label of ['Call (CE)', 'Strike', 'Put (PE)']) grid.append(make('span', label, 'option-picker-column'));
      for (const value of strikes.slice(start, start + 5)) {
        for (const side of ['CE', 'strike', 'PE']) {
          if (side === 'strike') { grid.append(make('span', value, 'option-picker-strike')); continue; }
          const selected = strike.value === value && type.value === side;
          const button = make('button', (selected ? '✓ ' : '') + (side === 'CE' ? 'Call' : 'Put'));
          button.type = 'button';
          button.className = selected ? 'is-selected' : '';
          button.setAttribute('aria-label', 'Select ' + instrument.value + ' ' + value + ' ' + side);
          button.setAttribute('aria-pressed', String(selected));
          button.addEventListener('click', () => {
            type.value = side;
            strike.value = value;
            // The strike handler loads valid expiries. No order is submitted here.
            strike.dispatchEvent(new Event('change', {bubbles:true}));
          });
          grid.append(button);
        }
      }
      if (focused) [...grid.querySelectorAll('button')].find(button => button.getAttribute('aria-label') === focused)?.focus({preventScroll:true});
    }
    previous.addEventListener('click', () => { centre = Math.max(2, centre - 5); render(); });
    next.addEventListener('click', () => { centre = Math.min(strikes.length - 3, centre + 5); render(); });
    strike.addEventListener('change', () => { centre = null; render(); });
    for (const field of [exchange, instrument, type, expiry]) field.addEventListener('change', render);
    // Strike/expiry lists arrive asynchronously in both personal and admin forms.
    new MutationObserver(() => { centre = null; render(); }).observe(strike, {childList:true, subtree:true});
    new MutationObserver(render).observe(expiry, {childList:true, subtree:true});
    render();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount);
  else mount();
})();
