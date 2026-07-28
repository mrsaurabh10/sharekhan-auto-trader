package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.ShoonyaInstrumentEntity;
import org.com.sharekhan.repository.ShoonyaInstrumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipInputStream;

/** Downloads Shoonya's public daily symbol master; no authenticated Shoonya API is used here. */
@Service
@Slf4j
@RequiredArgsConstructor
public class ShoonyaInstrumentMasterService {
    private static final String MASTER_URL = "https://api.shoonya.com/%s_symbols.txt.zip";
    private final ShoonyaInstrumentRepository repository;

    @Value("${app.shoonya.symbol-master.enabled:true}")
    private boolean scheduledRefreshEnabled;

    @Scheduled(cron = "${app.shoonya.symbol-master.refresh-cron:0 15 8 * * MON-FRI}", zone = "Asia/Kolkata")
    public void refreshNfoMasterEachTradingDay() {
        if (!scheduledRefreshEnabled) return;
        try {
            int rows = refresh("NFO");
            log.info("Refreshed {} Shoonya NFO instrument rows from the daily symbol master", rows);
        } catch (Exception e) {
            log.error("Shoonya NFO symbol-master refresh failed", e);
        }
    }

    public int refresh(String exchange) {
        String normalizedExchange = normalizeExchange(exchange);
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(MASTER_URL.formatted(normalizedExchange)).toURL().openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(120_000);
            connection.setRequestProperty("Accept", "application/zip");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("Shoonya symbol master download returned HTTP " + connection.getResponseCode());
            }
            try (InputStream input = connection.getInputStream()) {
                List<ShoonyaInstrumentEntity> instruments = parse(normalizedExchange, input);
                if (instruments.isEmpty()) throw new IllegalStateException("Shoonya " + normalizedExchange + " symbol master contained no valid rows");
                replace(normalizedExchange, instruments);
                return instruments.size();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to download Shoonya " + normalizedExchange + " symbol master", e);
        }
    }

    public String resolveToken(String exchange, String tradingSymbol) {
        String normalizedExchange = normalizeExchange(exchange);
        if (!StringUtils.hasText(tradingSymbol)) throw new IllegalArgumentException("symbol is required when token is not provided");
        return repository.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase(normalizedExchange, tradingSymbol.trim())
                .map(ShoonyaInstrumentEntity::getToken)
                .orElseThrow(() -> new IllegalArgumentException("Shoonya symbol not found in local " + normalizedExchange
                        + " master: " + tradingSymbol + ". Refresh the symbol master first."));
    }

    @Transactional
    protected void replace(String exchange, List<ShoonyaInstrumentEntity> instruments) {
        repository.deleteByExchangeIgnoreCase(exchange);
        repository.saveAll(instruments);
    }

    static List<ShoonyaInstrumentEntity> parse(String exchange, InputStream zip) throws IOException {
        try (ZipInputStream entries = new ZipInputStream(zip, StandardCharsets.UTF_8)) {
            if (entries.getNextEntry() == null) throw new IOException("Zip file has no symbol master entry");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(entries, StandardCharsets.UTF_8))) {
                String header = reader.readLine();
                if (!StringUtils.hasText(header)) throw new IOException("Symbol master header is missing");
                Map<String, Integer> columns = columns(header);
                require(columns, "token", "tradingsymbol");
                List<ShoonyaInstrumentEntity> result = new ArrayList<>();
                String line;
                LocalDateTime fetchedAt = LocalDateTime.now();
                while ((line = reader.readLine()) != null) {
                    List<String> row = csv(line);
                    String token = value(row, columns, "token");
                    String tradingSymbol = value(row, columns, "tradingsymbol");
                    if (!StringUtils.hasText(token) || !StringUtils.hasText(tradingSymbol)) continue;
                    result.add(ShoonyaInstrumentEntity.builder()
                            .instrumentKey(exchange + ":" + tradingSymbol.toUpperCase(Locale.ROOT))
                            .exchange(exchange).token(token).tradingSymbol(tradingSymbol)
                            .symbol(value(row, columns, "symbol")).expiry(value(row, columns, "expiry"))
                            .instrument(value(row, columns, "instrument")).optionType(value(row, columns, "optiontype"))
                            .strikePrice(decimal(value(row, columns, "strikeprice"))).lotSize(integer(value(row, columns, "lotsize")))
                            .tickSize(decimal(value(row, columns, "ticksize"))).fetchedAt(fetchedAt).build());
                }
                return result;
            }
        }
    }

    private static String normalizeExchange(String exchange) {
        String value = exchange == null ? "" : exchange.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("NSE", "NFO", "BSE", "BFO", "CDS", "MCX").contains(value)) throw new IllegalArgumentException("Unsupported Shoonya exchange: " + exchange);
        return value;
    }
    private static Map<String, Integer> columns(String header) { Map<String, Integer> found = new HashMap<>(); List<String> values = csv(header); for (int i = 0; i < values.size(); i++) found.put(values.get(i).replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT), i); return found; }
    private static void require(Map<String, Integer> columns, String... names) { for (String name : names) if (!columns.containsKey(name)) throw new IllegalArgumentException("Shoonya symbol master misses required column " + name); }
    private static String value(List<String> row, Map<String, Integer> columns, String name) { Integer index = columns.get(name); return index == null || index >= row.size() ? null : row.get(index).trim(); }
    private static Double decimal(String value) { try { return StringUtils.hasText(value) ? Double.valueOf(value) : null; } catch (NumberFormatException e) { return null; } }
    private static Integer integer(String value) { try { return StringUtils.hasText(value) ? Integer.valueOf(value) : null; } catch (NumberFormatException e) { return null; } }
    private static List<String> csv(String line) { List<String> values = new ArrayList<>(); StringBuilder value = new StringBuilder(); boolean quoted = false; for (int i = 0; i < line.length(); i++) { char c = line.charAt(i); if (c == '"') { if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { value.append(c); i++; } else quoted = !quoted; } else if (c == ',' && !quoted) { values.add(value.toString()); value.setLength(0); } else value.append(c); } values.add(value.toString()); return values; }
}
