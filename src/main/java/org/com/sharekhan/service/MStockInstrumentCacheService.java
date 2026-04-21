package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.AuthTokenResult;
import org.com.sharekhan.auth.BrokerAuthProvider;
import org.com.sharekhan.auth.BrokerAuthProviderRegistry;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.config.MStockProperties;
import org.com.sharekhan.entity.MStockInstrumentEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.repository.MStockInstrumentRepository;
import org.com.sharekhan.util.CryptoService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

@Service
@Slf4j
@RequiredArgsConstructor
public class MStockInstrumentCacheService {

    private static final String SCRIPT_MASTER_URL = "https://api.mstock.trade/openapi/typea/instruments/scriptmaster";
    private static final int BATCH_SIZE = 1_000;

    private final MStockInstrumentRepository repository;
    private final TokenStoreService tokenStoreService;
    private final BrokerAuthProviderRegistry providerRegistry;
    private final CryptoService cryptoService;
    private final MStockProperties mStockProperties;

    public boolean refreshInstrumentMasterIfEmpty() {
        if (repository.count() > 0) {
            log.info("✅ MStock instrument master already populated. Skipping refresh.");
            return false;
        }
        return refreshInstrumentMaster();
    }

    public boolean refreshInstrumentMaster() {
        Optional<Credentials> credentialsOpt = resolveCredentials();
        if (credentialsOpt.isEmpty()) {
            log.warn("⚠️ Unable to resolve MStock credentials. Skipping instrument master fetch.");
            return false;
        }

        Credentials credentials = credentialsOpt.get();
        DownloadResult result = downloadAndPersist(credentials);
        if (result == DownloadResult.SUCCESS) {
            return true;
        }

        if (result == DownloadResult.UNAUTHORIZED) {
            log.warn("🔄 MStock script master request returned unauthorized. Attempting token refresh.");
            if (attemptTokenRefresh()) {
                credentialsOpt = resolveCredentials();
                if (credentialsOpt.isPresent()) {
                    DownloadResult retry = downloadAndPersist(credentialsOpt.get());
                    return retry == DownloadResult.SUCCESS;
                }
            }
        }

        return false;
    }

    private Optional<Credentials> resolveCredentials() {
        TokenStoreService.TokenInfo tokenInfo = tokenStoreService.getFirstNonExpiredTokenInfo(Broker.MSTOCK);
        String accessToken = null;
        String apiKey = null;
        if (tokenInfo != null) {
            accessToken = tokenInfo.getToken();
            if (tokenInfo.getApiKey() != null) {
                try {
                    apiKey = cryptoService.decrypt(tokenInfo.getApiKey());
                } catch (Exception e) {
                    apiKey = tokenInfo.getApiKey();
                }
            }
        }

        if (accessToken == null) {
            accessToken = tokenStoreService.getAccessToken(Broker.MSTOCK);
        }
        if (!StringUtils.hasText(apiKey)) {
            apiKey = mStockProperties.getApiKey();
        }

        if (!StringUtils.hasText(accessToken)) {
            return Optional.empty();
        }

        return Optional.of(new Credentials(apiKey, accessToken));
    }

    private boolean attemptTokenRefresh() {
        BrokerAuthProvider provider = providerRegistry.getProvider(Broker.MSTOCK);
        if (provider == null) {
            log.warn("⚠️ No MStock auth provider registered; cannot refresh token.");
            return false;
        }
        try {
            AuthTokenResult authTokenResult = provider.loginAndFetchToken();
            if (authTokenResult == null || !StringUtils.hasText(authTokenResult.token())) {
                log.warn("⚠️ MStock auth provider returned empty token.");
                return false;
            }
            tokenStoreService.updateToken(Broker.MSTOCK, authTokenResult.token(), authTokenResult.expiresIn());
            log.info("✅ MStock token refreshed successfully.");
            return true;
        } catch (Exception ex) {
            log.error("❌ Failed to refresh MStock token", ex);
            return false;
        }
    }

    private DownloadResult downloadAndPersist(Credentials credentials) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(SCRIPT_MASTER_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setRequestProperty("Accept", "text/csv");
            conn.setRequestProperty("X-Mirae-Version", "1");
            conn.setRequestProperty("Authorization", buildAuthHeader(credentials));

            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                log.warn("⚠️ Received 401 while fetching MStock script master.");
                return DownloadResult.UNAUTHORIZED;
            }
            if (status != HttpURLConnection.HTTP_OK) {
                String errorBody = readBody(conn.getErrorStream());
                log.error("❌ Failed to download MStock script master ({}): {}", status, errorBody);
                return DownloadResult.FAILED;
            }

            try (InputStream raw = conn.getInputStream();
                 InputStream effectiveStream = wrapStreamIfCompressed(conn, raw);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(effectiveStream, StandardCharsets.UTF_8))) {

                List<MStockInstrumentEntity> records = parseCsv(reader);
                if (records.isEmpty()) {
                    log.warn("⚠️ MStock script master response contained no records.");
                    return DownloadResult.FAILED;
                }

                persistRecords(records);
                log.info("📥 Stored {} MStock instruments from script master.", records.size());
                return DownloadResult.SUCCESS;
            }
        } catch (IOException ex) {
            log.error("❌ IO failure while downloading MStock script master: {}", ex.getMessage(), ex);
            return DownloadResult.FAILED;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private InputStream wrapStreamIfCompressed(HttpURLConnection conn, InputStream raw) throws IOException {
        String encoding = conn.getContentEncoding();
        if (encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip")) {
            return new GZIPInputStream(raw);
        }
        return raw;
    }

    private void persistRecords(List<MStockInstrumentEntity> records) {
        repository.deleteAllInBatch();

        int size = records.size();
        AtomicInteger saved = new AtomicInteger();
        for (int from = 0; from < size; from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, size);
            List<MStockInstrumentEntity> batch = records.subList(from, to);
            repository.saveAll(batch);
            repository.flush();
            saved.addAndGet(batch.size());
            if (saved.get() % (BATCH_SIZE * 5) == 0) {
                log.info("   ↳ Persisted {} instruments so far...", saved.get());
            }
        }
    }

    private List<MStockInstrumentEntity> parseCsv(BufferedReader reader) throws IOException {
        Map<String, MStockInstrumentEntity> deduped = new LinkedHashMap<>();
        String line;
        boolean headerSkipped = false;
        LocalDateTime fetchedAt = LocalDateTime.now();

        while ((line = reader.readLine()) != null) {
            if (!headerSkipped) {
                headerSkipped = true;
                continue;
            }
            if (line.isBlank()) {
                continue;
            }
            line = stripBom(line);
            List<String> fields = parseCsvLine(line);
            if (fields.isEmpty()) {
                continue;
            }
            MStockInstrumentEntity entity = convertRow(fields, fetchedAt);
            if (entity == null || !StringUtils.hasText(entity.getInstrumentKey())) {
                continue;
            }

            MStockInstrumentEntity existing = deduped.get(entity.getInstrumentKey());
            if (existing == null) {
                deduped.put(entity.getInstrumentKey(), entity);
            } else {
                MStockInstrumentEntity preferred = choosePreferred(existing, entity);
                deduped.put(entity.getInstrumentKey(), preferred);
            }
        }
        return new ArrayList<>(deduped.values());
    }

    private String stripBom(String line) {
        if (line != null && !line.isEmpty() && line.charAt(0) == '\uFEFF') {
            return line.substring(1);
        }
        return line;
    }

    private MStockInstrumentEntity convertRow(List<String> fields, LocalDateTime fetchedAt) {
        if (fields.size() < 3) {
            return null;
        }

        String instrumentTokenText = safeField(fields, 0);
        String exchangeToken = safeField(fields, 1);
        String tradingSymbolRaw = safeField(fields, 2);
        String name = safeField(fields, 3);
        String lastPriceText = safeField(fields, 4);
        String expiry = safeField(fields, 5);
        String strikeText = safeField(fields, 6);
        String tickSizeText = safeField(fields, 7);
        String lotSizeText = safeField(fields, 8);
        String instrumentType = safeField(fields, 9);
        String segment = safeField(fields, 10);
        String exchange = safeField(fields, 11);

        if (!StringUtils.hasText(instrumentTokenText) || !StringUtils.hasText(tradingSymbolRaw) || !StringUtils.hasText(exchange)) {
            return null;
        }

        Long instrumentToken = parseLong(instrumentTokenText);
        if (instrumentToken == null) {
            return null;
        }

        String tradingSymbol = tradingSymbolRaw.trim().toUpperCase(Locale.ROOT);
        String normalizedExchange = exchange.trim().toUpperCase(Locale.ROOT);
        tradingSymbol = normalizeSpotTradingSymbol(normalizedExchange, tradingSymbol, instrumentType, segment, strikeText, expiry);
        String instrumentKey = buildInstrumentKey(normalizedExchange, tradingSymbol);
        if (instrumentKey == null) {
            return null;
        }

        return MStockInstrumentEntity.builder()
                .instrumentToken(instrumentToken)
                .instrumentKey(instrumentKey)
                .tradingSymbol(tradingSymbol)
                .name(StringUtils.hasText(name) ? name.trim() : null)
                .exchange(normalizedExchange)
                .segment(StringUtils.hasText(segment) ? segment.trim() : null)
                .instrumentType(StringUtils.hasText(instrumentType) ? instrumentType.trim() : null)
                .exchangeToken(StringUtils.hasText(exchangeToken) ? exchangeToken.trim() : null)
                .lastPrice(parseDouble(lastPriceText))
                .expiry(StringUtils.hasText(expiry) ? expiry.trim() : null)
                .strike(parseDouble(strikeText))
                .tickSize(parseDouble(tickSizeText))
                .lotSize(parseInteger(lotSizeText))
                .fetchedAt(fetchedAt)
                .build();
    }

    private String safeField(List<String> fields, int index) {
        return index < fields.size() ? fields.get(index) : null;
    }

    private String buildInstrumentKey(String exchange, String tradingSymbol) {
        if (!StringUtils.hasText(exchange) || !StringUtils.hasText(tradingSymbol)) {
            return null;
        }
        return exchange.trim().toUpperCase(Locale.ROOT) + ":" + tradingSymbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSpotTradingSymbol(String exchange,
                                              String tradingSymbol,
                                              String instrumentType,
                                              String segment,
                                              String strikeText,
                                              String expiry) {
        if (!StringUtils.hasText(exchange) || !StringUtils.hasText(tradingSymbol)) {
            return tradingSymbol;
        }

        String normalizedExchange = exchange.trim().toUpperCase(Locale.ROOT);
        if (!(normalizedExchange.equals("NSE") || normalizedExchange.equals("BSE"))) {
            return tradingSymbol;
        }

        if (!isLikelySpotEquity(instrumentType, segment, strikeText, expiry)) {
            return tradingSymbol;
        }

        if (normalizedExchange.equals("NSE") && !tradingSymbol.endsWith("-EQ")) {
            return tradingSymbol + "-EQ";
        }
        if (normalizedExchange.equals("BSE") && !tradingSymbol.endsWith("-A")) {
            return tradingSymbol + "-A";
        }
        return tradingSymbol;
    }

    private boolean isLikelySpotEquity(String instrumentType,
                                       String segment,
                                       String strikeText,
                                       String expiry) {
        boolean hasStrike = StringUtils.hasText(strikeText);
        boolean hasExpiry = StringUtils.hasText(expiry);
        if (hasStrike || hasExpiry) {
            return false;
        }

        if (StringUtils.hasText(instrumentType)) {
            String normalized = instrumentType.trim().toUpperCase(Locale.ROOT);
            if (normalized.contains("FUT") || normalized.contains("OPT") || normalized.contains("PE") || normalized.contains("CE")) {
                return false;
            }
            if (normalized.equals("EQ") || normalized.equals("EQUITY")) {
                return true;
            }
        }

        if (StringUtils.hasText(segment)) {
            String normalizedSegment = segment.trim().toUpperCase(Locale.ROOT);
            if (normalizedSegment.contains("DERIV")) {
                return false;
            }
            if (normalizedSegment.contains("CASH")) {
                return true;
            }
        }

        // Default to true when we cannot confidently classify it as derivative.
        return true;
    }

    private MStockInstrumentEntity choosePreferred(MStockInstrumentEntity existing, MStockInstrumentEntity candidate) {
        if (existing == null) return candidate;
        if (candidate == null) return existing;
        if (Objects.equals(existing.getInstrumentToken(), candidate.getInstrumentToken())) {
            return existing;
        }

        int existingScore = score(existing);
        int candidateScore = score(candidate);
        if (candidateScore > existingScore) {
            log.debug("Replacing duplicate instrument {} (token {} -> {}) with richer metadata.",
                    existing.getInstrumentKey(), existing.getInstrumentToken(), candidate.getInstrumentToken());
            return candidate;
        }
        return existing;
    }

    private int score(MStockInstrumentEntity entity) {
        int score = 0;
        if (StringUtils.hasText(entity.getInstrumentType())) score += 2;
        if (StringUtils.hasText(entity.getSegment())) score += 2;
        if (StringUtils.hasText(entity.getExpiry())) score += 3;
        if (entity.getStrike() != null) score += 2;
        if (entity.getLotSize() != null) score += 1;
        if (entity.getTickSize() != null) score += 1;
        if (entity.getLastPrice() != null) score += 1;
        if (StringUtils.hasText(entity.getName())) score += 1;
        if (StringUtils.hasText(entity.getExchangeToken())) score += 1;
        return score;
    }

    private Double parseDouble(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer parseInteger(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Long parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private List<String> parseCsvLine(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                tokens.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        tokens.add(current.toString());
        return tokens;
    }

    private String buildAuthHeader(Credentials credentials) {
        if (StringUtils.hasText(credentials.apiKey())) {
            return "token " + credentials.apiKey() + ":" + credentials.accessToken();
        }
        return "token " + credentials.accessToken();
    }

    private String readBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString().trim();
        }
    }

    private enum DownloadResult {
        SUCCESS,
        UNAUTHORIZED,
        FAILED
    }

    private record Credentials(String apiKey, String accessToken) {
    }
}
