package org.com.sharekhan.parser;

import java.util.HashMap;
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
    private static final Pattern EXPIRY_MONTH_AFTER = Pattern.compile(
            "\\b(JANUARY|JAN|FEBRUARY|FEB|MARCH|MAR|APRIL|APR|MAY|JUNE|JUN|JULY|JUL|AUGUST|AUG|SEPTEMBER|SEP|OCTOBER|OCT|NOVEMBER|NOV|DECEMBER|DEC)\\s+EXPIRY\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EXPIRY_MONTH_BEFORE = Pattern.compile(
            "\\bEXPIRY\\s*[:=]?\\s*(JANUARY|JAN|FEBRUARY|FEB|MARCH|MAR|APRIL|APR|MAY|JUNE|JUN|JULY|JUL|AUGUST|AUG|SEPTEMBER|SEP|OCTOBER|OCT|NOVEMBER|NOV|DECEMBER|DEC)\\b",
            Pattern.CASE_INSENSITIVE
    );

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
                    extractLots(normalized),
                    extractExpiryMonth(normalized)
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

        return buildResult(stock, entry, direction, extractLots(text), extractExpiryMonth(text, fields));
    }

    private Map<String, Object> buildResult(String stock, String entry, String direction, Integer lots, Integer expiryMonth) {
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
        if (expiryMonth != null) {
            result.put("expiryMonth", expiryMonth);
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

    private Integer extractExpiryMonth(String text) {
        return extractExpiryMonth(text, Map.of());
    }

    private Integer extractExpiryMonth(String text, Map<String, String> fields) {
        String explicitExpiry = firstPresent(fields, "expiry", "expirymonth");
        Integer fieldMonth = parseMonth(explicitExpiry);
        if (fieldMonth != null) {
            return fieldMonth;
        }

        Matcher afterMatcher = EXPIRY_MONTH_AFTER.matcher(text);
        if (afterMatcher.find()) {
            return parseMonth(afterMatcher.group(1));
        }

        Matcher beforeMatcher = EXPIRY_MONTH_BEFORE.matcher(text);
        if (beforeMatcher.find()) {
            return parseMonth(beforeMatcher.group(1));
        }

        return null;
    }

    private Integer parseMonth(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String token = value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        return monthMap().get(token);
    }

    private Map<String, Integer> monthMap() {
        Map<String, Integer> months = new HashMap<>();
        months.put("JANUARY", 1); months.put("JAN", 1);
        months.put("FEBRUARY", 2); months.put("FEB", 2);
        months.put("MARCH", 3); months.put("MAR", 3);
        months.put("APRIL", 4); months.put("APR", 4);
        months.put("MAY", 5);
        months.put("JUNE", 6); months.put("JUN", 6);
        months.put("JULY", 7); months.put("JUL", 7);
        months.put("AUGUST", 8); months.put("AUG", 8);
        months.put("SEPTEMBER", 9); months.put("SEP", 9);
        months.put("OCTOBER", 10); months.put("OCT", 10);
        months.put("NOVEMBER", 11); months.put("NOV", 11);
        months.put("DECEMBER", 12); months.put("DEC", 12);
        return months;
    }

    private Double parseDouble(String value) {
        try {
            return value == null ? null : Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
