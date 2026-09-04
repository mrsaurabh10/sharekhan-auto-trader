package org.com.sharekhan.parser;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WhatsappSignalParserTest {


    private final WhatsappSignalParser parser = new WhatsappSignalParser();

    @Test
    public void testOptionCallWithExpiryAndMultipleTargets() {
        String text = "*Hi, Your Subscribed Trading Signal Is Ready:*\n" +
                "INSTRUMENT: BIOCON 25 NOV 365 CALL\n" +
                "ENTRY: 12\n" +
                "STOP LOSS: 11\n" +
                "TARGET: 12.70/13.40/14";

        Map<String, Object> result = parser.parse(text);
        assertNotNull(result);
        assertEquals("BIOCON", result.get("symbol"));
        assertEquals(365.0, (Double) result.get("strike"), 0.01);
        assertEquals("CE", result.get("optionType"));
        assertEquals("25/11/" + java.time.LocalDate.now().getYear(), result.get("expiry"));
        assertEquals(12.0, (Double) result.get("entry"), 0.01);
        assertEquals(11.0, (Double) result.get("stopLoss"), 0.01);
        assertEquals(12.70, (Double) result.get("target1"), 0.01);
        assertEquals(13.40, (Double) result.get("target2"), 0.01);
        assertEquals(14.0, (Double) result.get("target3"), 0.01);
        assertEquals(true, result.get("intraday"));
        assertEquals("awr", result.get("source"));
    }

    @Test
    public void testFutureInstrument() {
        String text = "*Hi, Your Subscribed Trading Signal Is Ready:*\n" +
                "INSTRUMENT: DALBHARAT OCT FUT\n" +
                "ENTRY: 2250\n" +
                "STOP LOSS: 2235\n" +
                "TARGET: 2260/2270/2280";

        Map<String, Object> result = parser.parse(text);
        assertNotNull(result);
        assertEquals("DALBHARAT", result.get("symbol"));
        assertNull(result.get("strike"));
        assertEquals("FUT", result.get("optionType"));
        assertTrue(((String) result.get("expiry")).contains("/10/"));
        assertEquals(2250.0, (Double) result.get("entry"), 0.01);
        assertEquals(2235.0, (Double) result.get("stopLoss"), 0.01);
        assertEquals(2260.0, (Double) result.get("target1"), 0.01);
        assertEquals(2270.0, (Double) result.get("target2"), 0.01);
        assertEquals(2280.0, (Double) result.get("target3"), 0.01);
    }


    @Test
    public void testOptionPutWithExpiry() {
        String text = "*Hi, Your Subscribed Trading Signal Is Ready:*\n" +
                "INSTRUMENT: BANKNIFTY NOV 35000 PUT\n" +
                "ENTRY: 410\n" +
                "STOP LOSS: 390\n" +
                "TARGET: 420/450";

        Map<String, Object> result = parser.parse(text);
        assertNotNull(result);
        assertEquals("BANKNIFTY", result.get("symbol"));
        assertEquals(35000.0, (Double) result.get("strike"), 0.01);
        assertEquals("PE", result.get("optionType"));
        String expiry = (String) result.get("expiry");
        assertTrue(expiry.isEmpty() || expiry.matches("\\d{2}/\\d{2}/\\d{4}"));
        assertEquals(410.0, (Double) result.get("entry"), 0.01);
        assertEquals(390.0, (Double) result.get("stopLoss"), 0.01);
        assertEquals(420.0, (Double) result.get("target1"), 0.01);
        assertEquals(450.0, (Double) result.get("target2"), 0.01);
        assertEquals(0.0, (Double) result.get("target3"), 0.01);
    }

    @Test
    public void testOptionPutWithQualifiedEntry() {
        String text = "*Hi, Your Subscribed Trading Signal Is Ready:*\n" +
                "INSTRUMENT: DIXON 28 JUL 13600 PUT\n" +
                "ENTRY: ABOVE 150\n" +
                "STOP LOSS: 110\n" +
                "TARGET: 170/190/210\n\n" +
                "This is based on your chosen alert preferences.";

        Map<String, Object> result = parser.parse(text);

        assertNotNull(result);
        assertEquals("DIXON", result.get("symbol"));
        assertEquals(13600.0, (Double) result.get("strike"), 0.01);
        assertEquals("PE", result.get("optionType"));
        assertEquals(150.0, (Double) result.get("entry"), 0.01);
        assertEquals(110.0, (Double) result.get("stopLoss"), 0.01);
        assertEquals(170.0, (Double) result.get("target1"), 0.01);
        assertEquals(190.0, (Double) result.get("target2"), 0.01);
        assertEquals(210.0, (Double) result.get("target3"), 0.01);
    }

    @Test
    public void parsesStockBazaariWhatsappEquityAsDeliveryWithTsl() {
        Map<String, Object> result = parser.parse("""
                *Hi, Your Subscribed Trading Signal Is Ready:*
                INSTRUMENT: RELIANCE-EQ
                ENTRY: ABOVE 1450.50
                STOP LOSS: 1425
                TARGET: 1475/1500/1530
                """);

        assertNotNull(result);
        assertEquals("StockBazaari", result.get("source"));
        assertEquals("RELIANCE", result.get("symbol"));
        assertEquals("NC", result.get("exchange"));
        assertNull(result.get("optionType"));
        assertEquals(false, result.get("intraday"));
        assertEquals(true, result.get("tslEnabled"));
    }

    @Test
    public void testInvalidInputReturnsNull() {
        String text = "Random invalid text without required fields";
        Map<String, Object> result = parser.parse(text);
        assertNull(result);
    }
}
