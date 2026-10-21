'use strict';
window.PersonalTelegramSettings = {
  mount(api, isAdmin) {
    const card = document.querySelector('#telegramSettings');
    if (!card) return;
    if (isAdmin) { card.hidden = true; return; }
    const form = card.querySelector('form');
    const input = form.elements.username;
    const status = card.querySelector('[data-status]');
    const detail = card.querySelector('[data-detail]');
    const connect = card.querySelector('[data-connect]');
    const refresh = card.querySelector('[data-refresh]');
    const remove = card.querySelector('[data-remove]');
    const open = card.querySelector('[data-open]');
    let poll, pending = false, botConfigured = false;
    function clearPoll() { if (poll) clearInterval(poll); poll = null; }
    function render(data) {
      input.value = data.username || '';
      status.textContent = data.connected ? 'Connected · @' + data.username : data.username ? 'Username saved · Connection pending' : 'Not connected';
      detail.textContent = data.connected
        ? 'Trade updates and exit attention alerts go to your personal Telegram chat (ID ' + data.chatId + ').'
        : 'Save your Telegram username, open your connection link, then press Start in the bot. Your numeric chat ID will be captured automatically.';
      botConfigured = data.botConfigured;
      connect.disabled = !botConfigured;
      if (!data.botConfigured) detail.textContent = 'Secure Telegram connections are currently unavailable. Your username can still be saved.';
      remove.hidden = !data.username;
      if (data.connected) { open.hidden = true; open.removeAttribute('href'); clearPoll(); }
    }
    function showError(error) { detail.textContent = error.message || 'Unable to update Telegram settings. Please try again.'; }
    async function load() { const data = await api('/api/user/telegram'); render(data); return data; }
    async function action(button, work) {
      if (pending) return;
      pending = true; button.disabled = true;
      try { await work(); } catch (error) { showError(error); }
      finally { pending = false; button.disabled = button === connect && !botConfigured; }
    }
    async function save() {
      const data = await api('/api/user/telegram', 'PUT', {username: input.value});
      open.hidden = true; open.removeAttribute('href'); clearPoll(); render(data); return data;
    }
    form.addEventListener('submit', event => { event.preventDefault(); action(form.querySelector('[type=submit]'), save); });
    connect.addEventListener('click', () => action(connect, async () => {
      if (!form.reportValidity()) return;
      await save();
      const link = await api('/api/user/telegram/link', 'POST', {});
      if (!/^https:\/\/t\.me\/[A-Za-z0-9_]+\?start=connect_[A-Za-z0-9_-]{32}$/.test(link.url)) throw new Error('Unable to create a Telegram connection link.');
      open.href = link.url; open.hidden = false;
      detail.textContent = 'Open Telegram below and press Start using @' + input.value.replace(/^@/, '') + '. The link expires in 10 minutes. Then refresh the connection status.';
      let remaining = 120;
      poll = setInterval(async () => {
        if (--remaining <= 0) { clearPoll(); return; }
        if (document.hidden || pending) return;
        try {
          const data = await api('/api/user/telegram');
          if (data.connected) render(data);
        } catch { clearPoll(); }
      }, 5000);
    }));
    refresh.addEventListener('click', () => action(refresh, load));
    remove.addEventListener('click', () => action(remove, async () => {
      await api('/api/user/telegram', 'DELETE');
      open.hidden = true; open.removeAttribute('href'); clearPoll(); await load();
    }));
    load().catch(showError);
  }
};
