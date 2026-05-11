package org.com.sharekhan.parser;

import org.junit.jupiter.api.Test;

import java.util.Map;

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
        assertEquals(110.0, (Double) result.get("stopLoss"), 0.01);
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
}
