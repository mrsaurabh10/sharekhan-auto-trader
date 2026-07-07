package org.com.sharekhan.controller;

import org.com.sharekhan.auth.BrokerAuthProviderRegistry;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.service.MStockHistoricalService;
import org.com.sharekhan.service.MStockInstrumentCacheService;
import org.com.sharekhan.service.MStockInstrumentResolver;
import org.com.sharekhan.service.MStockIntradayCandleService;
import org.com.sharekhan.service.MStockLtpService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MStockControllerTest {

    private final MStockIntradayCandleService intradayCandleService = mock(MStockIntradayCandleService.class);
    private final MStockController controller = new MStockController(
            mock(MStockLtpService.class),
            mock(TokenStoreService.class),
            mock(BrokerAuthProviderRegistry.class),
            mock(MStockInstrumentResolver.class),
            mock(MStockInstrumentCacheService.class),
            mock(MStockHistoricalService.class),
            intradayCandleService);

    @Test
    void intradayCandlesReturnsParsedMStockResponse() {
        var candle = new MStockIntradayCandleService.IntradayCandle(
                LocalDate.of(2026, 7, 7), LocalTime.of(9, 20),
                380.5, 380.6, 380.2, 380.3, 10_000L);
        when(intradayCandleService.getIntradayCandles("NSE", "1922", "minute"))
                .thenReturn(List.of(candle));

        var response = controller.getIntradayCandles("NSE", "1922", "minute");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "success");
        assertThat(response.getBody()).containsEntry("count", 1);
        assertThat(response.getBody()).containsEntry("candles", List.of(candle));
        verify(intradayCandleService).getIntradayCandles("NSE", "1922", "minute");
    }

    @Test
    void intradayCandlesRejectsBlankToken() {
        var response = controller.getIntradayCandles("NSE", " ", "minute");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("status", "error");
    }
}
