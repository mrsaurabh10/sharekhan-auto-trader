# Shared Shoonya password

The shared SHOONYA_PASSWORD deployment value may be an `ENC(...)` envelope containing
the same Base64 AES-GCM encoding used by CryptoService (12-byte random IV followed by
ciphertext and authentication tag). Decrypt only into the per-login property copy;
never replace the original encrypted configuration property or log its contents.
Unreadable or malformed envelopes fail before launching the login browser. Existing
unwrapped configuration and account-specific encrypted credentials remain compatible.
Use the existing persistent application encryption key; never rotate it as part of a
password update or export it to a client. Preserve all unrelated deployment settings.
Password input belongs in a masked local prompt or a secure settings form, not chat,
process arguments, command history or a plaintext file. A password update does not
prove OAuth login/token exchange succeeded; verify that separately through the app.
