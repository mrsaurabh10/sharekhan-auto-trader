package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.AuthTokenResult;
import org.com.sharekhan.auth.BrokerAuthProvider;
import org.com.sharekhan.auth.BrokerAuthProviderRegistry;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.config.ShoonyaProperties;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.ShoonyaInstrumentEntity;
import org.com.sharekhan.enums.Broker;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** Read-only Shoonya client. It intentionally exposes only NorenWClientAPI/GetQuotes. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShoonyaQuoteService {
    private final ShoonyaProperties properties;
    private final TokenStoreService tokenStoreService;
    private final BrokerAuthProviderRegistry providerRegistry;
    private final ShoonyaInstrumentMasterService instrumentMasterService;

    /**
     * A quote retains both the identity we requested and the identity echoed by
     * Shoonya.  Never infer the latter from the request: that would hide an
     * incorrect token/symbol routing response.
     */
    public record LiveQuote(String tradingSymbol,
                            String token,
                            String returnedTradingSymbol,
                            String returnedToken,
                            Double lastPrice,
                            Double bestBid,
                            Double bestAsk) {
        /** Convenience constructor retained for tests and explicitly trusted callers. */
        public LiveQuote(String tradingSymbol, String token, Double lastPrice, Double bestBid, Double bestAsk) {
            this(tradingSymbol, token, tradingSymbol, token, lastPrice, bestBid, bestAsk);
        }
        public boolean hasUsablePrice() { return usable(lastPrice) || usable(bestAsk) || usable(bestBid); }
        public Double referencePrice() { return usable(lastPrice) ? lastPrice : usable(bestAsk) ? bestAsk : bestBid; }
        public boolean hasConfirmedIdentity() {
            return StringUtils.hasText(returnedTradingSymbol)
                    && StringUtils.hasText(returnedToken)
                    && tradingSymbol.equalsIgnoreCase(returnedTradingSymbol)
                    && token.equalsIgnoreCase(returnedToken);
        }
        private static boolean usable(Double value) { return value != null && Double.isFinite(value) && value > 0d; }
    }

    public JSONObject getQuotes(String exchange, String token, String symbol) {
        if (!StringUtils.hasText(exchange)) throw new IllegalArgumentException("exchange is required");
        if (!StringUtils.hasText(token)) token = instrumentMasterService.resolveToken(exchange, symbol);
        String sessionToken = tokenStoreService.getAccessToken(Broker.SHOONYA);
        if (!StringUtils.hasText(sessionToken)) {
            // The access token is persisted; reload it after a service/container restart before re-authenticating.
            tokenStoreService.loadFromDb(Broker.SHOONYA);
            sessionToken = tokenStoreService.getAccessToken(Broker.SHOONYA);
        }
        if (!StringUtils.hasText(sessionToken)) {
            BrokerAuthProvider provider = providerRegistry.getProvider(Broker.SHOONYA);
            if (provider == null) throw new IllegalStateException("Shoonya authentication provider is not registered");
            AuthTokenResult authenticated = provider.loginAndFetchToken();
            tokenStoreService.updateToken(Broker.SHOONYA, authenticated.token(), authenticated.expiresIn());
            sessionToken = authenticated.token();
        }
        String quoteRequestId = UUID.randomUUID().toString();
        JSONObject request = new JSONObject().put("uid", properties.getUid()).put("exch", exchange).put("token", token);
        // This is the wire-request identity, recorded immediately before the HTTP call.
        // Do not add uid or Authorization to this log.
        log.info("SHOONYA_QUOTE_REQUEST | requestId={} | exchange={} | token={}", quoteRequestId, exchange, token);
        return post(request, sessionToken, quoteRequestId);
    }

    /** Fetches a current option quote using the persisted Shoonya symbol master mapping. */
    public Optional<LiveQuote> getOptionQuote(ScriptMasterEntity script) {
        return getQuote(script);
    }

    /** Fetches a current quote for any script represented in the Shoonya symbol master. */
    public Optional<LiveQuote> getQuote(ScriptMasterEntity script) {
        Optional<ShoonyaInstrumentEntity> instrument = instrumentMasterService.resolveScript(script);
        if (instrument.isEmpty()) return Optional.empty();
        ShoonyaInstrumentEntity resolved = instrument.get();
        try {
            JSONObject quote = getQuotes(resolved.getExchange(), resolved.getToken(), null);
            if (!"Ok".equalsIgnoreCase(quote.optString("stat"))) return Optional.empty();
            LiveQuote result = new LiveQuote(resolved.getTradingSymbol(), resolved.getToken(),
                    firstText(quote, "tsym", "trading_symbol"), firstText(quote, "tk", "token"),
                    decimal(quote, "lp"), decimal(quote, "bp1"), decimal(quote, "sp1"));
            return result.hasUsablePrice() ? Optional.of(result) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Double decimal(JSONObject quote, String key) {
        String value = quote.optString(key, "");
        try { return StringUtils.hasText(value) ? Double.valueOf(value) : null; }
        catch (NumberFormatException ignored) { return null; }
    }

    private static String firstText(JSONObject quote, String... keys) {
        for (String key : keys) {
            String value = quote.optString(key, "");
            if (StringUtils.hasText(value)) return value.trim();
        }
        return null;
    }

    private JSONObject post(JSONObject request, String sessionToken, String quoteRequestId) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(properties.getApiUrl() + "/GetQuotes").toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            connection.setRequestProperty("Authorization", "Bearer " + sessionToken);
            String body = "jData=" + request;
            try (OutputStream output = connection.getOutputStream()) { output.write(body.getBytes(StandardCharsets.UTF_8)); }
            int status = connection.getResponseCode();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream(), StandardCharsets.UTF_8))) {
                String response = reader.lines().collect(Collectors.joining());
                if (response.isBlank()) throw new IllegalStateException("Shoonya GetQuotes returned HTTP " + status + " with no body");
                JSONObject parsed = new JSONObject(response);
                log.info("SHOONYA_QUOTE_RESPONSE | requestId={} | httpStatus={} | returnedExchange={} | returnedToken={} | returnedSymbol={} | status={} | ltp={}",
                        quoteRequestId, status, firstText(parsed, "exch", "exchange"), firstText(parsed, "tk", "token"),
                        firstText(parsed, "tsym", "trading_symbol"), parsed.optString("stat", ""), decimal(parsed, "lp"));
                return parsed;
            }
        } catch (Exception e) {
            log.error("SHOONYA_QUOTE_FAILURE | requestId={} | message={}", quoteRequestId, e.getMessage());
            throw new IllegalStateException("Shoonya GetQuotes failed: " + e.getMessage(), e);
        }
    }
}
