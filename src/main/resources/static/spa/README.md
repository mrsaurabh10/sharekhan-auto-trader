Minimal SPA to run in parallel with the existing server

- Files created under `src/main/resources/static/spa/`:
  - `index.html` - a tiny single page UI that posts to `/api/trades/trigger-on-price` and provides quick API buttons
  - `app.js` - small vanilla JS to call the API and render responses

How to access
- Start your Spring Boot application and open:
  - http://localhost:8080/spa/  (will serve index.html)

Notes
- I intentionally did not modify `place-order.html` per your request.
- The SPA uses the server's existing REST endpoints `/api/trades/trigger-on-price`, `/api/trades/recent-executions`, `/api/trades/pending` — ensure these endpoints exist. If the endpoint names differ, update `app.js` accordingly.
- This is intentionally lightweight and not meant to replace the existing UI; it's a developer-friendly parallel SPA for quick testing and simple workflows.

