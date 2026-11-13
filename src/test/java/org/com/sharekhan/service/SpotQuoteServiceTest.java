package org.com.sharekhan.service;

import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpotQuoteServiceTest {
    static final Instant NOW=Instant.parse("2026-11-13T05:30:00Z");
    final QuoteCacheService cache=mock(QuoteCacheService.class);
    final ScriptMasterRepository scripts=mock(ScriptMasterRepository.class);
    final ShoonyaQuoteService shoonya=mock(ShoonyaQuoteService.class);
    final MStockLtpService mstock=mock(MStockLtpService.class);
    final MStockInstrumentResolver resolver=mock(MStockInstrumentResolver.class);
    final WebSocketSubscriptionService websocket=mock(WebSocketSubscriptionService.class);
    final SpotQuoteService service=new SpotQuoteService(cache,scripts,shoonya,mstock,resolver,websocket,Clock.fixed(NOW,ZoneOffset.UTC));
    final ScriptMasterEntity script=ScriptMasterEntity.builder().scripCode(20000).exchange("NC").tradingSymbol("NIFTY").build();
    @BeforeEach void setup(){
        when(scripts.findByScripCode(20000)).thenReturn(script);
        when(websocket.getActiveScripKeys()).thenReturn(Set.of("NC20000"));
    }
    QuoteCacheService.QuoteSnapshot ws(Instant at){return QuoteCacheService.QuoteSnapshot.builder().scripCode(20000).lastTradedPrice(25000d)
            .lastLtpAt(at).ltpSource("SHAREKHAN_WS").ltpExchange("NC").ltpProviderIdentity("scrip:20000").build();}
    ShoonyaQuoteService.ExactQuote exact(Instant providerAt){return new ShoonyaQuoteService.ExactQuote(
            new ShoonyaQuoteService.LiveQuote("NIFTY","26000","Nifty 50","26000",25001d,null,null),"NSE",NOW,NOW.minusSeconds(60),providerAt);}
    @Test void dedicatedWebsocketSurvivesAnotherProvidersLatestCacheUpdate(){
        var tick=ws(NOW);when(cache.getSharekhanSnapshot(20000)).thenReturn(Optional.of(tick));
        when(cache.getSnapshot(20000)).thenReturn(Optional.of(QuoteCacheService.QuoteSnapshot.builder().ltpSource("SHOONYA").build()));
        assertSame(tick,service.spot(20000,"NC"));assertTrue(service.fresh(tick,NOW,20000,"NC"));
        verifyNoInteractions(shoonya,mstock);verify(websocket,never()).subscribeToScripLtp(anyString());
    }
    @Test void exactShoonyaCurrentSnapshotAcceptsQuietLastChangeAndSharesOneRequest(){
        when(shoonya.getExactQuote(script)).thenReturn(Optional.of(exact(NOW)));
        var q=service.spot(20000,"NC");assertNotNull(q);assertEquals("SHOONYA",q.getLtpSource());assertEquals(25001,q.getLastTradedPrice());
        assertTrue(service.fresh(q,NOW,20000,"NC"));assertFalse(service.fresh(q,NOW.plusSeconds(6),20000,"NC"));
        assertFalse(service.fresh(q,NOW,20000,"BC"));assertFalse(service.fresh(q,NOW,20001,"NC"));
        assertSame(q,service.spot(20000,"NC"));verify(shoonya,times(1)).getExactQuote(script);verifyNoInteractions(mstock);
    }
    @Test void fallbackCannotAcceptArbitraryShoonyaLabelInSharedCache(){
        var fabricated=QuoteCacheService.QuoteSnapshot.builder().scripCode(20000).lastTradedPrice(25000d).lastLtpAt(NOW).ltpSource("SHOONYA").ltpExchange("NC").build();
        when(cache.getSnapshot(20000)).thenReturn(Optional.of(fabricated));
        assertFalse(service.fresh(fabricated,NOW,20000,"NC"));assertNull(service.spot(20000,"NC"));
    }
    void mstock(){
        when(resolver.resolveInstrumentKey(script)).thenReturn(Optional.of("NSE:Nifty 50"));
        when(resolver.expectedSpotToken(script,"NSE:Nifty 50")).thenReturn(Optional.of(26000L));
        when(mstock.fetchLtpForInstrumentReadOnly("NSE:Nifty 50")).thenReturn(Map.of("instrument_token",26000L,"last_price",25002d));
    }
    @Test void staleShoonyaFallsBackToExactMstockResponseWithoutLogin(){
        when(shoonya.getExactQuote(script)).thenReturn(Optional.of(exact(NOW.minusSeconds(6))));mstock();
        var q=service.spot(20000,"NC");assertNotNull(q);assertEquals("MSTOCK",q.getLtpSource());assertEquals(25002,q.getLastTradedPrice());
        assertTrue(service.fresh(q,NOW,20000,"NC"));verify(mstock).fetchLtpForInstrumentReadOnly("NSE:Nifty 50");
        verify(mstock,never()).fetchLtpForInstrument(anyString());
    }
    @Test void wrongReturnedMstockTokenNeverBecomesSpot(){
        mstock();when(mstock.fetchLtpForInstrumentReadOnly(anyString())).thenReturn(Map.of("instrument_token",123L,"last_price",25002d));
        assertNull(service.spot(20000,"NC"));assertTrue(service.reason(20000,"NC").contains("IDENTITY"));
    }
    @Test void futureProviderSnapshotAndWrongExchangeAreRejected(){
        when(shoonya.getExactQuote(script)).thenReturn(Optional.of(exact(NOW.plusSeconds(1))));
        assertNull(service.spot(20000,"NC"));assertNull(service.spot(20000,"BC"));
        assertFalse(service.fresh(ws(NOW.plusSeconds(1)),NOW,20000,"NC"));
    }
    @Test void missingWatchSubscribesAndDiagnosticsNeverFetchOrSubscribe(){
        when(websocket.getActiveScripKeys()).thenReturn(Set.of());
        when(shoonya.getExactQuote(script)).thenReturn(Optional.of(exact(NOW)));
        assertNotNull(service.spot(20000,"NC"));verify(websocket).subscribeToScripLtp("NC20000");
        clearInvocations(shoonya,mstock,websocket);
        var diagnostics=service.diagnostics(20000,"NC");assertEquals("SHOONYA_VERIFIED",diagnostics.get("reason"));
        assertNotNull(service.cached(20000,"NC"));
        verifyNoInteractions(shoonya,mstock,websocket);
    }
    @Test void derivativesCannotBeReadAsSpot(){
        script.setOptionType("CE");assertNull(service.spot(20000,"NC"));verifyNoInteractions(shoonya,mstock,websocket);
    }
}
