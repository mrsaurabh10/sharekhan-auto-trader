package org.com.sharekhan.parser;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SpotTelegramSignalParser implements TradingSignalParser {

    private static final Pattern TRADE_PATTERN = Pattern.compile(
            "^(?:(BUY|SELL)\\s+)?([A-Z0-9\\s]+?)\\s+(\\d+(?:\\.\\d+)?)\\s+(CE|PE)\\s+(?:ABOVE|BELOW)\\s+SPOT\\s+(\\d+(?:\\.\\d+)?)\\b",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public Map<String, Object> parse(String text) {
        if (text == null || text.isBlank() || !containsSpot(text)) {
            return null;
        }

        List<String> lines = Arrays.stream(text.split("\\R"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        String tradeLine = lines.stream()
                .filter(line -> TRADE_PATTERN.matcher(line).find())
                .findFirst()
                .orElse(null);
        if (tradeLine == null) {
            return null;
        }

        Matcher tradeMatcher = TRADE_PATTERN.matcher(tradeLine);
        if (!tradeMatcher.find()) {
            return null;
        }

        String action = tradeMatcher.group(1) == null ? "BUY" : tradeMatcher.group(1).toUpperCase(Locale.ROOT);
        String symbol = tradeMatcher.group(2).trim().toUpperCase(Locale.ROOT);
        String strike = tradeMatcher.group(3);
        String optionType = tradeMatcher.group(4).toUpperCase(Locale.ROOT);
        Double entry = tryParseDouble(tradeMatcher.group(5));
        if (symbol.isBlank() || entry == null) {
            return null;
        }

        List<String> targets = lines.stream()
                .filter(this::isTargetLine)
                .findFirst()
                .map(this::extractNumbers)
                .orElseGet(List::of);

        Double stopLoss = lines.stream()
                .filter(this::isStopLossLine)
                .findFirst()
                .map(this::extractNumbers)
                .filter(values -> !values.isEmpty())
                .map(values -> tryParseDouble(values.get(0)))
                .orElse(null);
        if (stopLoss == null) {
            return null;
        }

        String expiry = lines.stream()
                .filter(this::isExpiryLine)
                .findFirst()
                .map(line -> parseExpiry(line, symbol))
                .orElse(null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "telegram");
        result.put("action", action);
        result.put("symbol", symbol);
        result.put("strike", strike);
        result.put("optionType", optionType);
        result.put("entry", entry);
        result.put("target1", targets.size() > 0 ? targets.get(0) : null);
        result.put("target2", targets.size() > 1 ? targets.get(1) : null);
        result.put("target3", targets.size() > 2 ? targets.get(2) : null);
        result.put("stopLoss", stopLoss);
        result.put("expiry", expiry);
        result.put("exchange", null);
        result.put("intraday", !text.toUpperCase(Locale.ROOT).contains("BTST"));
        result.put("useSpotPrice", true);
        result.put("useSpotForEntry", true);
        result.put("useSpotForSl", true);
        result.put("useSpotForTarget", true);
        return result;
    }

    private boolean containsSpot(String text) {
        return Pattern.compile("\\bSPOT\\b", Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    private boolean isTargetLine(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        return upper.startsWith("TARGET") || upper.startsWith("TGT");
    }

    private boolean isStopLossLine(String line) {
        return line.toUpperCase(Locale.ROOT).startsWith("SL");
    }

    private List<String> extractNumbers(String text) {
        List<String> values = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\d+(?:\\.\\d+)?").matcher(text);
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values;
    }

    private boolean isExpiryLine(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        return monthMap().keySet().stream().anyMatch(upper::contains);
    }

    private String parseExpiry(String rawExpiry, String symbol) {
        Integer month = null;
        String upper = rawExpiry.toUpperCase(Locale.ROOT);
        for (String token : upper.split("[^A-Z]+")) {
            Integer parsedMonth = monthMap().get(token);
            if (parsedMonth != null) {
                month = parsedMonth;
                break;
            }
        }
        if (month == null) {
            return null;
        }

        LocalDate today = LocalDate.now();
        int year = today.getYear();
        DayOfWeek expiryDay = "SENSEX".equalsIgnoreCase(symbol) ? DayOfWeek.THURSDAY : DayOfWeek.TUESDAY;
        LocalDate expiry = lastWeekdayOfMonth(year, month, expiryDay);
        if ((today.getMonthValue() == month && !expiry.isAfter(today)) || today.getMonthValue() > month) {
            expiry = lastWeekdayOfMonth(year + 1, month, expiryDay);
        }
        return expiry.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    private LocalDate lastWeekdayOfMonth(int year, int month, DayOfWeek dayOfWeek) {
        LocalDate date = YearMonth.of(year, month).atEndOfMonth();
        while (date.getDayOfWeek() != dayOfWeek) {
            date = date.minusDays(1);
        }
        return date;
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

    private Double tryParseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (Exception e) {
            return null;
        }
    }
}
