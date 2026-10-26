package org.com.sharekhan.strategy.mcx;

import org.junit.jupiter.api.*;
import org.com.sharekhan.config.ShoonyaProperties;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.ShoonyaInstrumentMasterService;
import org.com.sharekhan.util.CryptoService;
import org.json.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NaturalGasShoonyaCandlesTest {
    final LocalDateTime now=LocalDateTime.of(2026,10,26,18,47,26);
    final ShoonyaProperties properties=new ShoonyaProperties();
    final AccessTokenRepository tokens=mock(AccessTokenRepository.class);
    final CryptoService crypto=mock(CryptoService.class);
    final ShoonyaInstrumentMasterService master=mock(ShoonyaInstrumentMasterService.class);
    final StrategyCandleHistoryRepository repository=mock(StrategyCandleHistoryRepository.class);
    final ScriptMasterEntity future=NaturalGasContractsTest.row(574319,"FUTC",null,"24/11/2026",0);
    final ShoonyaInstrumentEntity instrument=ShoonyaInstrumentEntity.builder().exchange("MCX").token("574319").tradingSymbol("NATURALGAS24NOV26")
        .symbol("NATURALGAS").instrument("FUTCOM").expiry("24-NOV-2026").lotSize(1250).tickSize(.1).fetchedAt(now).build();
    NaturalGasShoonyaCandles service;
    JSONObject identity(){return new JSONObject().put("stat","Ok").put("exch","MCX").put("token","574319").put("tsym","NATURALGAS24NOV26")
        .put("exd","24-NOV-2026").put("instname","FUTCOM").put("ls","1250").put("ti","0.10");}
    static JSONObject row(String time,double o,double h,double l,double c,long volume) {
        var at=LocalDateTime.parse("2026-10-26T"+time);
        return new JSONObject().put("stat","Ok").put("time",at.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss")))
            .put("ssboe",Long.toString(at.atZone(ZoneId.of("Asia/Kolkata")).toEpochSecond()))
            .put("into",String.valueOf(o)).put("inth",String.valueOf(h)).put("intl",String.valueOf(l)).put("intc",String.valueOf(c))
            .put("intv",Long.toString(volume)).put("v","999999");
    }
    static String actualResponse(){return new JSONArray().put(row("18:45:00",291.2,291.7,290.5,291.6,615))
        .put(row("18:20:00",288.5,288.5,287.5,287.5,466)).put(row("18:15:00",288.6,288.8,287.8,288.3,824)).toString();}
    NaturalGasShoonyaCandles client()throws Exception {
        var result=spy(new NaturalGasShoonyaCandles(properties,tokens,crypto,master,repository));
        doReturn(identity().toString()).when(result).post(eq("GetSecurityInfo"),any(),eq("fixture-session"));
        doReturn(actualResponse()).when(result).post(eq("TPSeries"),any(),eq("fixture-session"));return result;
    }
    @BeforeEach void setup()throws Exception {
        properties.setUid("fixture-uid");future.setLotSize(1250);future.setTickSize(.1);
        when(tokens.findAllByBrokerName("Shoonya")).thenReturn(List.of(AccessTokenEntity.builder().token("encrypted-fixture")
            .expiry(now.toLocalDate().atTime(17,14).atZone(ZoneId.of("Asia/Kolkata")).toInstant()).build()));
        when(crypto.decrypt("encrypted-fixture")).thenReturn("fixture-session");
        when(master.resolveNaturalGasFuture(eq(future),any())).thenReturn(instrument);service=client();
    }
    @Test void actualMissingIntervalsUseIntervalVolumeAndExcludeFormingCandle() {
        var parsed=NaturalGasShoonyaCandles.parse(actualResponse(),now);
        assertEquals(1,parsed.forming());assertEquals(List.of(LocalTime.of(18,15),LocalTime.of(18,20)),parsed.completed().stream().map(c->c.time()).toList());
        assertEquals(List.of(824L,466L),parsed.completed().stream().map(c->c.volume()).toList());
        assertEquals(288.8,parsed.completed().getFirst().high());assertEquals(287.5,parsed.completed().getLast().close());
    }
    @Test void validatesClockOhlcIntervalVolumeAndDuplicateRecords() {
        var base=new JSONArray(actualResponse()).getJSONObject(1);
        for(String field:List.of("ssboe","into","intv")) {
            var bad=new JSONObject(base.toString());bad.remove(field);
            assertThrows(RuntimeException.class,()->NaturalGasShoonyaCandles.parse(new JSONArray().put(bad).toString(),now));
        }
        for(String value:List.of("-1","2.5"))assertThrows(RuntimeException.class,()->NaturalGasShoonyaCandles.parse(new JSONArray().put(new JSONObject(base.toString()).put("intv",value)).toString(),now));
        assertThrows(RuntimeException.class,()->NaturalGasShoonyaCandles.parse(new JSONArray().put(new JSONObject(base.toString()).put("intl","300")).toString(),now));
        assertThrows(RuntimeException.class,()->NaturalGasShoonyaCandles.parse(new JSONArray().put(base).put(base).toString(),now));
        assertThrows(RuntimeException.class,()->NaturalGasShoonyaCandles.parse(new JSONArray().put(new JSONObject(base.toString()).put("ssboe","1")).toString(),now));
        assertThrows(RuntimeException.class,()->NaturalGasShoonyaCandles.parse(new JSONArray().put(row("18:21:00",288,289,287,288,10)).toString(),now));
        assertThrows(RuntimeException.class,()->NaturalGasShoonyaCandles.parse(new JSONArray().put(row("18:50:00",288,289,287,288,10)).toString(),now));
        assertThrows(RuntimeException.class,()->NaturalGasShoonyaCandles.parse("{\"stat\":\"Not_Ok\"}",now));
    }
    @Test void checksExactReturnedIdentityBeforeRequestingCandles()throws Exception {
        for(var field:List.of("exch","token","tsym","exd","instname","ls","ti")) {
            var bad=identity().put(field,field.equals("ls")?"1":field.equals("ti")?"NaN":"WRONG");
            assertThrows(RuntimeException.class,()->NaturalGasShoonyaCandles.verifyIdentity(bad,instrument));
        }
        doReturn(identity().put("tsym","NATURALGAS27OCT26").toString()).when(service).post(eq("GetSecurityInfo"),any(),anyString());
        assertTrue(service.completed(future,now).isEmpty());verify(service,never()).post(eq("TPSeries"),any(),anyString());verify(repository,never()).saveAll(any());
    }
    @Test void testsCurrentDaySavedSessionDespiteEstimatedLocalExpiryWithoutLoginOrTokenWrites()throws Exception {
        assertEquals(2,service.completed(future,now).size());
        verify(service).post(eq("TPSeries"),argThat(j->j.getString("exch").equals("MCX")&&j.getString("token").equals("574319")&&j.getString("intrv").equals("5")),eq("fixture-session"));
        verify(tokens,never()).save(any());assertEquals("ok",service.diagnostics(574319).get("status"));
    }
    @Test void neverUsesAnotherUsersSessionOrPriorDaySavedSession()throws Exception {
        when(tokens.findAllByBrokerName("Shoonya")).thenReturn(List.of(AccessTokenEntity.builder().token("foreign").brokerCredentialsId(9L).expiry(now.atZone(ZoneId.of("Asia/Kolkata")).toInstant()).build()));
        assertTrue(service.completed(future,now).isEmpty());verify(crypto,never()).decrypt(anyString());verify(service,never()).post(anyString(),any(),anyString());
        when(tokens.findAllByBrokerName("Shoonya")).thenReturn(List.of(AccessTokenEntity.builder().token("old").expiry(now.minusDays(1).atZone(ZoneId.of("Asia/Kolkata")).toInstant()).build()));
        assertTrue(service.completed(future,now.plusMinutes(1)).isEmpty());verify(crypto,never()).decrypt(anyString());
    }
    @Test void cacheRefetchesAtNewFiveMinuteBoundaryEvenWithinThirtySeconds()throws Exception {
        service.completed(future,now.withMinute(49).withSecond(59));service.completed(future,now.withMinute(50).withSecond(5));
        verify(service,times(2)).post(eq("TPSeries"),any(),anyString());verify(service).post(eq("GetSecurityInfo"),any(),anyString());
        service.completed(future,now.withMinute(50).withSecond(10));verify(service,times(2)).post(eq("TPSeries"),any(),anyString());
    }
    @Test void persistsSourceSpecificBarsAndAvoidsRewritingIdenticalRowsAfterRestart()throws Exception {
        service.completed(future,now);
        var captor=org.mockito.ArgumentCaptor.forClass(Iterable.class);verify(repository).saveAll(captor.capture());
        var saved=new ArrayList<StrategyCandleHistoryEntity>();captor.getValue().forEach(x->saved.add((StrategyCandleHistoryEntity)x));
        assertEquals(2,saved.size());assertEquals("SHOONYA_MCX_TPS:574319:2026-11-24",saved.getFirst().getInstrumentKey());
        when(repository.findTop1000ByInstrumentKeyOrderByCandleTimeDesc(anyString())).thenReturn(saved);clearInvocations(repository);
        var restarted=client();assertEquals(2,restarted.completed(future,now).size());verify(repository,never()).saveAll(any());
    }
    @Test void providerFailureCannotReuseLastGoodRowsOrLeakCredentialsInDiagnostics()throws Exception {
        assertEquals(2,service.completed(future,now).size());
        doThrow(new IllegalStateException("fixture-session fixture-uid")).when(service).post(eq("TPSeries"),any(),anyString());
        assertTrue(service.completed(future,now.plusMinutes(1)).isEmpty());
        String status=service.diagnostics(574319).toString();assertFalse(status.contains("fixture-session"));assertFalse(status.contains("fixture-uid"));
        service.completed(future,now.plusMinutes(1).plusSeconds(5));verify(service,times(2)).post(eq("TPSeries"),any(),anyString());
    }
}
