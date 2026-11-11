package org.com.sharekhan.strategy.adxdmi;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.service.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AdxDmiMarketDataTest {
 final Instant now=Instant.parse("2026-11-11T04:30:00Z");
 final QuoteCacheService shared=new QuoteCacheService();
 final ScriptMasterRepository scripts=mock(ScriptMasterRepository.class);
 final ShoonyaQuoteService api=mock(ShoonyaQuoteService.class);
 final TradeAuditService audit=mock(TradeAuditService.class);
 final AdxDmiMarketData data=service(shared);
 final ScriptMasterEntity sensex=ScriptMasterEntity.builder().scripCode(999901).tradingSymbol("SENSEX").exchange("BC").build();
 final ScriptMasterEntity option=ScriptMasterEntity.builder().scripCode(123).tradingSymbol("SENSEX").exchange("BF").optionType("CE").strikePrice(81900d).expiry("12/11/2026").lotSize(20).build();
 AdxDmiMarketData service(QuoteCacheService cache){return new AdxDmiMarketData(cache,scripts,api,audit,Clock.fixed(now,AdxDmiRules.IST));}
 @BeforeEach void setup(){when(scripts.findByScripCode(999901)).thenReturn(sensex);when(scripts.findByScripCode(123)).thenReturn(option);}
 ShoonyaQuoteService.ExactQuote spot(String token,String symbol,String exchange,Instant provider){return new ShoonyaQuoteService.ExactQuote(new ShoonyaQuoteService.LiveQuote("SENSEX","1",symbol,token,81879.4,null,null),exchange,now,provider);}
 ShoonyaQuoteService.ExactQuote book(double bid,double ask,double qty){return new ShoonyaQuoteService.ExactQuote(new ShoonyaQuoteService.LiveQuote("SENSEX26N1281900CE","123","SENSEX26N1281900CE","123",440d,bid,ask,qty,qty),"BFO",now,now);}
 @Test void sensexRestIdentityWorksWithoutRelabelingSharedCache(){
  when(api.getExactQuote(sensex)).thenReturn(Optional.of(spot("1","SENSEX","BSE",now)));
  var q=data.spot(999901,"BC");assertNotNull(q);assertEquals(81879.4,q.getLastTradedPrice());assertEquals("1:SENSEX",q.getLtpProviderIdentity());
  assertTrue(data.freshSpot("BC",q,now,999901));assertFalse(data.freshSpot("NC",q,now,999901));assertFalse(data.freshSpot("BC",q,now.plusSeconds(6),999901));
  assertFalse(AdxDmiRules.freshSpot("BC",q,now,999901));assertTrue(shared.getSnapshot(999901).isEmpty());
  assertSame(q,data.spot(999901,"BC"));verify(api,times(1)).getExactQuote(sensex);
 }
 @Test void wrongIdentitiesAndMissingStaleOrFutureTimeAreRejected(){
  for(var q:List.of(spot("2","SENSEX","BSE",now),spot("1","BANKEX","BSE",now),spot("1","SENSEX","NSE",now),spot("1","SENSEX","BSE",now.minusSeconds(6)),spot("1","SENSEX","BSE",now.plusSeconds(1)),spot("1","SENSEX","BSE",null))){
   when(api.getExactQuote(sensex)).thenReturn(Optional.of(q));assertNull(service(shared).spot(999901,"BC"));
  }
 }
 @Test void zeroOrCrossedBooksNeverFillAndValidBookPreservesDepth(){
  for(var q:List.of(book(0,0,0),book(442,440,60))){when(api.getExactQuote(option)).thenReturn(Optional.of(q));assertNull(service(shared).book(123,"BF"));}
  when(api.getExactQuote(option)).thenReturn(Optional.of(book(439,440,60)));var q=data.book(123,"BF");assertNotNull(q);assertEquals(60,q.getAskQuantity());
  assertTrue(data.freshBook("BF",q,now,123));assertFalse(data.freshBook("NF",q,now,123));assertFalse(data.freshBook("BF",q,now.plusSeconds(6),123));
 }
 @Test void unverifiedSharedShoonyaCannotBypassApiAndFailuresAreThrottledAndAudited(){
  shared.recordQuote(999901,null,null,81879.4,"SHOONYA","BC","1:SENSEX",null,null);
  when(api.getExactQuote(sensex)).thenReturn(Optional.empty());assertNull(data.spot(999901,"BC"));assertFalse(data.freshSpot("BC",shared.getSnapshot(999901).orElseThrow(),now,999901));
  data.spot(999901,"BC");verify(api,times(1)).getExactQuote(sensex);verify(audit,times(1)).record(any());
 }
 @Test void verifiedSharekhanAvoidsRestCalls(){
  var cache=mock(QuoteCacheService.class);var q=QuoteCacheService.QuoteSnapshot.builder().scripCode(999901).lastTradedPrice(81879.4).ltpSource("SHAREKHAN_WS").ltpExchange("BC").ltpProviderIdentity("scrip:999901").lastLtpAt(now).build();
  when(cache.getSnapshot(999901)).thenReturn(Optional.of(q));assertSame(q,service(cache).spot(999901,"BC"));verifyNoInteractions(api);
 }
}
