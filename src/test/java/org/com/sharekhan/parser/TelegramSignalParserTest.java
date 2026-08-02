package org.com.sharekhan.parser;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.*;

class TelegramSignalParserTest {

    private final TelegramSignalParser parser = new TelegramSignalParser();

    @Test
    void parsesRegularSignalWithHyphenInSymbol() {
        String text = """
                BUY BAJAJ-AUTO 10100 CE ABOVE 120
                TARGET :- 130 / 140
                SL :- 110
                MAY EXPIRY
                """;

        Map<String, Object> result = parser.parse(text);

        assertNotNull(result);
        assertEquals("BAJAJ-AUTO", result.get("symbol"));
        assertEquals("10100", result.get("strike"));
        assertEquals("CE", result.get("optionType"));
        assertEquals(120.0, (Double) result.get("entry"), 0.01);
        assertEquals("130", result.get("target1"));
        assertEquals("140", result.get("target2"));
        assertNull(result.get("target3"));
        assertEquals(110.0, (Double) result.get("stopLoss"), 0.01);
    }

    @Test
    void parsesBreakoutSignalEvenWhenAboveHasTypoAndReadsThreeTargets() {
        String text = """
                Mazdock 2400 pe abive 88
                Tgt - 95/100/105
                Sl - 75
                """;

        Map<String, Object> result = parser.parse(text);

        assertNotNull(result);
        assertEquals("MAZDOCK", result.get("symbol"));
        assertEquals("2400", result.get("strike"));
        assertEquals("PE", result.get("optionType"));
        assertEquals(88.0, (Double) result.get("entry"), 0.01);
        assertEquals("95", result.get("target1"));
        assertEquals("100", result.get("target2"));
        assertEquals("105", result.get("target3"));
        assertEquals(79.2, (Double) result.get("stopLoss"), 0.01);
        assertNotEquals(true, result.get("quickTrade"));
    }

    @Test
    void replacesCeStopLossAboveTargetWithTenPercentOptionStop() {
        Map<String, Object> result = parser.parse("""
                BUY EICHERMOT 7400 CE ABOVE 175
                TARGET :- 190 / 205
                SL :- 1440
                """);

        assertNotNull(result);
        assertEquals(157.5, (Double) result.get("stopLoss"), 0.01);
    }

    @Test
    void replacesPeStopLossBelowTargetWithTenPercentOptionStop() {
        Map<String, Object> result = parser.parse("""
                BUY EICHERMOT 7400 PE ABOVE 175
                TARGET :- 160 / 145
                SL :- 144
                """);

        assertNotNull(result);
        assertEquals(157.5, (Double) result.get("stopLoss"), 0.01);
    }

    @Test
    void retainsValidDirectionalStopLoss() {
        Map<String, Object> result = parser.parse("""
                BUY EICHERMOT 7400 CE ABOVE 175
                TARGET :- 190 / 205
                SL :- 160
                """);

        assertNotNull(result);
        assertEquals(160.0, (Double) result.get("stopLoss"), 0.01);
    }

    @Test
    void parsesQuickSignalWithHyphenInSymbol() {
        Map<String, Object> result = parser.parse("BAJAJ-AUTO 10100 CE Lots 1");

        assertNotNull(result);
        assertEquals("BAJAJ-AUTO", result.get("symbol"));
        assertEquals("10100", result.get("strike"));
        assertEquals("CE", result.get("optionType"));
        assertEquals(true, result.get("quickTrade"));
        assertEquals(1, result.get("quantity"));
    }

    @Test
    void parsesStockBazaariSignalAndKeepsTheContractMonth() {
        Map<String, Object> result = parser.parse("""
                New Trade Opportunity – Delivered as part of your subscription plan

                📈 Trade: LODHA AUG 1160 CE
                📍 Trigger Price: BUY ABOVE 38.3
                🎯 Target: 40.5/43/46
                🛑 SL: 32
                """);

        assertNotNull(result);
        assertEquals("StockBazaari", result.get("source"));
        assertEquals("LODHA", result.get("symbol"));
        assertEquals("1160", result.get("strike"));
        assertEquals("CE", result.get("optionType"));
        assertEquals(38.3, (Double) result.get("entry"), 0.01);
        assertEquals("40.5", result.get("target1"));
        assertEquals("43", result.get("target2"));
        assertEquals("46", result.get("target3"));
        assertEquals(32.0, (Double) result.get("stopLoss"), 0.01);
        assertEquals(lastTuesdayOfAugust().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")), result.get("expiry"));
    }

    @Test
    void parsesStockBazaariEquityAsDeliveryTradeWithTsl() {
        Map<String, Object> result = parser.parse("""
                New Trade Opportunity
                Trade: RELIANCE
                Trigger Price: BUY ABOVE 1450.50
                Target: 1475/1500/1530
                SL: 1425
                """);

        assertNotNull(result);
        assertEquals("StockBazaari", result.get("source"));
        assertEquals("RELIANCE", result.get("symbol"));
        assertEquals("NC", result.get("exchange"));
        assertNull(result.get("strike"));
        assertNull(result.get("optionType"));
        assertEquals(1450.50, (Double) result.get("entry"), 0.01);
        assertEquals(false, result.get("intraday"));
        assertEquals(true, result.get("tslEnabled"));
    }

    private LocalDate lastTuesdayOfAugust() {
        LocalDate today = LocalDate.now();
        int year = today.getYear();
        LocalDate expiry = lastWeekdayOfMonth(year, 8, DayOfWeek.TUESDAY);
        if (!expiry.isAfter(today)) {
            expiry = lastWeekdayOfMonth(year + 1, 8, DayOfWeek.TUESDAY);
        }
        return expiry;
    }

    private LocalDate lastWeekdayOfMonth(int year, int month, DayOfWeek weekday) {
        LocalDate date = YearMonth.of(year, month).atEndOfMonth();
        while (date.getDayOfWeek() != weekday) {
            date = date.minusDays(1);
        }
        return date;
    }
}
