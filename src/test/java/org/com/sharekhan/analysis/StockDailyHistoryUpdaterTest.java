package org.com.sharekhan.analysis;
import org.junit.jupiter.api.*;
import org.com.sharekhan.service.SharekhanHistoricalService;
import org.springframework.context.ApplicationEventPublisher;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class StockDailyHistoryUpdaterTest {
    final LocalDate day=LocalDate.of(2026,10,30);final Instant now=day.atTime(19,0).atZone(StockAnalysisService.IST).toInstant();
    final StockDailyHistoryStore store=mock(StockDailyHistoryStore.class);final DhanDailyHistoryClient client=mock(DhanDailyHistoryClient.class);final ApplicationEventPublisher events=mock(ApplicationEventPublisher.class);
    List<StockDailyHistoryStore.Member> members(){return IntStream.range(0,200).mapToObj(i->new StockDailyHistoryStore.Member("S"+i,"INE000000001",day)).toList();}
    StockDailyHistoryUpdater updater(){return new StockDailyHistoryUpdater(store,client,new SwingSessions(),events,true,Clock.fixed(now,StockAnalysisService.IST));}
    @Test void alreadyCurrentUniverseAndWeekendsMakeNoApiRequests(){
        var members=members();when(store.members()).thenReturn(members);when(store.currentSymbols(eq(day),any())).thenReturn(members.stream().map(StockDailyHistoryStore.Member::symbol).collect(Collectors.toSet()));
        var updater=updater();try{updater.update();verifyNoInteractions(client);verify(store,never()).claim(any(),any());}finally{updater.close();}
    }
    @Test void missingSessionFetchesOnlyOneStockAndPreservesOthers()throws Exception{
        var members=members();when(store.members()).thenReturn(members);when(store.currentSymbols(eq(day),any())).thenReturn(members.subList(1,200).stream().map(StockDailyHistoryStore.Member::symbol).collect(Collectors.toSet()));when(store.claim(day,now)).thenReturn(true);
        var prices=new ArrayList<SharekhanHistoricalService.HistoricalCandle>();for(int i=40;i>0;i--)prices.add(new SharekhanHistoricalService.HistoricalCandle(day.minusDays(i),LocalTime.MIDNIGHT,100,102,99,101,1000L));
        var saved=new StockDailyHistoryStore.Saved("S0","10",BigDecimal.valueOf(.05),now,prices);when(store.read(any(),eq(day))).thenReturn(saved);
        var candle=new DhanDailyBars.Candle(day,day.atStartOfDay(StockAnalysisService.IST).toEpochSecond(),BigDecimal.valueOf(100),BigDecimal.valueOf(102),BigDecimal.valueOf(99),BigDecimal.valueOf(101),1000);
        when(client.fetch(eq(members.getFirst()),eq(saved),eq(day.minusDays(8)),eq(day))).thenReturn(new DhanDailyHistoryClient.Input("10",BigDecimal.valueOf(.05),List.of(candle)));
        var updater=updater();try{updater.update();verify(client,times(1)).fetch(any(),any(),any(),eq(day));verify(store).save(eq(members.getFirst()),eq("10"),any(),eq(day.minusDays(8)),eq(day),eq(List.of(candle)),eq(now));verify(store).finish(day,now,now,"COMPLETE",1,199,0,null);verify(events).publishEvent(any(StockDailyHistoryUpdater.Updated.class));}finally{updater.close();}
    }
    @Test void authenticationFailureStopsProviderCallsAndNeverWritesCandles()throws Exception{
        when(store.members()).thenReturn(members());when(store.currentSymbols(eq(day),any())).thenReturn(Set.of());when(store.claim(day,now)).thenReturn(true);when(store.read(any(),eq(day))).thenReturn(new StockDailyHistoryStore.Saved("S0","10",BigDecimal.valueOf(.05),now,List.of()));
        when(client.fetch(any(),any(),any(),any())).thenThrow(new DhanDailyHistoryClient.Unavailable("Reconnect configured account.",true));
        var updater=updater();try{updater.update();verify(client,times(1)).fetch(any(),any(),any(),any());verify(store,never()).save(any(),any(),any(),any(),any(),any(),any());verify(store).finish(day,now,now,"PARTIAL",0,0,200,"Reconnect configured account.");}finally{updater.close();}
    }
    @Test void disabledOrUnsupportedCalendarNeverStartsProviderWork(){
        var disabled=new StockDailyHistoryUpdater(store,client,new SwingSessions(),events,false,Clock.fixed(now,StockAnalysisService.IST));try{assertThat(disabled.request()).isFalse();verifyNoInteractions(client,store);}finally{disabled.close();}
        var future=new StockDailyHistoryUpdater(store,client,new SwingSessions(),events,true,Clock.fixed(Instant.parse("2027-01-05T15:00:00Z"),StockAnalysisService.IST));try{future.update();verifyNoInteractions(client,store);}finally{future.close();}
    }
    @Test void reportListenerFailureCannotTurnCommittedHistoryIntoFailedUpdate()throws Exception {
        when(store.members()).thenReturn(members());when(store.currentSymbols(eq(day),any())).thenReturn(Set.of());when(store.claim(day,now)).thenReturn(true);
        when(store.read(any(),eq(day))).thenReturn(new StockDailyHistoryStore.Saved("S0","10",BigDecimal.valueOf(.05),now,List.of()));
        when(client.fetch(any(),any(),any(),any())).thenThrow(new DhanDailyHistoryClient.Unavailable("Delayed data",true));
        doThrow(new IllegalStateException("Report storage busy")).when(events).publishEvent(any(StockDailyHistoryUpdater.Updated.class));
        var updater=updater();try{updater.update();verify(store).finish(day,now,now,"PARTIAL",0,0,200,"Delayed data");verify(store,never()).finish(eq(day),eq(now),eq(now),eq("FAILED"),anyInt(),anyInt(),anyInt(),any());}finally{updater.close();}
    }
}
