package org.com.sharekhan.parser;

import java.util.*;
import java.util.regex.*;
import java.util.Calendar;

public class WhatsappSignalParser implements TradingSignalParser {

    @Override
    public Map<String, Object> parse(String text) {
        if (text == null || text.isBlank()) return null;

        try {
            String normalizedText = text.trim().replaceFirst("^\\*+", "").replaceFirst("\u200E", "");
            if (!normalizedText.toLowerCase().startsWith("hi, your subscribed trading signal is ready:")) {
                return null;
            }

            // Extract instrument full line
            Pattern instrumentFullPattern = Pattern.compile("INSTRUMENT:\\s*(.+)", Pattern.CASE_INSENSITIVE);
            Matcher instrumentMatcher = instrumentFullPattern.matcher(text);
            String instrumentFull = instrumentMatcher.find() ? instrumentMatcher.group(1).trim() : null;

            // Extract Entry price
            Double entryPrice = extractEntryPrice(text);
            // Extract Stop Loss
            Double stopLoss = extractDoubleValue(text, "STOP LOSS");
            // Extract Target(s)
            String targetStr = extractStringValue(text, "TARGET");
            String[] targetParts = targetStr != null ? targetStr.split("/") : new String[0];
            Double target1 = targetParts.length > 0 ? tryParseDouble(targetParts[0]) : 0.0;
            Double target2 = targetParts.length > 1 ? tryParseDouble(targetParts[1]) : 0.0;
            Double target3 = targetParts.length > 2 ? tryParseDouble(targetParts[2]) : 0.0;

            if (instrumentFull == null || entryPrice == null || stopLoss == null || targetStr == null) {
                return null;
            }

            // Symbol extraction
            String instrument;
            boolean isEquity = instrumentFull.toUpperCase(Locale.ROOT).contains("-EQ");
            if (isEquity) {
                instrument = instrumentFull.substring(0, instrumentFull.toUpperCase(Locale.ROOT).indexOf("-EQ")).trim();
            } else {
                String[] parts = instrumentFull.split(" ");
                instrument = parts.length > 0 ? parts[0].trim() : "";
            }

            boolean isFuture = instrumentFull.toUpperCase().contains("FUT");
            String optionType = null;
            Double strikePrice = null;
            Object expiryFormatted = "";

            if (isFuture) {
                optionType = "FUT";

                // Extract expiry month for FUT
                Pattern expiryMonthPattern = Pattern.compile("\\b(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)\\b", Pattern.CASE_INSENSITIVE);
                Matcher monthMatcher = expiryMonthPattern.matcher(instrumentFull);
                if (monthMatcher.find()) {
                    String monthStr = monthMatcher.group(1).toUpperCase();
                    // Optionally, set expiry day to 25th of that month (or adjust as needed)
                    expiryFormatted = formatExpiryDate("25 " + monthStr);
                } else {
                    expiryFormatted = "";
                }
            } else {
                // Strike & OptionType extraction for options
                Pattern strikeOptionPattern = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(CALL|PUT|CE|PE)", Pattern.CASE_INSENSITIVE);
                Matcher strikeOptionMatcher = strikeOptionPattern.matcher(instrumentFull);
                if (strikeOptionMatcher.find()) {
                    strikePrice = Double.valueOf(strikeOptionMatcher.group(1));
                    String type = strikeOptionMatcher.group(2).toUpperCase();
                    optionType = type.equals("CALL") ? "CE" : type.equals("PUT") ? "PE" : type;
                } else {
                    // fallback: last number in instrument
                    Pattern numberPattern = Pattern.compile("\\b(\\d{2,6}(?:\\.\\d+)?)\\b");
                    Matcher numberMatcher = numberPattern.matcher(instrumentFull);
                    while (numberMatcher.find()) {
                        strikePrice = Double.valueOf(numberMatcher.group(1));
                    }
                }

                // Expiry extraction for options (ex: "25 NOV")
                Pattern expiryPattern = Pattern.compile("\\b(\\d{1,2} [A-Z]{3})\\b", Pattern.CASE_INSENSITIVE);
                Matcher expiryMatcher = expiryPattern.matcher(instrumentFull);
                if (expiryMatcher.find()) {
                    expiryFormatted = formatExpiryDate(expiryMatcher.group(1));
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("symbol", instrument);
            result.put("exchange", isEquity ? "NC" : null);
            result.put("entry", entryPrice);
            result.put("stopLoss", stopLoss);
            result.put("target1", target1);
            result.put("target2", target2);
            result.put("target3", target3);
            result.put("trailingSl", 0.0);
            result.put("quantity", null);
            result.put("strike", strikePrice);
            result.put("optionType", optionType);
            result.put("expiry", expiryFormatted);
            // The subscribed WhatsApp format identifies cash equities with
            // "-EQ". Treat StockBazaari equity calls exactly like the Telegram
            // form: delivery only, with TSL, and eligible for amount sizing.
            result.put("intraday", !isEquity);
            if (isEquity) {
                result.put("source", "StockBazaari");
                result.put("tslEnabled", true);
            }

            return result;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private Double extractDoubleValue(String text, String key) {
        Pattern pattern = Pattern.compile(key + ":\\s*([\\d.]+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? tryParseDouble(matcher.group(1)) : null;
    }

    /**
     * Signal providers may qualify an entry level as "ABOVE 150" or "BELOW 150".
     * The trade engine uses the numeric level as its trigger price, so retain that
     * level while accepting the optional qualifier.
     */
    private Double extractEntryPrice(String text) {
        Pattern pattern = Pattern.compile("ENTRY:\\s*(?:(?:ABOVE|BELOW)\\s+)?([\\d.]+)",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? tryParseDouble(matcher.group(1)) : null;
    }

    private String extractStringValue(String text, String key) {
        Pattern pattern = Pattern.compile(key + ":\\s*([^\\n]+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
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
