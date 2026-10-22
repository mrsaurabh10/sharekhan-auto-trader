package org.com.sharekhan.strategy;

import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.service.MStockHistoricalService;
import org.com.sharekhan.service.UserConfigService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AtrPreviousDayBreakoutQualificationServiceTest {

    @Test
    void usesMStockHistoricalCandlesWhenWeekendIntradayChartIsEmpty() {
        StrategySupport support = mock(StrategySupport.class);
        MStockHistoricalService historical = mock(MStockHistoricalService.class);
        UserConfigService userConfigService = mock(UserConfigService.class);
        AtrPreviousDayBreakoutQualificationService service =
                new AtrPreviousDayBreakoutQualificationService(support, historical, userConfigService);
        ScriptMasterEntity spot = ScriptMasterEntity.builder().tradingSymbol("PFC").scripCode(14299).build();
        LocalDate friday = LocalDate.of(2026, 8, 7);
        LocalDateTime sunday = LocalDateTime.of(2026, 8, 9, 18, 34);

        when(support.loadCandles(spot, "5minute")).thenReturn(new CandleLoad(List.of(), false, "market closed"));
        when(support.resolveMStockHistoricalIdentity(spot))
                .thenReturn(java.util.Optional.of(new StrategySupport.MStockHistoricalIdentity("NSE", 14299L, "NSE:PFC-EQ")));
        when(support.roundPrice(anyDouble())).thenAnswer(invocation -> {
            double value = invocation.getArgument(0);
            return Math.round(value * 100d) / 100d;
        });
        when(historical.getHistoricalCandlesByToken(eq("NSE"), eq(14299L), eq("5minute"),
                any(String.class), eq("2026-08-09")))
                .thenReturn(MStockHistoricalService.HistoricalResponse.builder().candles(fridayCandles(friday)).build());

        Fno925EntryQualificationService.Qualification qualification = service.qualify(spot, "CE", 1L, sunday);

        assertThat(qualification.qualified()).isTrue();
        assertThat(qualification.signal().entryPrice()).isGreaterThan(100d);
        assertThat(qualification.signal().stopLoss()).isLessThan(qualification.signal().entryPrice());
    }

    @Test
    void addsMinimumBreakoutBufferWhenPriorDayCloseEqualsPdh() {
        StrategySupport support = mock(StrategySupport.class);
        MStockHistoricalService historical = mock(MStockHistoricalService.class);
        UserConfigService userConfigService = mock(UserConfigService.class);
        AtrPreviousDayBreakoutQualificationService service =
                new AtrPreviousDayBreakoutQualificationService(support, historical, userConfigService);
        ScriptMasterEntity spot = ScriptMasterEntity.builder().tradingSymbol("NTPC").scripCode(11630).build();
        LocalDate day = LocalDate.of(2026, 8, 13);
        LocalDateTime afterClose = LocalDateTime.of(2026, 8, 13, 18, 0);

        when(support.loadCandles(spot, "5minute")).thenReturn(new CandleLoad(List.of(), false, "market closed"));
        when(support.resolveMStockHistoricalIdentity(spot))
                .thenReturn(java.util.Optional.of(new StrategySupport.MStockHistoricalIdentity("NSE", 11630L, "NSE:NTPC-EQ")));
        when(support.roundPrice(anyDouble())).thenAnswer(invocation -> {
            double value = invocation.getArgument(0);
            return Math.round(value * 100d) / 100d;
        });
        when(historical.getHistoricalCandlesByToken(eq("NSE"), eq(11630L), eq("5minute"),
                any(String.class), eq("2026-08-13")))
                .thenReturn(MStockHistoricalService.HistoricalResponse.builder().candles(closeAtPdhCandles(day)).build());

        Fno925EntryQualificationService.Qualification qualification = service.qualify(spot, "CE", 1L, afterClose);

        assertThat(qualification.qualified()).isTrue();
        // ATR is 1.0067: configured 0.25 ATR offset gives 102.25, while the
        // default 0.35 ATR minimum breakout buffer lifts entry to 102.35.
        assertThat(qualification.signal().entryPrice()).isEqualTo(102.35d);
    }

    @Test
    void equalEffectiveAccountSettingsShareOneCalculationAndDifferentThresholdsDoNot() {
        StrategySupport support = mock(StrategySupport.class);
        MStockHistoricalService historical = mock(MStockHistoricalService.class);
        UserConfigService config = mock(UserConfigService.class);
        var service = new AtrPreviousDayBreakoutQualificationService(support,historical,config);
        var now=LocalDateTime.of(2026,8,13,20,25);
        var engine=new SharedMarketDecisions(java.time.Clock.fixed(now.atZone(StrategySupport.MARKET_ZONE).toInstant(),StrategySupport.MARKET_ZONE));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"sharedDecisions",engine);
        var spot=ScriptMasterEntity.builder().tradingSymbol("NTPC").scripCode(11630).build();
        when(support.loadCandles(spot,"5minute")).thenReturn(new CandleLoad(List.of(),false,"closed"));
        when(support.resolveMStockHistoricalIdentity(spot)).thenReturn(java.util.Optional.of(
                new StrategySupport.MStockHistoricalIdentity("NSE",11630L,"NSE:NTPC-EQ")));
        when(support.roundPrice(anyDouble())).thenAnswer(call -> Math.round((double)call.getArgument(0)*100)/100d);
        when(historical.getHistoricalCandlesByToken(eq("NSE"),eq(11630L),eq("5minute"),any(String.class),eq("2026-08-13")))
                .thenReturn(MStockHistoricalService.HistoricalResponse.builder().candles(closeAtPdhCandles(now.toLocalDate())).build());
        var a=service.qualify(spot,"CE",1L,now);var b=service.qualify(spot,"CE",2L,now);
        assertThat(a).isSameAs(b); assertThat(a.qualified()).isTrue();
        org.mockito.Mockito.verify(support).loadCandles(spot,"5minute");
        when(config.getConfig(2L,"fno_atr_previous_day_min_entry_distance_atr",null)).thenReturn("0.5");
        var changed=service.qualify(spot,"CE",2L,now);
        assertThat(changed).isNotSameAs(a);assertThat(changed.signal().entryPrice()).isNotEqualTo(a.signal().entryPrice());
        org.mockito.Mockito.verify(support,org.mockito.Mockito.times(2)).loadCandles(spot,"5minute");
    }

    private List<MStockHistoricalService.HistoricalCandle> fridayCandles(LocalDate friday) {
        return java.util.stream.IntStream.range(0, 76)
                .mapToObj(index -> {
                    double high = index == 40 ? 102d : 100.5d;
                    return MStockHistoricalService.HistoricalCandle.builder()
                            .date(friday).time(LocalTime.of(9, 15).plusMinutes(index * 5L))
                            .open(100d).high(high).low(99.5d).close(100d).volume(1_000L).build();
                })
                .toList();
    }

    private List<MStockHistoricalService.HistoricalCandle> closeAtPdhCandles(LocalDate day) {
        return java.util.stream.IntStream.range(0, 76)
                .mapToObj(index -> {
                    boolean finalCandle = index == 75;
                    return MStockHistoricalService.HistoricalCandle.builder()
                            .date(day).time(LocalTime.of(9, 15).plusMinutes(index * 5L))
                            .open(finalCandle ? 100d : 100d).high(finalCandle ? 102d : 100.5d)
                            .low(99.5d).close(finalCandle ? 102d : 100d).volume(1_000L).build();
                })
                .toList();
    }
}
