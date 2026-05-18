package org.com.sharekhan.parser;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StockAtrSignalParserTest {

    private final StockAtrSignalParser parser = new StockAtrSignalParser();

    @Test
    void parsesOneLineAtrSignal() {
        Map<String, Object> result = parser.parse("ATR TATASTEEL LONG 150.5 LOTS 2");

        assertNotNull(result);
        assertEquals(true, result.get("stockAtrTrade"));
        assertEquals("TATASTEEL", result.get("stock"));
        assertEquals(150.5, (Double) result.get("entry"), 0.01);
        assertEquals("LONG", result.get("direction"));
        assertEquals(2, result.get("quantity"));
    }

    @Test
    void parsesKeyValueAtrSignal() {
        String text = """
                ATR TRADE
                STOCK: BAJAJ-AUTO
                ENTRY: 10100
                DIRECTION: SHORT
                LOTS: 1
                """;

        Map<String, Object> result = parser.parse(text);

        assertNotNull(result);
        assertEquals("BAJAJ-AUTO", result.get("stock"));
        assertEquals(10100.0, (Double) result.get("entry"), 0.01);
        assertEquals("SHORT", result.get("direction"));
        assertEquals(1, result.get("quantity"));
    }
}
