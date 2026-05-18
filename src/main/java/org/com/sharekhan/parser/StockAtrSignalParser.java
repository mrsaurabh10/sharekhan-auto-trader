package org.com.sharekhan.parser;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StockAtrSignalParser implements TradingSignalParser {

    private static final Pattern ONE_LINE = Pattern.compile(
            "^(?:STOCK\\s+)?ATR(?:\\s+TRADE)?\\s+([A-Z0-9_\\-]+)\\s+(LONG|SHORT|BUY|SELL|BULLISH|BEARISH)\\s+(?:ABOVE\\s+|BELOW\\s+|ENTRY\\s+)?([0-9]+(?:\\.[0-9]+)?)(?:\\s+.*)?$",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern KEY_VALUE = Pattern.compile("\\b([A-Za-z][A-Za-z0-9_]*)\\s*[:=]\\s*([^\\n,]+)");
    private static final Pattern LOTS = Pattern.compile("\\b(?:LOTS?|QTY|QUANTITY)\\s*[:=]?\\s*(\\d+)\\b", Pattern.CASE_INSENSITIVE);

    @Override
    public Map<String, Object> parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String normalized = text.replace("\n", " ").replaceAll("\\s+", " ").trim();
        Matcher oneLineMatcher = ONE_LINE.matcher(normalized);
        if (oneLineMatcher.matches()) {
            return buildResult(
                    oneLineMatcher.group(1),
                    oneLineMatcher.group(3),
                    oneLineMatcher.group(2),
                    extractLots(normalized)
            );
        }

        String upper = text.toUpperCase(Locale.ROOT);
        if (!upper.contains("ATR")) {
            return null;
        }

        Map<String, String> fields = extractFields(text);
        String stock = firstPresent(fields, "stock", "symbol", "instrument");
        String entry = firstPresent(fields, "entry", "entryprice", "price");
        String direction = firstPresent(fields, "direction", "side", "bias");

        if (stock == null || entry == null || direction == null) {
            return null;
        }

        return buildResult(stock, entry, direction, extractLots(text));
    }

    private Map<String, Object> buildResult(String stock, String entry, String direction, Integer lots) {
        Double entryPrice = parseDouble(entry);
        if (stock == null || direction == null || entryPrice == null) {
            return null;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "telegram-atr");
        result.put("stockAtrTrade", true);
        result.put("stock", stock.trim().toUpperCase(Locale.ROOT));
        result.put("entry", entryPrice);
        result.put("direction", direction.trim().toUpperCase(Locale.ROOT));
        result.put("intraday", true);
        if (lots != null && lots > 0) {
            result.put("quantity", lots);
        }
        return result;
    }

    private Map<String, String> extractFields(String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher matcher = KEY_VALUE.matcher(text);
        while (matcher.find()) {
            String key = matcher.group(1).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            String value = matcher.group(2).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                fields.put(key, value);
            }
        }
        return fields;
    }

    private String firstPresent(Map<String, String> fields, String... keys) {
        for (String key : keys) {
            String value = fields.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Integer extractLots(String text) {
        Matcher matcher = LOTS.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double parseDouble(String value) {
        try {
            return value == null ? null : Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
