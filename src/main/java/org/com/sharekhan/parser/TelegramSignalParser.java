package org.com.sharekhan.parser;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
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

    private String parseExpiry(String rawExpiry, String symbol) {
        if (rawExpiry == null) return null;

        String upper = rawExpiry.trim().toUpperCase();
        // Extract month token
        Integer month = detectMonth(upper);
        if (month == null) return null;

        LocalDate now = LocalDate.now();

        // Decide expiry weekday rules based on symbol when month is provided
        DayOfWeek dow;
        String sym = symbol == null ? "" : symbol.toUpperCase();
        if (sym.contains("SENSEX")) {
            dow = DayOfWeek.THURSDAY; // monthly last Thursday for SENSEX when month specified
        } else {
            // Default and BANKNIFTY/NIFTY/STOCKS when month specified → last Tuesday
            dow = DayOfWeek.TUESDAY;
        }

        // Determine year (current or next) such that the last <dow> of that month is not in the past
        int year = now.getYear();
        LocalDate candidateThisYear = lastWeekdayOfMonth(year, month, dow);
        if (now.isAfter(candidateThisYear)) {
            year = year + 1;
        }
        LocalDate expiryDate = lastWeekdayOfMonth(year, month, dow);

        return String.format("%02d/%02d/%d", expiryDate.getDayOfMonth(), month, year);
    }

    private Integer detectMonth(String textUpper) {
        if (textUpper == null) return null;
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

        for (Map.Entry<String, Integer> e : monthMap.entrySet()) {
            if (textUpper.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    private LocalDate lastWeekdayOfMonth(int year, int month, DayOfWeek dow) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate date = ym.atEndOfMonth();
        while (date.getDayOfWeek() != dow) {
            date = date.minusDays(1);
        }
        return date;
    }

    private Double tryParseDouble(String val) {
        try {
            return Double.parseDouble(val);
        } catch (Exception e) {
            return null;
        }
    }
}


