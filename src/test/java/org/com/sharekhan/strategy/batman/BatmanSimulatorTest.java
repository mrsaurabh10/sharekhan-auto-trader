package org.com.sharekhan.strategy.batman;
import org.junit.jupiter.api.*;
import org.com.sharekhan.service.broker.SimulatorBrokerService;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.dto.BrokerContext;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class BatmanSimulatorTest{
 final BatmanBooks books=mock(BatmanBooks.class);final SimulatorBrokerService broker=new SimulatorBrokerService();final BrokerContext context=new BrokerContext();
 @BeforeEach void setup(){ReflectionTestUtils.setField(broker,"batmanBooks",books);}
 TriggeredTradeSetupEntity trade(String side){return TriggeredTradeSetupEntity.builder().source("batman:b:0").symbol("NIFTY").scripCode(1).exchange("NF").entryAction(side).entryPrice("SELL".equals(side)?99d:100d).actualEntryPrice(100d).quantity(65L).build();}
 QuoteCacheService.QuoteSnapshot q(Instant at,double size){return QuoteCacheService.QuoteSnapshot.builder().scripCode(1).bookProviderIdentity("exact:1").coherentBook(true).bestBid(99d).bestAsk(100d).bidQuantity(size).askQuantity(size).lastBookAt(at).build();}
 @Test void longPaysAskAndShortReceivesBidWithOppositeExitSide(){
  when(books.book(eq(1),eq("NF"),anyBoolean(),anyLong())).thenReturn(q(Instant.now(),1000));
  assertThat(broker.placeOrder(trade("BUY"),context,100).getExecutedPrice()).isEqualTo(100d);
  assertThat(broker.placeOrder(trade("SELL"),context,99).getExecutedPrice()).isEqualTo(99d);
  assertThat(broker.placeExitOrder(trade("BUY"),context,99).getExecutedPrice()).isEqualTo(99d);
  assertThat(broker.placeExitOrder(trade("SELL"),context,100).getExecutedPrice()).isEqualTo(100d);
 }
 @Test void cannotReuseDisplayedDepthAcrossTwoBasketEntries(){when(books.book(eq(1),eq("NF"),anyBoolean(),anyLong())).thenReturn(q(Instant.now(),65));broker.placeOrder(trade("SELL"),context,99);assertThatThrownBy(()->broker.placeOrder(trade("SELL"),context,99)).hasMessageContaining("already consumed");}
 @Test void staleCrossedAndMissingDepthDoNotFallBackToLtp(){
  when(books.book(eq(1),eq("NF"),anyBoolean(),anyLong())).thenReturn(q(Instant.now().minusSeconds(6),1000));assertThatThrownBy(()->broker.placeOrder(trade("SELL"),context,99)).hasMessageContaining("fresh");
  when(books.book(eq(1),eq("NF"),anyBoolean(),anyLong())).thenReturn(q(Instant.now(),0));assertThatThrownBy(()->broker.placeExitOrder(trade("SELL"),context,100)).hasMessageContaining("quantity");
 }
 @Test void nonFiniteBookDepthCannotCreateAFill(){
  when(books.book(eq(1),eq("NF"),anyBoolean(),anyLong())).thenReturn(q(Instant.now(),Double.NaN));assertThatThrownBy(()->broker.placeOrder(trade("BUY"),context,100)).hasMessageContaining("fresh");
 }
 @Test void approvedMarketFillUsesObservedPriceWithoutFrozenLimitOrDepthRejection(){
  var market=mock(BatmanMarketEntry.class);ReflectionTestUtils.setField(broker,"batmanMarketEntry",market);
  when(market.price(any())).thenReturn(java.util.OptionalDouble.of(70));
  assertThat(broker.placeOrder(trade("SELL"),context,99).getExecutedPrice()).isEqualTo(70);
  verifyNoInteractions(books);
 }
 @Test void cannotFillBeyondFrozenEntryLimit(){when(books.book(eq(1),eq("NF"),anyBoolean(),anyLong())).thenReturn(q(Instant.now(),1000));assertThatThrownBy(()->broker.placeOrder(trade("SELL"),context,100)).hasMessageContaining("price");}
 @Test void persistedFreshLtpPolicyCanCloseBothSidesWithoutDisplayedDepth(){
  var policy=mock(BatmanMarketEntry.class);ReflectionTestUtils.setField(broker,"batmanMarketEntry",policy);
  var quote=q(Instant.now(),0);when(policy.freshQuote(any())).thenReturn(java.util.Optional.of(quote));
  assertThat(broker.placeExitOrder(trade("BUY"),context,1).getExecutedPrice()).isEqualTo(99d);
  assertThat(broker.placeExitOrder(trade("SELL"),context,1).getExecutedPrice()).isEqualTo(100d);verifyNoInteractions(books);
 }
}
