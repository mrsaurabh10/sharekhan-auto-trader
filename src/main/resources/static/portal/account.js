'use strict';
(async function () {
  const mode = new URLSearchParams(location.search).get('mode') || 'signup';
  const settings = {
    signup: ['Create your account', 'Start with your account. Connect your broker when you’re ready.', 'Create account →', 'register', ['displayName', 'email', 'password']],
    forgot: ['Reset your password', 'We’ll email you a secure password reset link.', 'Send reset link', 'forgot-password', ['email']],
    resend: ['Verify your email', 'Request a new link to finish setting up your account.', 'Send verification link', 'resend', ['email']],
    verify: ['Verify your email', 'Confirm to activate your account. Your link is valid for 30 minutes.', 'Verify email', 'verify', []],
    reset: ['Choose a new password', 'Use a unique password with at least 12 characters.', 'Reset password', 'reset-password', ['password']]
  };
  const setting = settings[mode] || settings.signup;
  const form = document.querySelector('#accountForm');
  document.querySelector('#accountTitle').textContent = setting[0];
  document.querySelector('#accountDescription').textContent = setting[1];
  document.querySelector('#accountSubmit').textContent = setting[2];
  for (const field of ['displayName', 'email', 'password']) {
    const input = form.elements[field];
    input.closest('label').hidden = !setting[4].includes(field);
    input.disabled = !setting[4].includes(field);
    input.required = setting[4].includes(field);
  }
  // Fragments stay out of access logs and Referer headers. Remove it from browser history immediately.
  const token = new URLSearchParams(location.hash.slice(1)).get('token');
  if (location.hash) history.replaceState(null, '', location.pathname + location.search);
  const message = document.querySelector('#accountMessage');
  function show(text, error = false) { message.textContent = text; message.className = 'notice ' + (error ? 'error' : 'success'); message.hidden = false; }
  if (['verify','reset'].includes(mode) && !token) { show('The link is missing its token. Request a new email.', true); form.hidden = true; }
  if (!['verify','reset'].includes(mode)) {
    try { const response = await fetch('/api/account/availability'); const body = await response.json();
      if (!body.enabled) { show('Account registration and recovery are currently unavailable. Please try again later.', true); form.hidden = true; }
    } catch { show('Unable to check account availability. Please refresh.', true); }
  }
  form.addEventListener('submit', async event => {
    event.preventDefault(); const button = form.querySelector('button'); button.disabled = true;
    try {
      const csrfResponse = await fetch('/api/auth/csrf-token', {credentials: 'same-origin'});
      if (!csrfResponse.ok) throw new Error('Unable to initialize secure session. Refresh and try again.');
      const csrf = await csrfResponse.json();
      const body = Object.fromEntries(new FormData(form)); if (token) body.token = token;
      const response = await fetch('/api/account/' + setting[3], {method:'POST', credentials:'same-origin', headers:{'Content-Type':'application/json', [csrf.header]:csrf.token}, body:JSON.stringify(body)});
      const result = await response.json();
      if (!response.ok) throw new Error(result.message || result.error || 'Unable to complete this request. Please try again.');
      show(result.message); form.hidden = true; form.reset();
    } catch(error) { show(error.message, true); } finally { button.disabled = false; }
  });
})();
