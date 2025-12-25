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
        Pattern tradePattern = Pattern.compile("(BUY|SELL)\\s+([A-Z]+)\\s+(\\d+)\\s+(CE|PE)\\s+ABOVE\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
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

        String action = matcher.group(1).toUpperCase();
        String symbol = matcher.group(2).toUpperCase();
        String strike = matcher.group(3);
        String optionType = matcher.group(4).toUpperCase();
        Double entry = tryParseDouble(matcher.group(5));

        Optional<String> targetLine = lines.stream()
                .filter(l -> l.toUpperCase().startsWith("TARGET"))
                .findFirst();

        String target1 = null;
        String target2 = null;

        if (targetLine.isPresent()) {
            String targetLineVal = targetLine.get();
            String afterColon = targetLineVal.substring(targetLineVal.indexOf(":") + 1).replace("-", "").trim();
            String[] targets = afterColon.split("/");
            target1 = targets.length > 0 ? targets[0].trim() : null;
            target2 = targets.length > 1 ? targets[1].trim() : null;
        }

        Optional<String> slLine = lines.stream()
                .filter(l -> l.toUpperCase().startsWith("SL"))
                .findFirst();

        String stopLossText = null;

        if (slLine.isPresent()) {
            String slVal = slLine.get();
            stopLossText = slVal.substring(slVal.indexOf(":") + 1).replace("-", "").trim();
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
        // Rules per requirement:
        // - Output format: dd/MM/yyyy
        // - STOCK (default): monthly last Tuesday
        // - NIFTY: weekly Tuesday (next Tuesday when month not provided); if month provided → last Tuesday of that month
        // - SENSEX: weekly Thursday (next Thursday when month not provided); if month provided → last Thursday of that month
        // - BANKNIFTY: monthly last Tuesday (both default and when month provided)

        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        String sym = (symbolUpper == null ? "" : symbolUpper.toUpperCase(Locale.ROOT));
        boolean isNifty = "NIFTY".equals(sym);
        boolean isSensex = "SENSEX".equals(sym);
        boolean isBankNifty = "BANKNIFTY".equals(sym);

        // Month token parsing (JAN/JANUARY etc.)
        Integer monthFromText = null;
        if (rawExpiry != null && !rawExpiry.isBlank()) {
            String upper = rawExpiry.trim().toUpperCase(Locale.ROOT);
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
        }

        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate expiry;

        if (monthFromText != null) {
            // When month specified, pick that month's last Tue/Thu as per instrument
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
        } else {
            // No month specified: use default cycles per instrument
            if (isSensex) {
                expiry = nextOnOrAfter(today.plusDays(1), java.time.DayOfWeek.THURSDAY); // next Thursday (weekly)
            } else if (isNifty) {
                expiry = nextOnOrAfter(today.plusDays(1), java.time.DayOfWeek.TUESDAY); // next Tuesday (weekly)
            } else if (isBankNifty) {
                // monthly last Tuesday for current month if not passed, else next month's last Tuesday
                java.time.LocalDate cand = lastWeekdayOfMonth(today.getYear(), today.getMonthValue(), java.time.DayOfWeek.TUESDAY);
                if (!cand.isAfter(today)) {
                    java.time.LocalDate nextMonth = today.plusMonths(1);
                    cand = lastWeekdayOfMonth(nextMonth.getYear(), nextMonth.getMonthValue(), java.time.DayOfWeek.TUESDAY);
                }
                expiry = cand;
            } else {
                // STOCK default monthly last Tuesday
                java.time.LocalDate cand = lastWeekdayOfMonth(today.getYear(), today.getMonthValue(), java.time.DayOfWeek.TUESDAY);
                if (!cand.isAfter(today)) {
                    java.time.LocalDate nextMonth = today.plusMonths(1);
                    cand = lastWeekdayOfMonth(nextMonth.getYear(), nextMonth.getMonthValue(), java.time.DayOfWeek.TUESDAY);
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

    private static java.time.LocalDate nextOnOrAfter(java.time.LocalDate startExclusive, java.time.DayOfWeek dow) {
        java.time.LocalDate d = startExclusive;
        while (d.getDayOfWeek() != dow) {
            d = d.plusDays(1);
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


