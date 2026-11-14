package org.com.sharekhan.strategy.batman;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.service.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class BatmanLtpServiceTest {
 final Instant now=Instant.parse("2026-11-16T03:50:00Z");
 final QuoteCacheService cache=mock(QuoteCacheService.class);final ShoonyaQuoteService shoonya=mock(ShoonyaQuoteService.class);
 final MStockLtpService mstock=mock(MStockLtpService.class);final MStockInstrumentResolver resolver=mock(MStockInstrumentResolver.class);
 final BatmanLtpService service=new BatmanLtpService(cache,shoonya,mstock,resolver,Clock.fixed(now,ZoneOffset.UTC));
 final ScriptMasterEntity script=ScriptMasterEntity.builder().scripCode(123).exchange("NF").tradingSymbol("NIFTY").optionType("CE").strikePrice(26000d).expiry("29/12/2026").lotSize(65).build();
 QuoteCacheService.QuoteSnapshot ws(Instant at,String identity){return QuoteCacheService.QuoteSnapshot.builder().scripCode(123).ltpSource("SHAREKHAN_WS").ltpExchange("NF").ltpProviderIdentity(identity).lastTradedPrice(100d).lastLtpAt(at).build();}
 ShoonyaQuoteService.ExactQuote rest(Instant at){return new ShoonyaQuoteService.ExactQuote(new ShoonyaQuoteService.LiveQuote("NIFTY29DEC26C26000","77","NIFTY29DEC26C26000","77",101d,null,null),"NFO",now,now.minusSeconds(100),at);}
 void ms(){when(resolver.resolveInstrumentKey(script)).thenReturn(Optional.of("NFO:NIFTY26DEC26000CE"));when(mstock.fetchLtpForInstrumentReadOnly(anyString())).thenReturn(Map.of("instrument_token",123L,"last_price",102d));}
 @Test void exactFreshWebsocketNeedsNoBookDepth(){when(cache.getSharekhanSnapshot(123)).thenReturn(Optional.of(ws(now,"scrip:123")));var q=service.quote(script);assertThat(q.getBestBid()).isEqualTo(100);assertThat(q.getBidQuantity()).isZero();assertThat(q.getBookSource()).isEqualTo("SHAREKHAN_WS_SIM_LTP");verifyNoInteractions(shoonya,mstock);}
 @Test void staleOrWrongIdentityWebsocketFallsBackToCurrentExactRest(){when(cache.getSharekhanSnapshot(123)).thenReturn(Optional.of(ws(now.minusSeconds(6),"scrip:999")));when(shoonya.getExactQuote(script)).thenReturn(Optional.of(rest(now)));assertThat(service.quote(script).getBestAsk()).isEqualTo(101);assertThat(service.quote(script).getBestAsk()).isEqualTo(101);verify(shoonya,times(1)).getExactQuote(script);verifyNoInteractions(mstock);}
 @Test void staleRestUsesExistingReadOnlyMstock(){when(shoonya.getExactQuote(script)).thenReturn(Optional.of(rest(now.minusSeconds(6))));ms();assertThat(service.quote(script).getBookSource()).isEqualTo("MSTOCK_REST_OBSERVATION_SIM_LTP");verify(mstock,never()).fetchLtpForInstrument(anyString());}
 @Test void wrongContractTokenOrNonfinitePriceCannotBecomeLtp(){ms();when(mstock.fetchLtpForInstrumentReadOnly(anyString())).thenReturn(Map.of("instrument_token",999L,"last_price",102d));assertThat(service.quote(script)).isNull();}
 @Test void futureRestSnapshotIsRejected(){when(shoonya.getExactQuote(script)).thenReturn(Optional.of(rest(now.plusSeconds(1))));assertThat(service.quote(script)).isNull();}
 @Test void changedContractDoesNotReuseCachedPrice(){when(shoonya.getExactQuote(script)).thenReturn(Optional.of(rest(now)));assertThat(service.quote(script)).isNotNull();script.setExpiry("22/12/2026");when(shoonya.getExactQuote(script)).thenReturn(Optional.empty());assertThat(service.quote(script)).isNull();}
 @Test void providerFailureCannotBecomeRecordedPremium(){assertThat(service.quote(script)).isNull();verify(mstock,never()).fetchLtpForInstrument(anyString());}
}
