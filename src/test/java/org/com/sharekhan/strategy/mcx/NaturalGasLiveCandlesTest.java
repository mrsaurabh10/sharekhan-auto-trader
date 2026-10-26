package org.com.sharekhan.strategy.mcx;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.com.sharekhan.repository.StrategyCandleHistoryRepository;
import org.junit.jupiter.api.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NaturalGasLiveCandlesTest {
    final StrategyCandleHistoryRepository repository=mock(StrategyCandleHistoryRepository.class);
    final NaturalGasLiveCandles live=new NaturalGasLiveCandles(repository);
    final LocalDate day=LocalDate.of(2026,10,26);
    final ObjectMapper mapper=new ObjectMapper();
    @BeforeEach void watch(){live.watch(42);}
    void tick(LocalTime t,double price,long qty) throws Exception {tick(t,price,qty,"MX",42);}
    void tick(LocalTime t,double price,long qty,String exchange,int code) throws Exception {
        var d=mapper.createObjectNode().put("scripCode",code).put("exchangeCode",exchange)
                .put("lastUpdatedTime",day.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))+" "+t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")))
                .put("ltp",price).put("qty",qty).put("open",300).put("high",301).put("low",299);
        live.record(d,day.atTime(t).atZone(ZoneId.of("Asia/Kolkata")).toInstant());
    }
    void firstBar() throws Exception {
        for(int i=5;i<300;i+=10)tick(LocalTime.of(9,0).plusSeconds(i),300+(i%3)*.1,100+i);
    }
    @Test void completedObservedBarUsesCumulativeVolumeDeltasAndPersists() throws Exception {
        firstBar();assertTrue(live.completed(42,day.atTime(9,5)).isEmpty());
        tick(LocalTime.of(9,5,5),302,420);
        var c=live.completed(42,day.atTime(9,5,6)).getFirst();
        assertEquals(LocalTime.of(9,0),c.time());assertEquals(395L,c.volume());
        assertEquals(300,c.open());assertEquals(301,c.high());assertEquals(299,c.low());
        assertTrue(c.close()<302); // next interval's first price/volume cannot leak into the old interval
        verify(repository).save(any());
    }
    @Test void midSessionSubscriptionCannotManufactureOpeningOrPartialBar() throws Exception {
        tick(LocalTime.of(11,2),300,1000);tick(LocalTime.of(11,2,10),301,1010);
        tick(LocalTime.of(11,5,5),302,1020);
        assertTrue(live.completed(42,day.atTime(11,6)).isEmpty());verify(repository,never()).save(any());
    }
    @Test void disconnectDiscardsIncompleteBarEvenWhenReconnectedQuickly() throws Exception {
        firstBar();live.interrupted(new NaturalGasLiveCandles.StreamInterrupted());
        tick(LocalTime.of(9,5,5),300,420);assertTrue(live.completed(42,day.atTime(9,6)).isEmpty());
    }
    @Test void observationGapDoesNotBecomeACompleteCandle() throws Exception {
        tick(LocalTime.of(9,0,5),300,100);tick(LocalTime.of(9,4,55),301,400);tick(LocalTime.of(9,5,5),302,420);
        assertTrue(live.completed(42,day.atTime(9,6)).isEmpty());
    }
    @Test void wrongExchangeOrUnwatchedContractCannotProduceCandles() throws Exception {
        for(int i=5;i<310;i+=10) {tick(LocalTime.of(9,0).plusSeconds(i),300,100+i,"NF",42);tick(LocalTime.of(9,0).plusSeconds(i),300,100+i,"MX",43);}
        assertTrue(live.completed(42,day.atTime(9,6)).isEmpty());verify(repository,never()).save(any());
    }
    @Test void cumulativeCounterResetInvalidatesBar() throws Exception {
        firstBar();tick(LocalTime.of(9,4,56),300,1);tick(LocalTime.of(9,5,5),300,2);
        assertTrue(live.completed(42,day.atTime(9,6)).isEmpty());
    }
    @Test void completedCandlesSurviveACollectorRestart() throws Exception {
        firstBar();tick(LocalTime.of(9,5,5),302,420);
        var captor=org.mockito.ArgumentCaptor.forClass(org.com.sharekhan.entity.StrategyCandleHistoryEntity.class);
        verify(repository).save(captor.capture());
        when(repository.findTop1000ByInstrumentKeyOrderByCandleTimeDesc("SHAREKHAN_MCX_LIVE:42")).thenReturn(java.util.List.of(captor.getValue()));
        var restarted=new NaturalGasLiveCandles(repository);restarted.watch(42);
        assertEquals(live.completed(42,day.atTime(9,6)),restarted.completed(42,day.atTime(9,6)));
    }
    @Test void duplicateSnapshotDoesNotDoubleCountVolume() throws Exception {
        firstBar();tick(LocalTime.of(9,4,55),300.1,395);tick(LocalTime.of(9,5,5),302,420);
        assertEquals(395L,live.completed(42,day.atTime(9,6)).getFirst().volume());
    }
    @Test void staleProviderSnapshotCannotSeedAnOpeningCandle() throws Exception {
        var data=mapper.createObjectNode().put("scripCode",42).put("exchangeCode","MX")
                .put("lastUpdatedTime","23/10/2026 09:00:05").put("ltp",300).put("qty",100);
        live.record(data,day.atTime(9,0,5).atZone(ZoneId.of("Asia/Kolkata")).toInstant());
        assertTrue(live.completed(42,day.atTime(9,6)).isEmpty());assertTrue(live.status(42).contains("Rejected stale"));
    }
    @Test void missingVolumeObservationInvalidatesAnOtherwiseCompleteBar() throws Exception {
        firstBar();
        var data=mapper.createObjectNode().put("scripCode",42).put("exchangeCode","MX")
                .put("lastUpdatedTime","26/10/2026 09:04:56").put("ltp",300);
        live.record(data,day.atTime(9,4,56).atZone(ZoneId.of("Asia/Kolkata")).toInstant());
        tick(LocalTime.of(9,5,5),302,420);assertTrue(live.completed(42,day.atTime(9,6)).isEmpty());
    }
    @Test void acceptsProviderIsoLocalAndOffsetTimestampsWithoutDroppingTimezone() {
        assertEquals(day.atTime(11,24),NaturalGasLiveCandles.providerTime("2026-10-26 11:24:00"));
        assertEquals(day.atTime(11,24),NaturalGasLiveCandles.providerTime("2026-10-26T05:54:00Z"));
        assertEquals(day.atTime(11,24),NaturalGasLiveCandles.providerTime("26/10/2026 11:24:00"));
        assertEquals(day.atTime(11,30,56),NaturalGasLiveCandles.providerTime("10/26/2026 11:30:56"));
        assertEquals(LocalDateTime.of(2026,11,6,9,0),NaturalGasLiveCandles.providerTime("11/06/2026 09:00:00"));
        var instant=day.atTime(11,24).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
        assertEquals(day.atTime(11,24),NaturalGasLiveCandles.providerTime(Long.toString(instant.toEpochMilli())));
        assertEquals(day.atTime(11,24),NaturalGasLiveCandles.providerTime(Long.toString(instant.getEpochSecond())));
    }
}
