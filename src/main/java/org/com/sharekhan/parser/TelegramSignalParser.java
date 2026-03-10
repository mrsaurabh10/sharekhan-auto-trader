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

        // Remove HERO-ZERO keyword from text
        String cleanedText = text.replaceAll("(?i)HERO-ZERO", "").trim();

        String[] linesArr = cleanedText.split("\n");
        List<String> lines = Arrays.stream(linesArr)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        // Find the first line that matches the trade regex
        // Updated regex to handle optional BUY/SELL prefix, capture symbol correctly, and handle decimal strike
        Pattern tradePattern = Pattern.compile("^(?:BUY|SELL)?\\s*([A-Z0-9\\s]+?)\\s+([\\d\\.]+)\\s+(CE|PE)\\s+above\\s+([\\d\\.]+)", Pattern.CASE_INSENSITIVE);
        String tradeLine = lines.stream()
                .filter(line -> tradePattern.matcher(line).find())
                .findFirst()
                .orElse(null);

        if (tradeLine == null) {
            return null; // no valid trade info found
        }

        Matcher matcher = tradePattern.matcher(tradeLine);
        if (!matcher.find()) {
            return null;
        }

        String action = "BUY";
        // Group 1 is the symbol
        String symbol = matcher.group(1).toUpperCase().trim();
        // Group 2 is the strike
        String strike = matcher.group(2);
        // Group 3 is Option Type
        String optionType = matcher.group(3).toUpperCase();
        // Group 4 is Entry Price
        Double entry = tryParseDouble(matcher.group(4));

        // Updated filter to include lines starting with "TARGET" as well as "TGT"
        Optional<String> targetLine = lines.stream()
                .filter(l -> {
                    String upper = l.toUpperCase();
                    return upper.startsWith("TGT") || upper.startsWith("TARGET");
                })
                .findFirst();

        String target1 = null;
        String target2 = null;

        if (targetLine.isPresent()) {
            String targetLineVal = targetLine.get();
            // Replace TGT/TARGET prefix and separators like - or : with space
            // Updated to handle combinations like "TARGET :-"
            String targetsStr = targetLineVal.replaceAll("(?i)^TGT[\\s:\\-]*", "")
                                             .replaceAll("(?i)^TARGET[\\s:\\-]*", "").trim();
            
            String[] targets = targetsStr.split("[/\\s]+"); // Split by slash or space
            target1 = targets.length > 0 ? targets[0].trim() : null;
            target2 = targets.length > 1 ? targets[1].trim() : null;
        }

        Optional<String> slLine = lines.stream()
                .filter(l -> l.toUpperCase().startsWith("SL"))
                .findFirst();

        String stopLossText = null;

        if (slLine.isPresent()) {
            String slVal = slLine.get();
            // Replace SL prefix and separators
            // Updated to handle combinations like "SL :-"
            stopLossText = slVal.replaceAll("(?i)^SL[\\s:\\-]*", "").trim();
        }

        String expiryRaw = lines.stream()
                .filter(this::isExpiryLine)
                .findFirst()
                .orElse(null);

        String expiryFormatted = parseExpiry(expiryRaw, symbol);

        Double stopLoss = stopLossText != null ?
                Optional.ofNullable(tryParseDouble(stopLossText)).orElse(entry != null ? entry * 0.90 : 0.0) :
                (entry != null ? entry * 0.10 : 0.0);

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

        // Set intraday false if "BTST" present anywhere in original message
        boolean intraday = !text.toUpperCase().contains("BTST");
        result.put("intraday", intraday);

        return result;
    }

    private boolean isExpiryLine(String line) {
        if (line == null) return false;
        String upper = line.toUpperCase();
        List<String> months = Arrays.asList("JAN", "FEB", "MAR", "APR", "MAY", "JUN",
                "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");
        return months.stream().anyMatch(upper::contains);
    }

    private String parseExpiry(String rawExpiry, String symbolUpper) {
        if (rawExpiry == null || rawExpiry.isBlank()) {
            return null;
        }

        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate expiry = null;

        Integer dayFromText = null;
        Integer monthFromText = null;
        Integer yearFromText = null;

        String upper = rawExpiry.trim().toUpperCase(Locale.ROOT);

        // Try to parse day from strings like "30 MARCH"
        Pattern dayPattern = Pattern.compile("(\\d{1,2})");
        Matcher dayMatcher = dayPattern.matcher(upper);
        if (dayMatcher.find()) {
            try {
                dayFromText = Integer.parseInt(dayMatcher.group(1));
            } catch (NumberFormatException e) {
                // Ignore if not a valid number
            }
        }

        // Try to parse year from strings like "MARCH 2026"
        Pattern yearPattern = Pattern.compile("(20\\d{2})");
        Matcher yearMatcher = yearPattern.matcher(upper);
        if (yearMatcher.find()) {
            try {
                yearFromText = Integer.parseInt(yearMatcher.group(1));
            } catch (NumberFormatException e) {
                // Ignore
            }
        }

        // Month token parsing (JAN/JANUARY etc.)
        Map<String, Integer> monthMap = new HashMap<>();
        monthMap.put("JANUARY", 1); monthMap.put("JAN", 1);
        monthMap.put("FEBRUARY", 2); monthMap.put("FEB", 2);
        monthMap.put("MARCH", 3); monthMap.put("MAR", 3);
        monthMap.put("APRIL", 4); monthMap.put("APR", 4);
        monthMap.put("MAY", 5);
        monthMap.put("JUNE", 6); monthMap.put("JUN", 6);
        monthMap.put("JULY", 7); monthMap.put("JUL", 7);
        monthMap.put("AUGUST", 8); monthMap.put("AUG", 8);
        monthMap.put("SEPTEMBER", 9); monthMap.put("SEP", 9);
        monthMap.put("OCTOBER", 10); monthMap.put("OCT", 10);
        monthMap.put("NOVEMBER", 11); monthMap.put("NOV", 11);
        monthMap.put("DECEMBER", 12); monthMap.put("DEC", 12);
        for (String token : upper.split("[^A-Z]+")) {
            if (token == null || token.isEmpty()) continue;
            Integer m = monthMap.get(token);
            if (m != null) { monthFromText = m; break; }
        }

        if (dayFromText != null && monthFromText != null) {
            int year;
            if (yearFromText != null) {
                year = yearFromText;
            } else {
                year = today.getYear();
            }

            try {
                java.time.LocalDate candidateExpiry = java.time.LocalDate.of(year, monthFromText, dayFromText);
                if (yearFromText == null && candidateExpiry.isBefore(today)) {
                    candidateExpiry = candidateExpiry.withYear(year + 1);
                }
                expiry = candidateExpiry;
            } catch (java.time.DateTimeException e) {
                // Invalid date (e.g., 31 Feb), fall back to default logic
                expiry = null;
            }
        } else if (monthFromText != null) {
            // Only month provided, try to calculate expiry based on instrument
            String sym = (symbolUpper == null ? "" : symbolUpper.toUpperCase(Locale.ROOT));
            boolean isSensex = "SENSEX".equals(sym);
            
            int year = today.getYear();
            if (isSensex) {
                java.time.LocalDate cand = lastWeekdayOfMonth(year, monthFromText, java.time.DayOfWeek.THURSDAY);
                if (!cand.isAfter(today) && (today.getMonthValue() == monthFromText)) {
                    cand = lastWeekdayOfMonth(year + 1, monthFromText, java.time.DayOfWeek.THURSDAY);
                } else if (today.getMonthValue() > monthFromText) {
                    cand = lastWeekdayOfMonth(year + 1, monthFromText, java.time.DayOfWeek.THURSDAY);
                }
                expiry = cand;
            } else {
                // STOCK, NIFTY (monthly interpretation), BANKNIFTY → last Tuesday
                java.time.LocalDate cand = lastWeekdayOfMonth(year, monthFromText, java.time.DayOfWeek.TUESDAY);
                if (!cand.isAfter(today) && (today.getMonthValue() == monthFromText)) {
                    cand = lastWeekdayOfMonth(year + 1, monthFromText, java.time.DayOfWeek.TUESDAY);
                } else if (today.getMonthValue() > monthFromText) {
                    cand = lastWeekdayOfMonth(year + 1, monthFromText, java.time.DayOfWeek.TUESDAY);
                }
                expiry = cand;
            }
        }

        return expiry != null ? expiry.format(fmt) : null;
    }

    private static java.time.LocalDate lastWeekdayOfMonth(int year, int month, java.time.DayOfWeek dow) {
        java.time.YearMonth ym = java.time.YearMonth.of(year, month);
        java.time.LocalDate d = ym.atEndOfMonth();
        while (d.getDayOfWeek() != dow) {
            d = d.minusDays(1);
        }
        return d;
    }

    private Double tryParseDouble(String val) {
        try {
            return Double.parseDouble(val);
        } catch (Exception e) {
            return null;
        }
    }
}
