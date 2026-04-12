## Telegram Webhook Setup with Direct TLS on Spring Boot

This guide covers the steps to expose `/telegram/webhook` over HTTPS without an external reverse proxy. The Spring Boot app terminates TLS itself using the embedded Tomcat server.

### 1. Prerequisites
- A domain (or subdomain) you control. Telegram requires a publicly trusted certificate, which in practice means an FQDN pointed at your Kamatera VM.
- Public DNS A record: e.g. `bot.example.com → <kamatera public IP>`.
- Shell access to the VM with sudo privileges.

### 2. Install ACME client (Let’s Encrypt)
```bash
sudo apt update
sudo apt install certbot -y
```
If some other ACME client fits better (lego, acme.sh), adjust the commands accordingly.

### 3. Obtain a certificate
Stop anything bound to ports 80/443, then request a cert using the standalone HTTP challenge:
```bash
sudo systemctl stop sharekhan-app.service   # or however the app is run
sudo certbot certonly --standalone -d bot.example.com
```
The certificate and key land in `/etc/letsencrypt/live/bot.example.com/`.

> **Renewals:** Let’s Encrypt certs expire every 90 days. Certbot installs a cron job that attempts renewal twice daily. You will convert the renewed cert into a PKCS12 keystore in the next step—automate that with a deploy hook.

### 4. Convert to PKCS12 for Spring Boot
```bash
sudo openssl pkcs12 -export \
  -in /etc/letsencrypt/live/bot.example.com/fullchain.pem \
  -inkey /etc/letsencrypt/live/bot.example.com/privkey.pem \
  -out /etc/letsencrypt/live/bot.example.com/bot.p12 \
  -name spring-boot \
  -passout pass:changeit
```
Set restrictive permissions:
```bash
sudo chmod 640 /etc/letsencrypt/live/bot.example.com/bot.p12
sudo chown root:sharekhan /etc/letsencrypt/live/bot.example.com/bot.p12   # replace group as needed
```

Automation: place a script in `/etc/letsencrypt/renewal-hooks/deploy/convert-to-p12.sh` that repeats the conversion and restarts the app after each renewal.

### 5. Configure Spring Boot
The app reads SSL settings from environment variables (see `application.yml`):

| Variable | Description |
| --- | --- |
| `SERVER_SSL_ENABLED` | Set to `true` to enable TLS. |
| `SERVER_SSL_KEY_STORE` | Absolute path to the `.p12` file (`/etc/letsencrypt/live/bot.example.com/bot.p12`). |
| `SERVER_SSL_KEY_STORE_PASSWORD` | Password used in step 4 (`changeit` in the example). |
| `SERVER_SSL_KEY_STORE_TYPE` | Defaults to `PKCS12`; override if needed. |
| `SERVER_SSL_KEY_ALIAS` | Defaults to `spring-boot`. |
| `SERVER_PORT` | Optional. Set to `443` if you run as root or give the binary the `CAP_NET_BIND_SERVICE` capability. Otherwise leave at `8443` and use a firewall rule/port forwarding if desired. |

Example systemd drop-in (`/etc/systemd/system/sharekhan-app.service.d/ssl.conf`):
```
[Service]
Environment=SERVER_SSL_ENABLED=true
Environment=SERVER_SSL_KEY_STORE=file:/etc/letsencrypt/live/bot.example.com/bot.p12
Environment=SERVER_SSL_KEY_STORE_PASSWORD=changeit
Environment=SERVER_SSL_KEY_ALIAS=spring-boot
Environment=SERVER_PORT=8443
Environment=APP_TELEGRAM_WEBHOOK_SECRET=<your-secret>
```
Reload the systemd daemon and restart the service:
```bash
sudo systemctl daemon-reload
sudo systemctl start sharekhan-app.service
```

> If you want to listen on 443 without running as root, add the capability once:  
> `sudo setcap 'cap_net_bind_service=+ep' /path/to/java`  
> then set `SERVER_PORT=443`.

### 6. Verify HTTPS
Test from your workstation:
```bash
curl -I https://bot.example.com:8443/telegram/webhook
```
(Drop `:8443` if binding on 443.) You should see `HTTP/1.1 200 OK`.

### 7. Register Telegram webhook
```bash
curl https://api.telegram.org/bot<YOUR_BOT_TOKEN>/setWebhook \
  -d "url=https://bot.example.com:8443/telegram/webhook" \
  -d "secret_token=<same-secret-configured-in-app>"
```
Check status:
```bash
curl https://api.telegram.org/bot<YOUR_BOT_TOKEN>/getWebhookInfo
```

### 8. Monitor and maintain
- Tail logs: `journalctl -u sharekhan-app.service -f` to confirm incoming messages.
- Ensure the PKCS12 conversion script is part of the cert renewal hook so the app always uses a fresh certificate.
- Keep firewall rules allowing inbound TCP 443 (or 8443) from the internet.

### Do I need a domain?
Yes. Telegram only accepts HTTPS endpoints backed by certificates issued to a real hostname. Use any domain or subdomain you control; register one if you don’t already have it. A DNS-only service (Cloudflare, Route53, etc.) is sufficient—you just need an A record that points to your VM.
