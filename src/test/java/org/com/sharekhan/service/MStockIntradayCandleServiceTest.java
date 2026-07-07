package org.com.sharekhan.service;

import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.entity.MStockInstrumentEntity;
import org.com.sharekhan.repository.MStockInstrumentRepository;
import org.com.sharekhan.util.CryptoService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void completedMinuteCandleUsesExchangeTokenAndCachesExactMinute() {
        MStockInstrumentResolver resolver = mock(MStockInstrumentResolver.class);
        MStockInstrumentRepository repository = mock(MStockInstrumentRepository.class);
        MStockIntradayCandleService service = spy(new MStockIntradayCandleService(
                mock(TokenStoreService.class), mock(CryptoService.class), resolver, repository));
        LocalDateTime requestedMinute = LocalDateTime.of(2026, 7, 7, 9, 20);
        var expected = new MStockIntradayCandleService.IntradayCandle(
                LocalDate.of(2026, 7, 7), LocalTime.of(9, 20),
                380.5, 380.6, 380.2, 380.3, 10_000L);

        when(resolver.resolveInstrumentKey(1922)).thenReturn(Optional.of("NSE:KOTAKBANK"));
        when(repository.findByInstrumentKey("NSE:KOTAKBANK")).thenReturn(Optional.of(
                MStockInstrumentEntity.builder()
                        .instrumentToken(123L)
                        .instrumentKey("NSE:KOTAKBANK")
                        .tradingSymbol("KOTAKBANK")
                        .exchange("NSE")
                        .exchangeToken("1922")
                        .fetchedAt(LocalDateTime.now())
                        .build()));
        doReturn(List.of(expected)).when(service).getIntradayCandles("NSE", "1922", "minute");

        assertSame(expected, service.getCompletedMinuteCandle(1922, requestedMinute));
        assertSame(expected, service.getCompletedMinuteCandle(1922, requestedMinute));
        verify(service, times(1)).getIntradayCandles("NSE", "1922", "minute");
    }
}
