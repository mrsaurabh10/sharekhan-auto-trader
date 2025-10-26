package org.com.sharekhan.parser;

import java.util.*;
import java.util.regex.*;
import java.util.Calendar;

public class WhatsappSignalParser implements TradingSignalParser {

    @Override
    public Map<String, Object> parse(String text) {
        // Only parse if message starts with specific prefix
        String normalizedText = text.trim().replaceFirst("^\\*+", "").replaceFirst("\u200E", "");
        if (!normalizedText.toLowerCase().startsWith("hi, your subscribed trading signal is ready:")) {
            return null;
        }

        try {
            Pattern instrumentFullPattern = Pattern.compile("INSTRUMENT: ([^\\n]+)", Pattern.CASE_INSENSITIVE);
            Pattern entryPattern = Pattern.compile("ENTRY: ([\\d.]+)", Pattern.CASE_INSENSITIVE);
            Pattern stopLossPattern = Pattern.compile("STOP LOSS: ([\\d.]+)", Pattern.CASE_INSENSITIVE);
            Pattern targetPattern = Pattern.compile("TARGET: ([\\d/.]+)", Pattern.CASE_INSENSITIVE);

            Matcher instrumentFullMatcher = instrumentFullPattern.matcher(text);
            Matcher entryMatcher = entryPattern.matcher(text);
            Matcher stopLossMatcher = stopLossPattern.matcher(text);
            Matcher targetMatcher = targetPattern.matcher(text);

            String instrumentFull = instrumentFullMatcher.find() ? instrumentFullMatcher.group(1) : null;
            String entryStr = entryMatcher.find() ? entryMatcher.group(1) : null;
            String stopLossStr = stopLossMatcher.find() ? stopLossMatcher.group(1) : null;
            String targetStr = targetMatcher.find() ? targetMatcher.group(1) : null;

            if (instrumentFull == null || entryStr == null || stopLossStr == null || targetStr == null)
                return null;

            String instrument;
            if (instrumentFull.contains("-EQ")) {
                instrument = instrumentFull.substring(0, instrumentFull.indexOf("-EQ"));
            } else {
                String[] parts = instrumentFull.split(" ");
                instrument = parts.length > 0 ? parts[0] : "";
            }

            boolean isFuture = instrumentFull.toUpperCase().contains("FUT");

            Object expiryFormatted;
            if (isFuture) {
                expiryFormatted = null;
            } else {
                Pattern expiryPattern = Pattern.compile("\\d{1,2} [A-Z]{3}", Pattern.CASE_INSENSITIVE);
                Matcher expiryMatcher = expiryPattern.matcher(instrumentFull);
                if (expiryMatcher.find()) {
                    expiryFormatted = formatExpiryDate(expiryMatcher.group(0));
                } else {
                    expiryFormatted = "";
                }
            }

            Object optionType;
            if (isFuture) {
                optionType = "FUT";
            } else {
                Pattern optionTypePattern = Pattern.compile("\\b(CALL|PUT)\\b", Pattern.CASE_INSENSITIVE);
                Matcher optionMatcher = optionTypePattern.matcher(instrumentFull);
                if (optionMatcher.find()) {
                    String option = optionMatcher.group(0).toUpperCase();
                    optionType = option.equals("CALL") ? "CE" : option.equals("PUT") ? "PE" : null;
                } else {
                    optionType = null;
                }
            }

            Pattern strikePricePattern = Pattern.compile("\\b(\\d{4,5})\\b", Pattern.CASE_INSENSITIVE);
            Matcher strikePriceMatcher = strikePricePattern.matcher(instrumentFull);
            Double strikePrice = strikePriceMatcher.find() && !isFuture ? Double.valueOf(strikePriceMatcher.group(1)) : null;

            Double entryPrice = Double.parseDouble(entryStr);
            Double stopLoss = Double.parseDouble(stopLossStr);

            String[] targetParts = targetStr.split("/");
            Double target1 = targetParts.length > 0 ? tryParseDouble(targetParts[0]) : 0.0;
            Double target2 = targetParts.length > 1 ? tryParseDouble(targetParts[1]) : 0.0;
            Double target3 = targetParts.length > 2 ? tryParseDouble(targetParts[2]) : 0.0;

            Map<String, Object> result = new HashMap<>();
            result.put("symbol", instrument);
            result.put("exchange", null);
            result.put("entry", entryPrice != null ? entryPrice : 0.0);
            result.put("stopLoss", stopLoss != null ? stopLoss : 0.0);
            result.put("target1", target1 != null ? target1 : 0.0);
            result.put("target2", target2 != null ? target2 : 0.0);
            result.put("target3", target3 != null ? target3 : 0.0);
            result.put("trailingSl", 0.0);
            result.put("quantity", null);
            result.put("strike", strikePrice);
            result.put("optionType", optionType);
            result.put("expiry", expiryFormatted);
            result.put("intraday", true);

            return result;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private Double tryParseDouble(String val) {
        try {
            return Double.parseDouble(val);
        } catch (Exception e) {
            return null;
        }
    }

    private String formatExpiryDate(String expiryStr) {
        String[] parts = expiryStr.split(" ");
        if (parts.length != 2) return "";

        Integer day = tryParseInt(parts[0]);
        if (day == null) return "";

        String monthStr = parts[1].toUpperCase();
        Map<String, Integer> monthMap = Map.ofEntries(
                Map.entry("JAN", 1),
                Map.entry("FEB", 2),
                Map.entry("MAR", 3),
                Map.entry("APR", 4),
                Map.entry("MAY", 5),
                Map.entry("JUN", 6),
                Map.entry("JUL", 7),
                Map.entry("AUG", 8),
                Map.entry("SEP", 9),
                Map.entry("OCT", 10),
                Map.entry("NOV", 11),
                Map.entry("DEC", 12)
        );

        Integer expiryMonth = monthMap.get(monthStr);
        if (expiryMonth == null) return "";

        Calendar cal = Calendar.getInstance();
        int currentYear = cal.get(Calendar.YEAR);
        int currentMonth = cal.get(Calendar.MONTH) + 1;

        int expiryYear = expiryMonth < currentMonth ? currentYear + 1 : currentYear;

        return String.format("%02d/%02d/%d", day, expiryMonth, expiryYear);
    }

    private Integer tryParseInt(String val) {
        try {
            return Integer.parseInt(val);
        } catch (Exception e) {
            return null;
        }
    }
}
