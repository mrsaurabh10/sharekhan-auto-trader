// admin-dashboard.js - stub implementation
(function () {
  'use strict';

  // Minimal safe admin-dashboard stub to avoid syntax errors while we iterate.
  // Exposes the functions used by the template as no-op placeholders.

  async function ensureCsrf() {
    // noop for now
    return;
  }

  async function fetchJson(url, opts) {
    const res = await fetch(url, opts || { credentials: 'include' });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new Error('HTTP ' + res.status + ' ' + text);
    }
    return res.json();
  }

  function noop() {}

  async function loadUsers() {
    console.debug('loadUsers stub called');
    // safe no-op: UI will show message if not implemented
  }

  async function loadRequestedOrdersForUser(userId) {
    console.debug('loadRequestedOrdersForUser stub for', userId);
  }

  async function loadExecutedForUser(userId) {
    console.debug('loadExecutedForUser stub for', userId);
  }

  async function loadBrokers(userId) {
    console.debug('loadBrokers stub for', userId);
  }

  function selectUser(appUserId, liElem, customerId) {
    console.debug('selectUser stub', appUserId, customerId);
    window.selectedUserId = appUserId;
    const uc = document.getElementById('userContent'); if (uc) uc.style.display = 'block';
  }

  // expose safe stubs
  window.loadUsers = loadUsers;
  window.loadRequestedOrdersForUser = loadRequestedOrdersForUser;
  window.loadExecutedForUser = loadExecutedForUser;
  window.loadBrokers = loadBrokers;
  window.selectUser = selectUser;
  window.selectUserWithCustomer = (appUserId, customerId, btn) => selectUser(appUserId, btn, customerId);

  document.addEventListener('DOMContentLoaded', () => {
    console.debug('admin-dashboard stub loaded');
    // call loadUsers so UI won't remain stuck
    try { loadUsers(); } catch (e) { console.debug('loadUsers error', e); }
  });

})();
