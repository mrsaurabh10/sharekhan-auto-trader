package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.ShoonyaInstrumentEntity;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ShoonyaInstrumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.StringUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.zip.ZipInputStream;

/** Downloads Shoonya's public daily symbol master; no authenticated Shoonya API is used here. */
@Service
@Slf4j
@RequiredArgsConstructor
public class ShoonyaInstrumentMasterService {
    private static final String MASTER_URL = "https://api.shoonya.com/%s_symbols.txt.zip";
    private final ShoonyaInstrumentRepository repository;
    private final ShoonyaInstrumentMasterWriter writer;

    @Value("${app.shoonya.symbol-master.enabled:true}")
    private boolean scheduledRefreshEnabled;

    @Scheduled(cron = "${app.shoonya.symbol-master.refresh-cron:0 15 8 * * MON-FRI}", zone = "Asia/Kolkata")
    public void refreshMastersEachTradingDay() {
        if (!scheduledRefreshEnabled) return;
        for (String exchange : List.of("NSE", "NFO", "BSE", "BFO")) {
            try {
                int rows = refresh(exchange);
                log.info("Refreshed {} Shoonya {} instrument rows from the daily symbol master", rows, exchange);
            } catch (Exception e) {
                log.error("Shoonya {} symbol-master refresh failed", exchange, e);
            }
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
                writer.replace(normalizedExchange, instruments);
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

    /** Resolves an F&O option from the existing Sharekhan script master to Shoonya's daily symbol master. */
    public Optional<ShoonyaInstrumentEntity> resolveOption(ScriptMasterEntity script) {
        if (script == null || !StringUtils.hasText(script.getOptionType()) || script.getStrikePrice() == null) return Optional.empty();
        String exchange = switch (script.getExchange() == null ? "" : script.getExchange().trim().toUpperCase(Locale.ROOT)) {
            case "NF", "NFO" -> "NFO";
            case "BF", "BFO" -> "BFO";
            default -> null;
        };
        if (exchange == null || !StringUtils.hasText(script.getTradingSymbol())) return Optional.empty();
        Optional<ShoonyaInstrumentEntity> byTradingSymbol = repository
                .findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase(exchange, script.getTradingSymbol().trim());
        if (byTradingSymbol.isPresent()) return byTradingSymbol;

        String expiry = shoonyaExpiry(script.getExpiry());
        if (!StringUtils.hasText(expiry)) return Optional.empty();
        // Sharekhan calls BFO index options SENSEX, while Shoonya's BFO master
        // records their underlying as BSXOPT.  Resolve against the provider's
        // canonical underlying so a valid SENSEX option can be quoted.
        String symbol = shoonyaOptionUnderlyingSymbol(exchange, script.getTradingSymbol());
        return repository.findFirstByExchangeIgnoreCaseAndSymbolIgnoreCaseAndExpiryIgnoreCaseAndOptionTypeIgnoreCaseAndStrikePrice(
                exchange, symbol, expiry, script.getOptionType().trim().toUpperCase(Locale.ROOT), script.getStrikePrice());
    }

    private String shoonyaOptionUnderlyingSymbol(String exchange, String tradingSymbol) {
        if ("BFO".equalsIgnoreCase(exchange) && "SENSEX".equalsIgnoreCase(tradingSymbol == null ? "" : tradingSymbol.trim())) {
            return "BSXOPT";
        }
        return tradingSymbol.trim();
    }

    /** Resolves either a cash or F&O script from the Sharekhan master to Shoonya. */
    public Optional<ShoonyaInstrumentEntity> resolveScript(ScriptMasterEntity script) {
        if (script == null || !StringUtils.hasText(script.getTradingSymbol())) {
            return Optional.empty();
        }
        if (StringUtils.hasText(script.getOptionType()) && script.getStrikePrice() != null) {
            return resolveOption(script);
        }
        String exchange = switch (script.getExchange() == null ? "" : script.getExchange().trim().toUpperCase(Locale.ROOT)) {
            case "NC", "NSE" -> "NSE";
            case "BC", "BSE" -> "BSE";
            case "NF", "NFO" -> "NFO";
            case "BF", "BFO" -> "BFO";
            default -> null;
        };
        if (exchange == null) {
            return Optional.empty();
        }
        String tradingSymbol = script.getTradingSymbol().trim();
        Optional<ShoonyaInstrumentEntity> exact = repository
                .findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase(exchange, tradingSymbol);
        if (exact.isPresent() || tradingSymbol.toUpperCase(Locale.ROOT).endsWith("-EQ")) {
            return exact;
        }
        // Cash symbols in the Sharekhan master are generally bare (for example
        // SWIGGY), while Shoonya's NSE/BSE symbol masters use SWIGGY-EQ.
        return repository.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase(exchange, tradingSymbol + "-EQ");
    }

    private static String shoonyaExpiry(String value) {
        if (!StringUtils.hasText(value)) return null;
        for (DateTimeFormatter formatter : List.of(DateTimeFormatter.ofPattern("dd/MM/uuuu"), DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("dd-MMM-uuuu", Locale.ROOT))) {
            try { return LocalDate.parse(value.trim(), formatter).format(DateTimeFormatter.ofPattern("dd-MMM-uuuu", Locale.ROOT)).toUpperCase(Locale.ROOT); }
            catch (DateTimeParseException ignored) { }
        }
        return value.trim().toUpperCase(Locale.ROOT);
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
                Set<String> instrumentKeys = new HashSet<>();
                String line;
                LocalDateTime fetchedAt = LocalDateTime.now();
                while ((line = reader.readLine()) != null) {
                    List<String> row = csv(line);
                    String token = value(row, columns, "token");
                    String tradingSymbol = value(row, columns, "tradingsymbol");
                    if (!StringUtils.hasText(token) || !StringUtils.hasText(tradingSymbol)) continue;
                    String instrumentKey = exchange + ":" + tradingSymbol.toUpperCase(Locale.ROOT);
                    // The BSE daily master occasionally contains duplicate trading symbols.
                    // Keep the first row: it is the same key used by local resolution and avoids
                    // aborting the entire exchange refresh on the unique database constraint.
                    if (!instrumentKeys.add(instrumentKey)) {
                        log.warn("Ignoring duplicate Shoonya {} instrument key {}", exchange, instrumentKey);
                        continue;
                    }
                    result.add(ShoonyaInstrumentEntity.builder()
                            .instrumentKey(instrumentKey)
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
