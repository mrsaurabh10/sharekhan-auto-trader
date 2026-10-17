package org.com.sharekhan.dhan;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.*;
import java.util.*;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.springframework.stereotype.Service;

/** Exact Dhan master mapping. Never reuses another broker's numeric scrip code. */
@Service
public class DhanInstrumentResolver {
    public record Instrument(String segment, String securityId, int lotSize, BigDecimal tickSize, long freezeQuantity) {}
    private record Snapshot(LocalDate day, Map<String,List<Instrument>> rows) {}
    private volatile Snapshot snapshot;
    private static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Kolkata")); }

    public Instrument resolve(TriggeredTradeSetupEntity trade) {
        if (trade == null) throw new IllegalArgumentException("Trade required");
        String segment = segment(trade.getExchange());
        boolean derivative = segment.endsWith("FNO");
        String key = key(segment, trade.getSymbol(), derivative ? expiry(trade.getExpiry()).toString() : "",
            derivative ? trade.getStrikePrice() : null, derivative ? trade.getOptionType() : "");
        if (derivative && expiry(trade.getExpiry()).isBefore(today())) throw new IllegalArgumentException("Expired Dhan contract");
        List<Instrument> matches = current().rows().getOrDefault(key,List.of());
        if (matches.size() != 1) throw new IllegalArgumentException("Dhan instrument missing or ambiguous in current master");
        return matches.getFirst();
    }
    private Snapshot current() {
        Snapshot current = snapshot;
        if (current != null && current.day().equals(today())) return current;
        synchronized (this) {
            current = snapshot;
            if (current != null && current.day().equals(today())) return current;
            try {
                var connection = (HttpURLConnection) URI.create("https://images.dhan.co/api-data/api-scrip-master-detailed.csv").toURL().openConnection();
                connection.setConnectTimeout(15_000); connection.setReadTimeout(120_000); connection.setInstanceFollowRedirects(false);
                try {
                    if (connection.getResponseCode() != 200) throw new IOException();
                    try (var reader = new InputStreamReader(connection.getInputStream(),StandardCharsets.UTF_8)) {
                        return load(reader, today());
                    }
                } finally { connection.disconnect(); }
            } catch (Exception ignored) { throw new IllegalStateException("Current Dhan instrument master unavailable; order not sent"); }
        }
    }
    // Package-visible for fixture tests; builds a complete snapshot before swapping it in.
    synchronized Snapshot load(Reader input, LocalDate day) throws IOException {
        BufferedReader reader = new BufferedReader(input);
        String header = reader.readLine();
        if (header == null) throw new IOException("Empty Dhan master");
        List<String> columns = csv(header.replace("\ufeff", ""));
        for (String required : List.of("EXCH_ID","SEGMENT","SECURITY_ID","INSTRUMENT","UNDERLYING_SYMBOL","SYMBOL_NAME","LOT_SIZE","SM_EXPIRY_DATE","STRIKE_PRICE","OPTION_TYPE","TICK_SIZE","SERIES","SM_FREEZE_QTY"))
            if (!columns.contains(required)) throw new IOException("Unsupported Dhan master columns");
        Map<String,List<Instrument>> index = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            List<String> row = csv(line);
            try {
                String exchange = field(columns,row,"EXCH_ID"), type = field(columns,row,"INSTRUMENT"), rawSegment = field(columns,row,"SEGMENT");
                if (!Set.of("NSE","BSE").contains(exchange)) continue;
                boolean cash = "E".equals(rawSegment) && "EQUITY".equals(type) && ("BSE".equals(exchange) || "EQ".equals(field(columns,row,"SERIES")));
                boolean option = "D".equals(rawSegment) && Set.of("OPTIDX","OPTSTK").contains(type);
                if (!cash && !option) continue;
                String segment = exchange + (cash ? "_EQ" : "_FNO");
                String symbol = field(columns,row,"UNDERLYING_SYMBOL");
                String exp = option ? expiry(field(columns,row,"SM_EXPIRY_DATE")).toString() : "";
                if (option && LocalDate.parse(exp).isBefore(day)) continue;
                Double strike = option ? Double.valueOf(field(columns,row,"STRIKE_PRICE")) : null;
                String optionType = option ? field(columns,row,"OPTION_TYPE") : "";
                if (option && !Set.of("CE","PE").contains(optionType)) continue;
                String security = field(columns,row,"SECURITY_ID");
                int lot = new BigDecimal(field(columns,row,"LOT_SIZE")).intValueExact();
                BigDecimal tick = new BigDecimal(field(columns,row,"TICK_SIZE")).movePointLeft(2); // Master ticks are paise, order prices are rupees.
                long freeze = new BigDecimal(field(columns,row,"SM_FREEZE_QTY")).longValueExact();
                if (cash && "BSE".equals(exchange) && freeze == -1) freeze = Integer.MAX_VALUE; // No freeze limit supplied for BSE cash.
                if (!security.matches("[0-9]+") || lot <= 0 || (cash && lot != 1) || tick.signum() <= 0 || freeze <= 0) continue;
                var instrument = new Instrument(segment,security,lot,tick,freeze);
                String key = key(segment,symbol,exp,strike,optionType);
                List<Instrument> candidates = index.computeIfAbsent(key,k->new ArrayList<>());
                if (!candidates.contains(instrument)) candidates.add(instrument);
            } catch (RuntimeException ignored) { /* Bad rows are never guessed or selected. */ }
        }
        if (index.isEmpty()) throw new IOException("Dhan master has no supported instruments");
        Map<String,List<Instrument>> immutable = new HashMap<>();
        index.forEach((key,value)->immutable.put(key,List.copyOf(value)));
        var next = new Snapshot(day,Map.copyOf(immutable)); snapshot = next; return next;
    }
    public static String segment(String exchange) {
        return switch (exchange == null ? "" : exchange.trim().toUpperCase(Locale.ROOT)) {
            case "NC", "NSE" -> "NSE_EQ";
            case "BC", "BSE" -> "BSE_EQ";
            case "NF", "NFO" -> "NSE_FNO";
            case "BF", "BFO" -> "BSE_FNO";
            default -> throw new IllegalArgumentException("Unsupported Dhan exchange");
        };
    }
    private static String key(String segment, String symbol, String exp, Double strike, String type) {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException();
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        if (segment.endsWith("_EQ") && normalized.endsWith("-EQ")) normalized = normalized.substring(0,normalized.length()-3);
        if (segment.endsWith("FNO") && (strike == null || !Double.isFinite(strike) || strike <= 0 || !Set.of("CE","PE").contains(type))) throw new IllegalArgumentException("Option contract required");
        return segment+"|"+normalized+"|"+exp+"|"+(strike == null ? "" : BigDecimal.valueOf(strike).stripTrailingZeros().toPlainString())+"|"+type;
    }
    private static LocalDate expiry(String value) {
        if (value == null) throw new IllegalArgumentException("Expiry required");
        for (DateTimeFormatter fmt : List.of(DateTimeFormatter.ISO_LOCAL_DATE,DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT))) {
            try { return LocalDate.parse(value.trim(),fmt); } catch (DateTimeParseException ignored) {}
        }
        throw new IllegalArgumentException("Invalid expiry");
    }
    private static String field(List<String> columns,List<String> row,String name) { return row.get(columns.indexOf(name)).trim(); }
    private static List<String> csv(String line) {
        var result = new ArrayList<String>(); var value = new StringBuilder(); boolean quote = false;
        for (int i=0;i<line.length();i++) {
            char c = line.charAt(i);
            if (c=='"') { if (quote && i+1<line.length() && line.charAt(i+1)=='"') { value.append(c);i++; } else quote=!quote; }
            else if (c==',' && !quote) { result.add(value.toString());value.setLength(0); } else value.append(c);
        }
        if (quote) throw new IllegalArgumentException("Malformed CSV");
        result.add(value.toString());return result;
    }
}
