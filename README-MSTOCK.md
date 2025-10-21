MStock configuration and testing

This document explains how to define the `app.mstock.api-key` and `app.mstock.totp-secret` properties used by the MStock authentication and LTP code, and how to test the flow locally.

Where the application reads these values
- The application binds properties using Spring Boot configuration properties: `app.mstock.api-key` and `app.mstock.totp-secret`.
- You should NOT commit real secrets to `application.yml`. Instead, supply them via environment variables, command-line args, or a secrets manager.
- The code exposes a `MStockProperties` class (bound to `app.mstock`) so the values are centrally available.

Environment variables (recommended for local/dev)
Spring Boot supports relaxed binding. The corresponding environment variables are:
- `APP_MSTOCK_API_KEY` -> `app.mstock.api-key`
- `APP_MSTOCK_TOTP_SECRET` -> `app.mstock.totp-secret`

Example (bash):

```bash
export APP_MSTOCK_API_KEY="your_mstock_api_key_here"
export APP_MSTOCK_TOTP_SECRET="your_totp_secret_here"
```

Run the app (example using the packaged JAR):

```bash
java -jar target/SharekhanOrderAPI-1.0-SNAPSHOT.jar
```

Or pass them inline to the JVM process:

```bash
APP_MSTOCK_API_KEY="your_mstock_api_key" APP_MSTOCK_TOTP_SECRET="your_totp_secret" java -jar target/SharekhanOrderAPI-1.0-SNAPSHOT.jar
```

Command-line arguments
You can also pass the properties as Spring Boot arguments when launching the jar or via `mvn spring-boot:run`:

```bash
java -jar target/SharekhanOrderAPI-1.0-SNAPSHOT.jar \
  --app.mstock.api-key="your_mstock_api_key" \
  --app.mstock.totp-secret="your_totp_secret"
```

or with Maven:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--app.mstock.api-key=your_mstock_api_key,--app.mstock.totp-secret=your_totp_secret"
```

Docker
In Docker you can pass env vars via `docker run -e` or via a docker-compose file:

```bash
docker run -e APP_MSTOCK_API_KEY=your_mstock_api_key -e APP_MSTOCK_TOTP_SECRET=your_totp_secret \
  -p 8080:8080 sharekhan-auto-trader:latest
```

Kubernetes
Create a Kubernetes Secret and mount it as environment variables into your Pod. Example manifest snippet:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: mstock-secret
type: Opaque
stringData:
  APP_MSTOCK_API_KEY: "your_mstock_api_key"
  APP_MSTOCK_TOTP_SECRET: "your_totp_secret"
```

Then reference the secret in your Deployment environment variables.

Testing the provider & storing tokens
- I added `TestAuthProvidersRunner`, which is conditional on the property `app.test-auth-providers=true`. When enabled, the application will attempt to fetch tokens for all registered `BrokerAuthProvider`s at startup and store them into the DB via `TokenStoreService`.

Run the jar with the test runner enabled (temporary):

```bash
java -jar target/SharekhanOrderAPI-1.0-SNAPSHOT.jar \
  --app.test-auth-providers=true \
  --app.mstock.api-key="your_mstock_api_key" \
  --app.mstock.totp-secret="your_totp_secret"
```

This will call the MStock TOTP verify endpoint, obtain `access_token` and save it in the `access_token` table (brokerName = `MStock`).

Verifying persisted token
- H2 Console (enabled by default in application.yml): http://localhost:8080/h2-console
  - JDBC URL: `jdbc:h2:file:./data/scriptdb`
  - username: `sa`
  - no password

Then run:

```sql
SELECT id, token, expiry, brokerName FROM access_token ORDER BY expiry DESC;
```

LTP endpoint (local test)
- After you have a valid MStock token stored (see above), you can call the local endpoint I added to proxy the MStock LTP API:

Example curl (multiple `i` query params):

```bash
curl -sG 'http://localhost:8080/api/mstock/ltp' \
  --data-urlencode "i=NSE:ACC" \
  --data-urlencode "i=BSE:ACC" \
  --data-urlencode "i=NFO:CDSL25JAN2220CE" | jq
```

You should receive a JSON response in the shape returned by MStock:

```json
{
  "status": "success",
  "data": {
    "NSE:ACC": {"instrument_token": 500410, "last_price": 195700},
    "BSE:ACC": {"instrument_token": 500410, "last_price": 195700},
    "NFO:CDSL25JAN2220CE": null
  }
}
```

Security notes
- Do not store secrets in `application.yml` committed to source control.
- Use environment variables, a secrets manager, or platform-specific secret injection (Kubernetes secrets, HashiCorp Vault, AWS Secrets Manager) in production.
- Rotate secrets regularly and restrict access.

If you'd like, I can:
- Add a small `application-mstock.yml` example under `src/main/resources` (gitignored) for local dev.
- Add `@ConfigurationProperties` validation (to fail fast if secrets are missing in sensitive environments).
- Add support for refreshing token automatically on 401 from the LTP endpoint (retry once after re-authenticating).

Let me know which of the above you'd like me to implement next.
