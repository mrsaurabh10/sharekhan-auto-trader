package org.com.sharekhan.service;

import org.com.sharekhan.dto.StockAtrTradeRequest;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockAtrTradeServiceTest {

    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Test
    void nearestExpiryHonorsRequestedExpiryMonth() {
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        StockAtrTradeService service = new StockAtrTradeService(
                scriptMasterRepository,
                mock(SharekhanHistoricalService.class));

        YearMonth nearerMonth = YearMonth.from(LocalDate.now().plusMonths(1));
        YearMonth requestedYearMonth = YearMonth.from(LocalDate.now().plusMonths(2));
        String nearerExpiry = nearerMonth.atEndOfMonth().format(EXPIRY_FORMAT);
        String requestedExpiry = requestedYearMonth.atEndOfMonth().format(EXPIRY_FORMAT);

        when(scriptMasterRepository.findAllOptionExpiriesByTradingSymbolAndOptionType("ULTRACEMCO", "CE"))
                .thenReturn(List.of(nearerExpiry, requestedExpiry));

        String expiry = service.nearestExpiry("ULTRACEMCO", "CE", requestedYearMonth.getMonthValue());

        assertThat(expiry).isEqualTo(requestedExpiry);
    }

    @Test
    void buildTriggerRequestRetriesAtrHistoricalRangeWhenPrimaryRangeIsEmpty() {
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        SharekhanHistoricalService historicalService = mock(SharekhanHistoricalService.class);
        StockAtrTradeService service = new StockAtrTradeService(scriptMasterRepository, historicalService);

        ScriptMasterEntity spot = ScriptMasterEntity.builder()
                .scripCode(12345)
                .tradingSymbol("ALKEM")
                .exchange("NC")
                .build();
        String expiry = LocalDate.now().plusDays(7).format(EXPIRY_FORMAT);

        when(scriptMasterRepository.findByExchangeIgnoreCase("NC")).thenReturn(List.of(spot));
        when(scriptMasterRepository.findAllOptionExpiriesByTradingSymbolAndOptionType("ALKEM", "CE"))
                .thenReturn(List.of(expiry));
        when(scriptMasterRepository.findStrikePricesByTradingSymbolAndOptionTypeAndExpiry("ALKEM", "CE", expiry))
                .thenReturn(List.of(5300.0, 5400.0, 5500.0));
        when(historicalService.getHistoricalCandles(eq(12345), eq("5minute"), any(), any()))
                .thenReturn(List.of())
                .thenReturn(candles(76));

        StockAtrTradeRequest request = new StockAtrTradeRequest();
        request.setStock("ALKEM");
        request.setDirection("LONG");
        request.setEntryPrice(5397.3);
        request.setSource("atr-signal");

        TriggerRequest trigger = service.buildTriggerRequest(request);

        assertThat(trigger.getInstrument()).isEqualTo("ALKEM");
        assertThat(trigger.getSpotScripCode()).isEqualTo(12345);
        assertThat(trigger.getStrikePrice()).isEqualTo(5400.0);
        assertThat(trigger.getStopLoss()).isLessThan(trigger.getEntryPrice());
        assertThat(trigger.getTarget1()).isGreaterThan(trigger.getEntryPrice());
        verify(historicalService, atLeast(2)).getHistoricalCandles(eq(12345), eq("5minute"), any(), any());
    }

    private List<SharekhanHistoricalService.HistoricalCandle> candles(int count) {
        List<SharekhanHistoricalService.HistoricalCandle> candles = new ArrayList<>();
        LocalDate startDate = LocalDate.now().minusDays(2);
        for (int i = 0; i < count; i++) {
            double base = 100 + i;
            candles.add(new SharekhanHistoricalService.HistoricalCandle(
                    startDate.plusDays(i / 75),
                    LocalTime.of(9, 15).plusMinutes((long) (i % 75) * 5),
                    base,
                    base + 2,
                    base - 2,
                    base + 1
            ));
        }
        return candles;
    }
}
