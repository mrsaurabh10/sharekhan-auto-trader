package org.com.sharekhan.analysis;
import org.junit.jupiter.api.Test;import org.com.sharekhan.service.NseMarketCalendar;import java.time.*;import java.util.*;import static org.mockito.Mockito.*;import static org.assertj.core.api.Assertions.*;
class IndexHistoryDailyUpdaterTest {
 final IndexHistoryDailyStore store=mock(IndexHistoryDailyStore.class);final DhanDailyHistoryClient client=mock(DhanDailyHistoryClient.class);final LocalDate day=IndexIntradayBarsTest.DAY;final Instant now=day.atTime(19,0).atZone(StockAnalysisService.IST).toInstant();final IndexHistorySessions calendar=new IndexHistorySessions(new SwingSessions(),new NseMarketCalendar(),"");
 IndexHistoryDailyUpdater updater(){return new IndexHistoryDailyUpdater(store,client,calendar,true,Clock.fixed(now,StockAnalysisService.IST));}
 void current(){when(store.pending(day)).thenReturn(List.of());when(store.latestCommon(any())).thenReturn(day);when(store.complete(any(),any())).thenReturn(true);}
 @Test void completeBothIndicesMakeZeroProviderRequests(){current();try(var u=closeable(updater())){u.value.update();verifyNoInteractions(client);verify(store,never()).claim(any(),any());}}
 @Test void completesOneMissingIndexAndReusesSavedFiveMinuteResponse()throws Exception{
  current();var j=new IndexHistoryDailyStore.Job("NIFTY",day);when(store.complete(eq("NIFTY"),eq(calendar.window(day)))).thenReturn(false);when(store.claim(j,now)).thenReturn(true);when(store.checkpoints(j)).thenReturn(new IndexHistoryDailyStore.Checkpoints(null,false,IndexIntradayBarsTest.data(5,calendar.window(day)),true));when(client.fetchIndex("NIFTY",day,1)).thenReturn(IndexIntradayBarsTest.data(1,calendar.window(day)));
  try(var u=closeable(updater())){u.value.update();verify(client).fetchIndex("NIFTY",day,1);verify(client,never()).fetchIndex(any(),any(),eq(5));verify(store).save(eq(j),any(),argThat(b->b.size()==375),argThat(b->b.size()==75),eq(now),eq(now));}
 }
 @Test void expiredTokenStopsRunAndPreservesCandles()throws Exception{current();when(store.complete(any(),any())).thenReturn(false);when(store.claim(any(),eq(now))).thenReturn(true);when(store.checkpoints(any())).thenReturn(new IndexHistoryDailyStore.Checkpoints(null,false,null,false));when(client.fetchIndex(any(),any(),eq(5))).thenThrow(new DhanDailyHistoryClient.Unavailable("Saved session expired.",true));try(var u=closeable(updater())){u.value.update();verify(client,times(1)).fetchIndex(any(),any(),anyInt());verify(store,never()).save(any(),any(),anyList(),anyList(),any(),any());verify(store).fail(any(),eq(now),eq(now),eq("Saved session expired."));}}
 @Test void partialOneMinuteResponseIsRetainedButNeverWrittenAsComplete()throws Exception{current();var j=new IndexHistoryDailyStore.Job("NIFTY",day);when(store.complete(eq("NIFTY"),eq(calendar.window(day)))).thenReturn(false);when(store.claim(j,now)).thenReturn(true);when(store.checkpoints(j)).thenReturn(new IndexHistoryDailyStore.Checkpoints(null,false,IndexIntradayBarsTest.data(5,calendar.window(day)),true));when(client.fetchIndex("NIFTY",day,1)).thenReturn("{\"timestamp\":[],\"open\":[],\"high\":[],\"low\":[],\"close\":[]}");try(var u=closeable(updater())){u.value.update();verify(store,never()).save(any(),any(),anyList(),anyList(),any(),any());verify(store).checkpoint(eq(j),eq(now),eq(1),anyString(),eq(false));verify(store).fail(eq(j),eq(now),eq(now),anyString());}}
 @Test void disabledWorkerDoesNothing(){var u=new IndexHistoryDailyUpdater(store,client,calendar,false,Clock.fixed(now,StockAnalysisService.IST));try{assertThat(u.request()).isFalse();u.update();verifyNoInteractions(store,client);}finally{u.close();}}
 @Test void intervalDisagreementInvalidatesBothCachedResponsesBeforeRetry()throws Exception {
  current();var j=new IndexHistoryDailyStore.Job("NIFTY",day);when(store.complete(eq("NIFTY"),eq(calendar.window(day)))).thenReturn(false);when(store.claim(j,now)).thenReturn(true);
  var changed=new org.json.JSONObject(IndexIntradayBarsTest.data(5,calendar.window(day)));changed.getJSONArray("high").put(0,105);
  when(store.checkpoints(j)).thenReturn(new IndexHistoryDailyStore.Checkpoints(IndexIntradayBarsTest.data(1,calendar.window(day)),true,changed.toString(),true));
  try(var u=closeable(updater())){u.value.update();verifyNoInteractions(client);verify(store).invalidatePair(j,now);verify(store,never()).save(any(),any(),anyList(),anyList(),any(),any());}
 }
 @Test void downtimeTailIsPlannedBeforeNewestSessionCanAdvanceTheWatermark(){
  current();when(store.latestCommon("NIFTY")).thenReturn(day.minusDays(20));when(store.complete(eq("NIFTY"),any())).thenReturn(false);
  try(var u=closeable(updater())){u.value.update();verify(store).plan(new IndexHistoryDailyStore.Job("NIFTY",day.minusDays(15)));verify(store).plan(new IndexHistoryDailyStore.Job("NIFTY",day));verifyNoInteractions(client);}
 }
 record Closing(IndexHistoryDailyUpdater value) implements AutoCloseable{public void close(){value.close();}}Closing closeable(IndexHistoryDailyUpdater u){return new Closing(u);}
}
