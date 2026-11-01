package org.com.sharekhan.analysis;
import org.junit.jupiter.api.Test;
import org.com.sharekhan.service.SharekhanHistoricalService.HistoricalCandle;
import java.time.*;import java.math.*;import java.util.*;
import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;
class MomentumResearchTest {
 final LocalDate day=LocalDate.of(2026,10,30);final SwingSessions calendar=new SwingSessions();
 List<HistoricalCandle> bars(boolean breakout){
  var rows=new ArrayList<HistoricalCandle>();LocalDate d=day;
  while(rows.size()<220){if(d.getDayOfWeek().getValue()<6&&(!calendar.supported(d)||calendar.regular(d)))rows.add(new HistoricalCandle(d,LocalTime.MIDNIGHT,100,101,99,100,3_000_000L));d=d.minusDays(1);}
  Collections.reverse(rows);if(breakout)rows.set(219,new HistoricalCandle(day,LocalTime.MIDNIGHT,100,105,99.8,104,6_000_000L));return rows;
 }
 StockDailyHistoryStore.Saved saved(String symbol,String id,boolean breakout){return new StockDailyHistoryStore.Saved(symbol,id,new BigDecimal("0.05"),Instant.parse("2026-11-01T12:00:00Z"),bars(breakout));}
 MomentumResearchService service(SavedDailyAnalysis daily){return new MomentumResearchService(daily,calendar,Clock.fixed(Instant.parse("2026-11-01T17:00:00Z"),StockAnalysisService.IST));}
 @Test void percentageMomentumProducesWorstEntryPlanWithProjectedSellExits(){var r=service(mock(SavedDailyAnalysis.class)).evaluate(saved("DEMO","10",true),day,calendar.next(day));
  assertThat(r.status()).isEqualTo("CONDITIONAL_BUY");assertThat(r.triggers()).containsExactly("4_PERCENT_BREAKOUT","RANGE_EXPANSION");assertThat(r.score()).isBetween(80,95);
  var p=r.plan();assertThat(p.riskPerShare()).isCloseTo(p.maxEntry()-p.stop(),within(.00001));assertThat(p.target1()).isGreaterThanOrEqualTo(p.maxEntry()+2*p.riskPerShare());assertThat(p.target2()).isGreaterThanOrEqualTo(p.maxEntry()+3*p.riskPerShare());assertThat(p.riskPercent()).isLessThanOrEqualTo(8);assertThat(p.rsi14()).isFinite();
 }
 @Test void archivedFiveRupeeCashTickIsAcceptedAsData(){var input=saved("DEMO","10",true);var r=service(mock(SavedDailyAnalysis.class)).evaluate(new StockDailyHistoryStore.Saved(input.symbol(),input.securityId(),new BigDecimal("5"),input.capturedAt(),input.candles()),day,calendar.next(day));assertThat(r.status()).isNotEqualTo("UNAVAILABLE");}
 @Test void dollarPriceMovementAloneDoesNotBecomeATrigger(){var r=service(mock(SavedDailyAnalysis.class)).evaluate(new StockDailyHistoryStore.Saved("DEMO","10",new BigDecimal(".05"),Instant.now(),dollarOnly()),day,calendar.next(day));assertThat(r.triggers()).isEmpty();assertThat(r.plan()).isNull();}
 List<HistoricalCandle> dollarOnly(){var rows=bars(false);rows.set(219,new HistoricalCandle(day,LocalTime.MIDNIGHT,99,101,99,100,4_000_000L));return rows;}
 @Test void staleShortConflictingAndMalformedInputsNeverProducePlans(){var s=service(mock(SavedDailyAnalysis.class));
  for(var rows:List.of(List.<HistoricalCandle>of(),bars(true).subList(0,219),List.of(new HistoricalCandle(day,LocalTime.MIDNIGHT,100,90,99,100,3L)))){
   assertThat(s.evaluate(new StockDailyHistoryStore.Saved("DEMO","10",new BigDecimal(".05"),Instant.now(),rows),day,calendar.next(day)).status()).isEqualTo("UNAVAILABLE");}
  var rows=bars(true);rows.add(new HistoricalCandle(day,LocalTime.MIDNIGHT,100,106,99,103,3L));assertThat(s.evaluate(new StockDailyHistoryStore.Saved("DEMO","10",new BigDecimal(".05"),Instant.now(),rows),day,calendar.next(day)).plan()).isNull();
 }
 @Test void futurePricesDoNotChangeHistoricalScoring(){var s=service(mock(SavedDailyAnalysis.class));var input=saved("DEMO","10",true);var before=s.evaluate(input,day,calendar.next(day));var rows=bars(true);rows.add(new HistoricalCandle(day.plusDays(1),LocalTime.MIDNIGHT,100,2000,1,1000,20_000_000L));assertThat(s.evaluate(new StockDailyHistoryStore.Saved("DEMO","10",input.tick(),input.capturedAt(),rows),day,calendar.next(day))).isEqualTo(before);}
 @Test void missingRecentSessionsAndCorporateActionsFailClosed(){var s=service(mock(SavedDailyAnalysis.class));var rows=bars(true);rows.remove(210);rows.add(0,new HistoricalCandle(LocalDate.of(2025,1,2),LocalTime.MIDNIGHT,100,101,99,100,3_000_000L));assertThat(s.evaluate(new StockDailyHistoryStore.Saved("DEMO","10",new BigDecimal(".05"),Instant.now(),rows),day,calendar.next(day)).reason()).contains("Missing recent");
  rows=bars(true);rows.set(100,new HistoricalCandle(rows.get(100).date(),LocalTime.MIDNIGHT,200,202,198,200,3_000_000L));assertThat(s.evaluate(new StockDailyHistoryStore.Saved("DEMO","10",new BigDecimal(".05"),Instant.now(),rows),day,calendar.next(day)).reason()).contains("corporate-action");
 }
 SavedDailyAnalysis mocked(boolean allStrong,boolean collision){var daily=mock(SavedDailyAnalysis.class);var members=new ArrayList<StockDailyHistoryStore.Member>();for(int i=0;i<200;i++){String symbol="DEMO"+i;members.add(new StockDailyHistoryStore.Member(symbol,"INE"+i,day));when(daily.read(symbol,day)).thenReturn(saved(symbol,collision?"10":String.valueOf(100+i),allStrong||i==0));}when(daily.members()).thenReturn(members);return daily;}
 @Test void marketCautionRetainsSetupEvidenceButWithholdsEveryEntry(){var report=service(mocked(false,false)).report();assertThat(report.status()).isEqualTo("MARKET_CAUTION");assertThat(report.stocks()).allMatch(r->r.plan()==null);assertThat(report.stocks()).anyMatch(r->r.status().equals("WATCH_ONLY")&&!r.triggers().isEmpty());}
 @Test void readyReportAndMinuteCacheUseOnlySavedInputs(){var daily=mocked(true,false);var service=service(daily);var a=service.report();assertThat(a.status()).isEqualTo("READY");assertThat(a.stocks()).allMatch(r->r.plan()!=null);assertThat(service.report()).isSameAs(a);verify(daily,times(1)).read("DEMO0",day);}
 @Test void identityCollisionsAndPartialUniverseBlockPlans(){var report=service(mocked(true,true)).report();assertThat(report.status()).isEqualTo("DATA_INCOMPLETE");assertThat(report.coveredCount()).isZero();assertThat(report.stocks()).allMatch(r->r.plan()==null);var daily=mocked(true,false);var partial=daily.members().subList(0,10);when(daily.members()).thenReturn(partial);assertThat(service(daily).report().status()).isEqualTo("DATA_INCOMPLETE");}
}
