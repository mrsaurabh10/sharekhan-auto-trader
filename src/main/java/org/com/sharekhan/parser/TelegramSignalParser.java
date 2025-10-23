package org.com.sharekhan.parser;

import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

public class TelegramSignalParser implements TradingSignalParser {

    @Override
    public Map<String, Object> parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String[] linesArr = text.split("\n");
        List<String> lines = Arrays.stream(linesArr)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        String action = null;
        String symbol = null;
        String strike = null;
        String optionType = null;
        Double entry = null;
        String target1 = null;
        String target2 = null;
        String stopLossText = null;
        String expiryRaw = null;

        // Parse first line like "BUY BANKNIFTY 57500 PE ABOVE 560"
        if (!lines.isEmpty()) {
            String firstLine = lines.get(0);
            Pattern regex = Pattern.compile("(BUY|SELL)\\s+([A-Z]+)\\s+(\\d+)\\s+(CE|PE)\\s+ABOVE\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = regex.matcher(firstLine);
            if (matcher.find()) {
                action = matcher.group(1).toUpperCase();
                symbol = matcher.group(2).toUpperCase();
                strike = matcher.group(3);
                optionType = matcher.group(4).toUpperCase();
                entry = tryParseDouble(matcher.group(5));
            }
        }

        // Parse line starting with TARGET
        Optional<String> targetLine = lines.stream()
                .filter(l -> l.toUpperCase().startsWith("TARGET"))
                .findFirst();

        if (targetLine.isPresent()) {
            String targetLineVal = targetLine.get();
            String afterColon = targetLineVal.substring(targetLineVal.indexOf(":") + 1).replace("-", "").trim();
            String[] targets = afterColon.split("/");
            target1 = targets.length > 0 ? targets[0].trim() : null;
            target2 = targets.length > 1 ? targets[1].trim() : null;
        }

        // Parse line starting with SL
        Optional<String> slLine = lines.stream()
                .filter(l -> l.toUpperCase().startsWith("SL"))
                .findFirst();

        if (slLine.isPresent()) {
            String slVal = slLine.get();
            stopLossText = slVal.substring(slVal.indexOf(":") + 1).replace("-", "").trim();
        }

        // Parse expiry line (contains month names)
        expiryRaw = lines.stream()
                .filter(this::isExpiryLine)
                .findFirst()
                .orElse(null);

        String expiryFormatted = parseExpiry(expiryRaw);

        Double stopLoss = stopLossText != null ?
                Optional.ofNullable(tryParseDouble(stopLossText)).orElse(entry != null ? entry * 0.90 : 0.0) :
                (entry != null ? entry * 0.10 : 0.0);

        // Mandatory fields check
        if (action == null || symbol == null || strike == null || optionType == null || entry == null) {
            return null;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "telegram");
        result.put("action", action);
        result.put("symbol", symbol);
        result.put("strike", strike);
        result.put("optionType", optionType);
        result.put("entry", entry);
        result.put("target1", target1);
        result.put("target2", target2);
        result.put("stopLoss", stopLoss);
        result.put("expiry", expiryFormatted);
        result.put("exchange", null);

        return result;
    }

    private boolean isExpiryLine(String line) {
        if (line == null) return false;
        String upper = line.toUpperCase();
        List<String> months = Arrays.asList("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");
        return months.stream().anyMatch(upper::contains);
    }

    private String parseExpiry(String rawExpiry) {
        if (rawExpiry == null) return null;

        String trimmed = rawExpiry.trim().toUpperCase();
        Map<String, String> months = Map.ofEntries(
                Map.entry("JANUARY", "01"), Map.entry("JAN", "01"),
                Map.entry("FEBRUARY", "02"), Map.entry("FEB", "02"),
                Map.entry("MARCH", "03"), Map.entry("MAR", "03"),
                Map.entry("APRIL", "04"), Map.entry("APR", "04"),
                Map.entry("MAY", "05"),
                Map.entry("JUNE", "06"), Map.entry("JUN", "06"),
                Map.entry("JULY", "07"), Map.entry("JUL", "07"),
                Map.entry("AUGUST", "08"), Map.entry("AUG", "08"),
                Map.entry("SEPTEMBER", "09"), Map.entry("SEP", "09"),
                Map.entry("OCTOBER", "10"), Map.entry("OCT", "10"),
                Map.entry("NOVEMBER", "11"), Map.entry("NOV", "11"),
                Map.entry("DECEMBER", "12"), Map.entry("DEC", "12")
        );

        String[] parts = trimmed.split(" ");

        if (parts.length == 2) {
            String day = parts[0].length() == 1 ? "0" + parts[0] : parts[0];
            String month = months.getOrDefault(parts[1], "10"); // default October
            return day + "/" + month + "/2025";  // hard coded 2025 as in Kotlin code
        } else if (parts.length == 1) {
            String month = months.getOrDefault(parts[0], "10");
            return month + "/2025";
        }

        return null;
    }

    private Double tryParseDouble(String val) {
        try {
            return Double.parseDouble(val);
        } catch (Exception e) {
            return null;
        }
    }
}

