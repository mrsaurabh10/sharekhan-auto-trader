package org.com.sharekhan.parser;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpotTelegramSignalParserTest {

    private final SpotTelegramSignalParser parser = new SpotTelegramSignalParser();

    @Test
    void parsesSpotBasedOptionSignalAndEnablesSpotFlags() {
        String text = """
                BUY TATASTEEL 215 CE ABOVE SPOT 213.96

                TARGET (SPOT) :- 217.50 / 219.50 / 222.00

                SL (SPOT) :- 212.12

                MAY EXPIRY
                """;

        Map<String, Object> result = parser.parse(text);

        assertNotNull(result);
        assertEquals("telegram", result.get("source"));
        assertEquals("BUY", result.get("action"));
        assertEquals("TATASTEEL", result.get("symbol"));
        assertEquals("215", result.get("strike"));
        assertEquals("CE", result.get("optionType"));
        assertEquals(213.96, (Double) result.get("entry"), 0.01);
        assertEquals("217.50", result.get("target1"));
        assertEquals("219.50", result.get("target2"));
        assertEquals("222.00", result.get("target3"));
        assertEquals(212.12, (Double) result.get("stopLoss"), 0.01);
        assertEquals(expectedMayExpiry(), result.get("expiry"));
        assertEquals(true, result.get("useSpotPrice"));
        assertEquals(true, result.get("useSpotForEntry"));
        assertEquals(true, result.get("useSpotForSl"));
        assertEquals(true, result.get("useSpotForTarget"));
        assertEquals(true, result.get("intraday"));
    }

    @Test
    void ignoresRegularTelegramSignalsWithoutSpotMarker() {
        String text = """
                BUY TATASTEEL 215 CE ABOVE 14.5
                TARGET :- 16 / 18
                SL :- 12
                """;

        assertNull(parser.parse(text));
    }

    private String expectedMayExpiry() {
        LocalDate today = LocalDate.now();
        int year = today.getYear();
        LocalDate expiry = lastTuesdayOfMay(year);
        if ((today.getMonthValue() == 5 && !expiry.isAfter(today)) || today.getMonthValue() > 5) {
            expiry = lastTuesdayOfMay(year + 1);
        }
        return expiry.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    private LocalDate lastTuesdayOfMay(int year) {
        LocalDate date = YearMonth.of(year, 5).atEndOfMonth();
        while (date.getDayOfWeek() != DayOfWeek.TUESDAY) {
            date = date.minusDays(1);
        }
        return date;
    }
}
