package org.com.sharekhan.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MStockIntradayCandleServiceTest {

    @Test
    void normalizeExchangeSegmentMapsExchangeNamesAndInternalCodes() {
        assertEquals("1", MStockIntradayCandleService.normalizeExchangeSegment("NSE"));
        assertEquals("1", MStockIntradayCandleService.normalizeExchangeSegment("NC"));
        assertEquals("2", MStockIntradayCandleService.normalizeExchangeSegment("NFO"));
        assertEquals("2", MStockIntradayCandleService.normalizeExchangeSegment("NF"));
        assertEquals("3", MStockIntradayCandleService.normalizeExchangeSegment("CDS"));
        assertEquals("4", MStockIntradayCandleService.normalizeExchangeSegment("BSE"));
        assertEquals("4", MStockIntradayCandleService.normalizeExchangeSegment("BC"));
        assertEquals("5", MStockIntradayCandleService.normalizeExchangeSegment("BFO"));
        assertEquals("5", MStockIntradayCandleService.normalizeExchangeSegment("BF"));
    }

    @Test
    void normalizeExchangeSegmentKeepsNumericSegmentValues() {
        assertEquals("1", MStockIntradayCandleService.normalizeExchangeSegment("1"));
        assertEquals("2", MStockIntradayCandleService.normalizeExchangeSegment("2"));
        assertEquals("3", MStockIntradayCandleService.normalizeExchangeSegment("3"));
        assertEquals("4", MStockIntradayCandleService.normalizeExchangeSegment("4"));
        assertEquals("5", MStockIntradayCandleService.normalizeExchangeSegment("5"));
    }

    @Test
    void normalizeExchangeSegmentRejectsUnknownExchange() {
        assertThrows(IllegalArgumentException.class,
                () -> MStockIntradayCandleService.normalizeExchangeSegment("MCX"));
    }
}
