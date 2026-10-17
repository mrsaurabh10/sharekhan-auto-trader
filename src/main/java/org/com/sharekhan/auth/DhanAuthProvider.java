package org.com.sharekhan.auth;

import java.net.URI;
import java.time.*;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.util.CryptoService;
import org.jboss.aerogear.security.otp.Totp;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/** Dhan individual authentication. Never include broker URLs, bodies or causes in errors. */
@Service
public class DhanAuthProvider implements BrokerAuthProvider {
    private static final String AUTH = "https://auth.dhan.co";
    private final CryptoService crypto;
    interface Transport { JSONObject exchange(URI uri, HttpMethod method, HttpHeaders headers) throws Exception; }
    private final Transport http;
    private final Clock clock;

    @Autowired
    public DhanAuthProvider(CryptoService crypto) {
        this(crypto, transport(), Clock.systemUTC());
    }
    DhanAuthProvider(CryptoService crypto, Transport http, Clock clock) {
        this.crypto = crypto; this.http = http; this.clock = clock;
    }
    private static Transport transport() {
        // Do not use RestTemplate: its debug logging includes the PIN/TOTP query string.
        var client = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        return (uri, method, headers) -> {
            var request = java.net.http.HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .method(method.name(), java.net.http.HttpRequest.BodyPublishers.noBody());
            headers.forEach((name, values) -> values.forEach(value -> request.header(name, value)));
            var response = client.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalArgumentException();
            return new JSONObject(response.body());
        };
    }
    @Override public Broker getBroker() { return Broker.DHAN; }
    @Override public AuthTokenResult loginAndFetchToken() {
        throw new IllegalStateException("Dhan requires user-scoped credentials");
    }
    @Override public AuthTokenResult loginAndFetchToken(BrokerCredentialsEntity creds) {
        try {
            String client = client(creds);
            String pin = secret(creds.getBrokerPassword());
            String seed = secret(creds.getTotpSecret());
            if (!pin.matches("[0-9]{6}") || !seed.matches("(?i)[A-Z2-7]{16,128}=*")) throw new IllegalArgumentException();
            URI uri = UriComponentsBuilder.fromHttpUrl(AUTH + "/app/generateAccessToken")
                .queryParam("dhanClientId", client).queryParam("pin", pin)
                .queryParam("totp", new Totp(seed).now()).build().encode().toUri();
            return token(request(uri, HttpMethod.POST, new HttpHeaders()), client);
        } catch (Exception ignored) { throw failure(); }
    }
    public String beginConsent(BrokerCredentialsEntity creds) {
        try {
            URI uri = UriComponentsBuilder.fromHttpUrl(AUTH + "/app/generate-consent")
                .queryParam("client_id", client(creds)).build().encode().toUri();
            JSONObject result = request(uri, HttpMethod.POST, headers(creds));
            if (!"success".equalsIgnoreCase(result.optString("status"))) throw new IllegalArgumentException();
            String consent = result.getString("consentAppId");
            if (consent.isBlank() || consent.length() > 256) throw new IllegalArgumentException();
            return UriComponentsBuilder.fromHttpUrl(AUTH + "/login/consentApp-login")
                .queryParam("consentAppId", consent).build().encode().toUriString();
        } catch (Exception ignored) { throw failure(); }
    }
    public AuthTokenResult consumeConsent(BrokerCredentialsEntity creds, String tokenId) {
        try {
            if (tokenId == null || tokenId.isBlank() || tokenId.length() > 2048) throw new IllegalArgumentException();
            URI uri = UriComponentsBuilder.fromHttpUrl(AUTH + "/app/consumeApp-consent")
                .queryParam("tokenId", "{tokenId}").encode().buildAndExpand(tokenId).toUri();
            return token(request(uri, HttpMethod.GET, headers(creds)), client(creds));
        } catch (Exception ignored) { throw failure(); }
    }
    private String client(BrokerCredentialsEntity creds) {
        if (creds == null || !"Dhan".equalsIgnoreCase(creds.getBrokerName())) throw new IllegalArgumentException();
        String value = secret(creds.getClientCode());
        if (!value.matches("[0-9]{1,20}")) throw new IllegalArgumentException();
        return value;
    }
    private String secret(String encrypted) {
        String value = crypto.decrypt(encrypted);
        if (value == null || value.isBlank()) throw new IllegalArgumentException();
        return value.trim();
    }
    private HttpHeaders headers(BrokerCredentialsEntity creds) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("app_id", secret(creds.getApiKey())); headers.set("app_secret", secret(creds.getSecretKey()));
        return headers;
    }
    private JSONObject request(URI uri, HttpMethod method, HttpHeaders headers) throws Exception {
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        return http.exchange(uri, method, headers);
    }
    private AuthTokenResult token(JSONObject body, String client) {
        if (!client.equals(body.getString("dhanClientId"))) throw new IllegalArgumentException();
        if (body.has("status") && !"success".equalsIgnoreCase(body.optString("status"))) throw new IllegalArgumentException();
        String token = body.getString("accessToken");
        if (token.isBlank() || "null".equalsIgnoreCase(token)) throw new IllegalArgumentException();
        String expiry = body.getString("expiryTime");
        Instant until;
        try { until = OffsetDateTime.parse(expiry).toInstant(); }
        catch (java.time.format.DateTimeParseException ex) {
            until = LocalDateTime.parse(expiry).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
        }
        long seconds = Duration.between(clock.instant(), until).getSeconds();
        if (seconds <= 60) throw new IllegalArgumentException();
        return new AuthTokenResult(token, seconds);
    }
    private IllegalStateException failure() {
        return new IllegalStateException("Dhan authentication failed. Check credentials, TOTP setup and login consent, then try again.");
    }
}
