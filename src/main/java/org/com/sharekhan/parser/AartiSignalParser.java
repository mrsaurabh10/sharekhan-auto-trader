package org.com.sharekhan.parser;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.*;

public class AartiSignalParser implements TradingSignalParser {

    @Override
    public Map<String, Object> parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        try {
            Pattern pattern = Pattern.compile(
                "(BUY|SELL)\\s+([A-Z]+)\\s+(\\d+)\\s+(CE|PE)\\s+(?:BUY|SELL)?\\s*ABOVE\\s+(\\d+)\\s+TGT\\s+(\\d+)\\s*-\\s*(\\d+)\\s+SL\\s+(\\d+)",
                Pattern.CASE_INSENSITIVE
            );

            Matcher matcher = pattern.matcher(text);
            if (!matcher.find()) {
                return null;
            }

            String action = matcher.group(1).toUpperCase();
            String symbol = matcher.group(2).toUpperCase();
            String strike = matcher.group(3);
            String optionType = matcher.group(4).toUpperCase();
            Double entryPrice = Double.parseDouble(matcher.group(5));
            Double target1 = Double.parseDouble(matcher.group(6));
            Double target2 = Double.parseDouble(matcher.group(7));
            Double stopLoss = Double.parseDouble(matcher.group(8));

            // Determine expiry
            String expiry = calculateNearestExpiry(symbol);

            Map<String, Object> result = new HashMap<>();
            result.put("source", "nifty-signal");
            result.put("action", action);
            result.put("symbol", symbol);
            result.put("strike", Double.parseDouble(strike));
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
        int daysUntil = (targetDay.getValue() - today.getValue() + 7) % 7;
        if (daysUntil == 0 && LocalTime.now(ZoneId.of("Asia/Kolkata")).isAfter(LocalTime.of(15, 30))) {
            daysUntil = 7;
        }
        if (daysUntil == 0) daysUntil = 7; // ensure future date
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
