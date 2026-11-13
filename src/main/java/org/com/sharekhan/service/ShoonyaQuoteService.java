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
    private record ExactIdentity(String exchange,String token,String tradingSymbol) { }
    private record CachedIdentity(ExactIdentity identity,java.time.Instant validatedAt) { }
    private final java.util.Map<String,CachedIdentity> exactIdentities=new java.util.concurrent.ConcurrentHashMap<>();
    private String exactKey(ScriptMasterEntity s){return java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))+"|"+s.getScripCode()+"|"+s.getExchange()+"|"+s.getTradingSymbol()+"|"+s.getExpiry()+"|"+s.getStrikePrice()+"|"+s.getOptionType()+"|"+s.getLotSize();}
    public void prepareExactQuote(ScriptMasterEntity script){exactIdentity(script);}
    private Optional<ExactIdentity> exactIdentity(ScriptMasterEntity script){
        if(script==null)return Optional.empty();var now=java.time.Instant.now();String key=exactKey(script);
        var cached=exactIdentities.get(key);
        if(cached!=null&&cached.validatedAt().plusSeconds(60).isAfter(now))return Optional.of(cached.identity());
        exactIdentities.entrySet().removeIf(e->e.getValue().validatedAt().plusSeconds(60).isBefore(now));
        return instrumentMasterService.resolveScript(script).map(row->{
            var identity=new ExactIdentity(row.getExchange(),row.getToken(),row.getTradingSymbol());
            if(exactIdentities.size()<2048)exactIdentities.put(key,new CachedIdentity(identity,now));return identity;
        });
    }


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
                            Double bestAsk, Double bidQuantity, Double askQuantity) {
        public LiveQuote(String symbol,String token,String returnedSymbol,String returnedToken,Double ltp,Double bid,Double ask) {
            this(symbol,token,returnedSymbol,returnedToken,ltp,bid,ask,null,null);
        }
        /** Convenience constructor retained for tests and explicitly trusted callers. */
        public LiveQuote(String tradingSymbol, String token, Double lastPrice, Double bestBid, Double bestAsk) {
            this(tradingSymbol, token, tradingSymbol, token, lastPrice, bestBid, bestAsk);
        }
        public boolean hasUsablePrice() { return usable(lastPrice) || usable(bestAsk) || usable(bestBid); }
        public Double referencePrice() { return usable(lastPrice) ? lastPrice : usable(bestAsk) ? bestAsk : bestBid; }
        public boolean hasConfirmedIdentity() {
            return StringUtils.hasText(tradingSymbol) && StringUtils.hasText(token)
                    && StringUtils.hasText(returnedTradingSymbol)
                    && StringUtils.hasText(returnedToken)
                    && token.equalsIgnoreCase(returnedToken)
                    && (tradingSymbol.equalsIgnoreCase(returnedTradingSymbol) || sameIndexIdentity());
        }
        private boolean sameIndexIdentity() {
            // Accept aliases only for known index tokens and matching index names.
            // An option response must still match the exact requested contract.
            return switch (token) {
                case "26000" -> niftyName(tradingSymbol) && niftyName(returnedTradingSymbol);
                case "26009" -> bankNiftyName(tradingSymbol) && bankNiftyName(returnedTradingSymbol);
                default -> false;
            };
        }
        private static boolean niftyName(String symbol) {
            return java.util.Set.of("NIFTY", "NIFTY50", "NIFTYINDEX").contains(normalizeIndexName(symbol));
        }
        private static boolean bankNiftyName(String symbol) {
            return java.util.Set.of("BANKNIFTY", "NIFTYBANK").contains(normalizeIndexName(symbol));
        }
        private static String normalizeIndexName(String symbol) {
            return symbol.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        }
        private static boolean usable(Double value) { return value != null && Double.isFinite(value) && value > 0d; }
    }

    public JSONObject getQuotes(String exchange, String token, String symbol) {
        return getQuotes(exchange, token, symbol, true);
    }

    private JSONObject getQuotes(String exchange, String token, String symbol, boolean allowLogin) {
        if (!StringUtils.hasText(exchange)) throw new IllegalArgumentException("exchange is required");
        if (!StringUtils.hasText(token)) token = instrumentMasterService.resolveToken(exchange, symbol);
        String sessionToken = tokenStoreService.getAccessToken(Broker.SHOONYA);
        if (!StringUtils.hasText(sessionToken)) {
            // The access token is persisted; reload it after a service/container restart before re-authenticating.
            tokenStoreService.loadFromDb(Broker.SHOONYA);
            sessionToken = tokenStoreService.getAccessToken(Broker.SHOONYA);
        }
        if (!StringUtils.hasText(sessionToken)) {
            if (!allowLogin) throw new IllegalStateException("Shoonya saved session unavailable; quote read will not authenticate");
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
        return post(request, sessionToken, quoteRequestId, allowLogin);
    }

    /** Fetches a current option quote using the persisted Shoonya symbol master mapping. */
    public Optional<LiveQuote> getOptionQuote(ScriptMasterEntity script) {
        return getQuote(script);
    }

    /** Fetches a current quote using the provider master or a verified index mapping. */
    public Optional<LiveQuote> getQuote(ScriptMasterEntity script) {
        Optional<ShoonyaInstrumentEntity> instrument = instrumentMasterService.resolveScript(script);
        if (instrument.isEmpty()) return Optional.empty();
        ShoonyaInstrumentEntity resolved = instrument.get();
        try {
            JSONObject quote = getQuotes(resolved.getExchange(), resolved.getToken(), null, false);
            if (!"Ok".equalsIgnoreCase(quote.optString("stat"))) return Optional.empty();
            LiveQuote result = new LiveQuote(resolved.getTradingSymbol(), resolved.getToken(),
                    firstText(quote, "tsym", "trading_symbol"), firstText(quote, "tk", "token"),
                    decimal(quote, "lp"), decimal(quote, "bp1"), decimal(quote, "sp1"), decimal(quote, "bq1"), decimal(quote, "sq1"));
            return result.hasUsablePrice() ? Optional.of(result) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Read-only exact snapshot for strict consumers; never logs in or refreshes credentials. */
    public record ExactQuote(LiveQuote quote, String exchange, java.time.Instant receivedAt,
                             java.time.Instant providerUpdatedAt, java.time.Instant providerSnapshotAt) {
        public ExactQuote(LiveQuote quote,String exchange,java.time.Instant receivedAt,java.time.Instant providerUpdatedAt){
            this(quote,exchange,receivedAt,providerUpdatedAt,providerUpdatedAt);
        }
    }

    /** GetQuotes is a current REST snapshot; lut is the last change, not the response time. */
    static java.time.Instant snapshotTime(JSONObject raw,java.time.Instant lastChange){
        if(!raw.has("request_time"))return lastChange;
        try{return java.time.LocalDateTime.parse(raw.getString("request_time"),
                java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss dd-MM-uuuu",java.util.Locale.ROOT)
                        .withResolverStyle(java.time.format.ResolverStyle.STRICT))
                .atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();}
        catch(RuntimeException invalid){return null;}
    }

    public Optional<ExactQuote> getExactQuote(ScriptMasterEntity script) {
        var mapped = exactIdentity(script);
        if (mapped.isEmpty()) return Optional.empty();
        var expected = mapped.get();
        try {
            var raw = getQuotes(expected.exchange(), expected.token(), null, false);
            if (!"Ok".equalsIgnoreCase(raw.optString("stat"))
                    || !expected.exchange().equalsIgnoreCase(raw.optString("exch"))) return Optional.empty();
            var quote = new LiveQuote(expected.tradingSymbol(), expected.token(),
                    firstText(raw, "tsym", "trading_symbol"), firstText(raw, "tk", "token"),
                    decimal(raw, "lp"), decimal(raw, "bp1"), decimal(raw, "sp1"), decimal(raw, "bq1"), decimal(raw, "sq1"));
            if (!quote.hasConfirmedIdentity()) return Optional.empty();
            java.time.Instant updated = null;
            if (raw.has("lut")) {
                try { updated = java.time.Instant.ofEpochSecond(Long.parseLong(raw.optString("lut"))); }
                catch (RuntimeException invalidTime) { return Optional.empty(); }
            }
            return Optional.of(new ExactQuote(quote, expected.exchange(), java.time.Instant.now(), updated,snapshotTime(raw,updated)));
        } catch (RuntimeException unavailable) {
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

    private JSONObject post(JSONObject request, String sessionToken, String quoteRequestId, boolean interactive) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(properties.getApiUrl() + "/GetQuotes").toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(interactive ? 15_000 : 500);
            connection.setReadTimeout(interactive ? 30_000 : 750);
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
