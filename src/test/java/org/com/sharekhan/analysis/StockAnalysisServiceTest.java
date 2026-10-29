package org.com.sharekhan.analysis;

import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.service.SharekhanHistoricalService;
import org.com.sharekhan.service.SharekhanHistoricalService.HistoricalCandle;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockAnalysisServiceTest {
    private final StockAnalysisService.Instrument nse = new StockAnalysisService.Instrument(10,"DEMO","NC");
    private final LocalDate day = LocalDate.of(2026,10,29);
    private Instant at(int hour, int minute) { return day.atTime(hour, minute).atZone(StockAnalysisService.IST).toInstant(); }
    private HistoricalCandle bar(LocalDate date, LocalTime time, double close, Long volume) {
        return new HistoricalCandle(date,time,close-1,close+2,close-2,close,volume);
    }
    private List<HistoricalCandle> intraday(int count) {
        List<HistoricalCandle> bars = new ArrayList<>();
        for (int i=0;i<count;i++) bars.add(bar(day,LocalTime.of(9,15).plusMinutes(i*5L),100+i,100L));
        return bars;
    }
    @Test void normalizesProviderEndStampsAndExcludesFormingFutureAndPreopen() {
        var input = new ArrayList<>(List.of(bar(day,LocalTime.of(9,19,59),100,100L),bar(day,LocalTime.of(9,24,59),101,100L),
                bar(day,LocalTime.of(9,14,59),99,100L),bar(day,LocalTime.of(9,30),102,100L),bar(day.plusDays(1),LocalTime.of(9,15),103,100L)));
        var report = StockAnalysisService.calculate(nse,"INTRADAY",input,at(9,32));
        assertThat(report.candles()).hasSize(2);
        assertThat(report.candles().getFirst().time()).isEqualTo(LocalTime.of(9,15));
        assertThat(report.asOf()).isEqualTo(day.atTime(9,25));
        assertThat(report.indicators().openingHigh()).isNull();
        assertThat(report.warnings()).anyMatch(s -> s.contains("forming/future"));
    }
    @Test void exactCompletionBoundaryIsAcceptedWithoutLookingAhead() {
        var report = StockAnalysisService.calculate(nse,"INTRADAY",intraday(4),at(9,30));
        assertThat(report.candles()).hasSize(3);
        assertThat(report.indicators().openingHigh()).isEqualTo(104);
        assertThat(report.indicators().openingLow()).isEqualTo(98);
        assertThat(report.dataStatus()).isEqualTo("RECENT");
    }
    @Test void conflictingDuplicatesAreRemovedAndBlockConclusions() {
        var input = intraday(55); input.add(input.getFirst()); input.add(bar(day,LocalTime.of(9,20),999,100L));
        Collections.reverse(input);
        var report = StockAnalysisService.calculate(nse,"INTRADAY",input,at(15,30));
        assertThat(report.duplicates()).isEqualTo(2);
        assertThat(report.candles()).hasSize(54);
        assertThat(report.dataStatus()).isEqualTo("INCOMPLETE");
        assertThat(report.indicators().sma50()).isNull();
    }
    @Test void identicalDuplicatesDoNotInvalidatePricesAndSortingIsStable() {
        var input = intraday(55); input.add(input.getFirst()); Collections.reverse(input);
        var report = StockAnalysisService.calculate(nse,"INTRADAY",input,at(15,30));
        assertThat(report.duplicates()).isOne();
        assertThat(report.candles()).hasSize(55);
        assertThat(report.indicators().sma20()).isCloseTo(144.5,within(.0001));
        assertThat(report.indicators().sma50()).isCloseTo(129.5,within(.0001));
        assertThat(report.trend()).isEqualTo("BULLISH");
        assertThat(report.dataStatus()).isEqualTo("HISTORICAL");
    }
    @Test void gapsAndInvalidOhlcWithholdSignalsInsteadOfPretendingContinuity() {
        var input = intraday(55); input.remove(10); input.add(new HistoricalCandle(day,LocalTime.of(14,30),100,90,95,100,100L));
        var report = StockAnalysisService.calculate(nse,"INTRADAY",input,at(15,30));
        assertThat(report.gaps()).isOne(); assertThat(report.rejected()).isOne();
        assertThat(report.indicators().rsi14()).isNull(); assertThat(report.trend()).isEqualTo("INSUFFICIENT_DATA");
    }
    @Test void missingVolumeAndMissingOpeningBarsCannotProduceSessionVwap() {
        var missing = intraday(5); missing.set(1,bar(day,LocalTime.of(9,20),101,null));
        assertThat(StockAnalysisService.calculate(nse,"INTRADAY",missing,at(10,0)).indicators().vwap()).isNull();
        assertThat(StockAnalysisService.calculate(nse,"INTRADAY",intraday(5).subList(1,5),at(10,0)).indicators().vwap()).isNull();
        assertThat(StockAnalysisService.calculate(nse,"INTRADAY",intraday(3),at(10,0)).indicators().vwap()).isCloseTo(101.,within(.00001));
    }
    @Test void dailyBarsExcludeCurrentDateAndRetainDateOnlyProviderCandles() {
        var input = new ArrayList<HistoricalCandle>();
        for (int i=0;i<202;i++) input.add(bar(day.minusDays(201-i),null,100+i,1000L));
        var report = StockAnalysisService.calculate(nse,"SWING",input,at(22,0));
        assertThat(report.candles()).hasSize(201); assertThat(report.candles().getLast().date()).isEqualTo(day.minusDays(1));
        assertThat(report.indicators().sma200()).isCloseTo(200.5,within(.0001));
        assertThat(report.indicators().rsi14()).isEqualTo(100);
        assertThat(report.indicators().atr14()).isCloseTo(4.,within(.0001));
        assertThat(report.indicators().vwap()).isNull(); assertThat(report.indicators().priorClose()).isEqualTo(299);
        assertThat(report.indicators().rangeHigh20()).isEqualTo(301);
        assertThat(report.indicators().rangeLow20()).isEqualTo(278);
        assertThat(report.asOf()).isEqualTo(day.minusDays(1).atTime(15,30));
    }
    @Test void emptyOrShortHistoryReportsUnavailableRatherThanInventedIndicators() {
        var report = StockAnalysisService.calculate(nse,"INTRADAY",List.of(),at(10,0));
        assertThat(report.dataStatus()).isEqualTo("UNAVAILABLE"); assertThat(report.asOf()).isNull();
        assertThat(report.indicators().sma200()).isNull();
        assertThat(report.warnings()).anyMatch(s -> s.contains("expired"));
        assertThat(StockAnalysisService.calculate(nse,"INTRADAY",intraday(14),at(12,0)).indicators().rsi14()).isNull();
    }
    @Test void rsiIsNeutralForFlatPricesAndDecliningSeriesIsBearish() {
        var flat = new ArrayList<HistoricalCandle>(); var down = new ArrayList<HistoricalCandle>();
        for(int i=0;i<60;i++) { flat.add(bar(day.minusDays(60-i),null,100,100L)); down.add(bar(day.minusDays(60-i),null,200-i,100L)); }
        assertThat(StockAnalysisService.calculate(nse,"SWING",flat,at(22,0)).indicators().rsi14()).isEqualTo(50);
        var report = StockAnalysisService.calculate(nse,"SWING",down,at(22,0));
        assertThat(report.indicators().rsi14()).isZero(); assertThat(report.trend()).isEqualTo("BEARISH");
    }
    @Test void exchangeAndDerivativeIdentityChecksHappenBeforeProviderCall() {
        var repo = mock(ScriptMasterRepository.class); var provider = mock(SharekhanHistoricalService.class);
        var service = new StockAnalysisService(repo,provider);
        var row = ScriptMasterEntity.builder().scripCode(10).exchange("BC").tradingSymbol("DEMO").build();
        when(repo.findByScripCode(10)).thenReturn(row);
        assertThatThrownBy(() -> service.analyze("NC",10,"INTRADAY")).hasMessageContaining("404");
        assertThatThrownBy(() -> service.analyze("NF",10,"INTRADAY")).hasMessageContaining("400");
        assertThatThrownBy(() -> service.analyze("BC",10,"WEEKLY")).hasMessageContaining("400");
        row.setExpiry("30/10/2026"); assertThatThrownBy(() -> service.analyze("BC",10,"SWING")).hasMessageContaining("404");
        verifyNoInteractions(provider);
    }
    @Test void cachesPublicReportsAcrossUsersWithoutAnyUserOrAccountFields() {
        var repo = mock(ScriptMasterRepository.class); var provider = mock(SharekhanHistoricalService.class);
        var service = new StockAnalysisService(repo,provider,Clock.fixed(at(11,0),StockAnalysisService.IST));
        when(repo.findByScripCode(10)).thenReturn(ScriptMasterEntity.builder().scripCode(10).exchange("BC").tradingSymbol("DEMO").build());
        when(provider.getRecentRawHistoricalCandles(10,"5minute")).thenReturn(intraday(10));
        assertThat(service.analyze("BC",10,"INTRADAY")).isSameAs(service.analyze("BC",10,"INTRADAY"));
        verify(provider,times(1)).getRecentRawHistoricalCandles(10,"5minute");
    }
    @Test void searchRetainsExactExchangeAndRejectsDerivatives() {
        var repo = mock(ScriptMasterRepository.class); var provider = mock(SharekhanHistoricalService.class);
        var service = new StockAnalysisService(repo,provider);
        when(repo.findByExchangeIgnoreCase("BC")).thenReturn(List.of(
            ScriptMasterEntity.builder().scripCode(1).exchange("BC").tradingSymbol("DEMO").build(),
            ScriptMasterEntity.builder().scripCode(2).exchange("NC").tradingSymbol("DEMO").build(),
            ScriptMasterEntity.builder().scripCode(3).exchange("BC").tradingSymbol("DEMO").optionType("CE").build()));
        assertThat(service.search("BC","de")).containsExactly(new StockAnalysisService.Instrument(1,"DEMO","BC"));
        verifyNoInteractions(provider);
    }
    @Test void slowProviderDoesNotQueueOtherRequestsOnServletThreads() throws Exception {
        var repo = mock(ScriptMasterRepository.class); var provider = mock(SharekhanHistoricalService.class);
        var service = new StockAnalysisService(repo,provider,Clock.fixed(at(11,0),StockAnalysisService.IST));
        when(repo.findByScripCode(10)).thenReturn(ScriptMasterEntity.builder().scripCode(10).exchange("NC").tradingSymbol("DEMO").build());
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        when(provider.getRecentRawHistoricalCandles(10,"5minute")).thenAnswer(invocation -> {
            entered.countDown(); if (!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Provider fixture timed out"); return intraday(5);
        });
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> service.analyze("NC",10,"INTRADAY"));
        try {
            assertThat(entered.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.analyze("NC",10,"INTRADAY")).hasMessageContaining("429");
        } finally { release.countDown(); }
        assertThat(first.get(3,java.util.concurrent.TimeUnit.SECONDS).candles()).hasSize(5);
        verify(provider,times(1)).getRecentRawHistoricalCandles(10,"5minute");
    }
}
