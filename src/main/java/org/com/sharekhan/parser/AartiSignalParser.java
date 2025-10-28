package org.com.sharekhan.parser;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.*;

public class AartiSignalParser implements TradingSignalParser {

    @Override
    public Map<String, Object> parse(String text) {
        if (text == null || text.isBlank()) return null;

        try {
            Pattern mainPattern = Pattern.compile(
                    "(BUY|SELL)\\s+([A-Z]+)\\s+(\\d+)\\s+(CE|PE)",
                    Pattern.CASE_INSENSITIVE
            );
            Matcher m = mainPattern.matcher(text);
            if (!m.find()) return null;

            String action = m.group(1).toUpperCase();
            String symbol = m.group(2).toUpperCase();
            String strike = m.group(3);
            String optionType = m.group(4).toUpperCase();

            String entryStr = null, target1Str = null, target2Str = null, slStr = null;
            Matcher entryM = Pattern.compile("(?:BUY|SELL)?\\s*ABOVE\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(text);
            if (entryM.find()) entryStr = entryM.group(1);

            Matcher tgtM = Pattern.compile("(?:TGT|TARGET)[\\s:]+(\\d+)[\\s/-]+(\\d+)", Pattern.CASE_INSENSITIVE).matcher(text);
            if (tgtM.find()) {
                target1Str = tgtM.group(1);
                target2Str = tgtM.group(2);
            }

            Matcher slM = Pattern.compile("(?:SL|STOP ?LOSS)\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(text);
            if (slM.find()) slStr = slM.group(1);

            Double entryPrice = tryParseDouble(entryStr);
            Double target1 = tryParseDouble(target1Str);
            Double target2 = tryParseDouble(target2Str);
            Double stopLoss = tryParseDouble(slStr);

            // Expiry
            String expiry = calculateNearestExpiry(symbol);

            Map<String, Object> result = new HashMap<>();
            result.put("source", "nifty-signal");
            result.put("action", action);
            result.put("symbol", symbol);
            result.put("strike", tryParseDouble(strike));
            result.put("optionType", optionType);
            result.put("entry", entryPrice);
            result.put("target1", target1);
            result.put("target2", target2);
            result.put("target3", null);
            result.put("stopLoss", stopLoss);
            result.put("trailingSl", 0.0);
            result.put("quantity", null);
            result.put("expiry", expiry);
            result.put("exchange", null);
            result.put("intraday", true);

            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private Double tryParseDouble(String val) {
        if (val == null || val.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(val.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Determine expiry:
     * - NIFTY → next Tuesday
     * - BANKNIFTY / FINNIFTY / MIDCPNIFTY → last Tuesday of the month
     * - SENSEX → next Friday (weekly)
     * - All other stocks → last Thursday of the month
     */
    private String calculateNearestExpiry(String symbol) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate expiryDate;

        if ("NIFTY".equalsIgnoreCase(symbol)) {
            expiryDate = getNextWeekday(today, DayOfWeek.TUESDAY);
        } 
        else if ("BANKNIFTY".equalsIgnoreCase(symbol)
                || "FINNIFTY".equalsIgnoreCase(symbol)
                || "MIDCPNIFTY".equalsIgnoreCase(symbol)) {
            expiryDate = getLastWeekdayOfMonth(today, DayOfWeek.TUESDAY);
        }
        else if ("SENSEX".equalsIgnoreCase(symbol)) {
            expiryDate = getNextWeekday(today, DayOfWeek.FRIDAY);
        }
        else {
            expiryDate = getLastWeekdayOfMonth(today, DayOfWeek.THURSDAY); // stock options
        }

        return String.format("%02d/%02d/%d",
                expiryDate.getDayOfMonth(),
                expiryDate.getMonthValue(),
                expiryDate.getYear());
    }

    // Finds next specific weekday (for weekly contracts)
    private LocalDate getNextWeekday(LocalDate date, DayOfWeek targetDay) {
        DayOfWeek today = date.getDayOfWeek();
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (today == targetDay && now.isBefore(LocalTime.of(15, 30))) {
            return date;
        }
        int daysUntil = (targetDay.getValue() - today.getValue() + 7) % 7;
        if (daysUntil == 0) daysUntil = 7;
        return date.plusDays(daysUntil);
    }

    // Finds last specific weekday of the current/next month
    private LocalDate getLastWeekdayOfMonth(LocalDate date, DayOfWeek targetDay) {
        LocalDate lastDay = date.with(TemporalAdjusters.lastDayOfMonth());
        LocalDate lastTargetDay = lastDay.with(TemporalAdjusters.previousOrSame(targetDay));

        if (lastTargetDay.isBefore(date)) {
            LocalDate nextMonth = date.plusMonths(1);
            lastDay = nextMonth.with(TemporalAdjusters.lastDayOfMonth());
            lastTargetDay = lastDay.with(TemporalAdjusters.previousOrSame(targetDay));
        }
        return lastTargetDay;
    }
}
