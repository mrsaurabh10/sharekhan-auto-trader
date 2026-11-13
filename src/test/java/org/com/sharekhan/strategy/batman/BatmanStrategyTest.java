package org.com.sharekhan.strategy.batman;
import org.junit.jupiter.api.*;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.*;
import org.com.sharekhan.service.broker.*;
import org.com.sharekhan.strategy.*;
import org.com.sharekhan.strategy.run.*;
import org.com.sharekhan.dhan.*;
import org.com.sharekhan.cache.QuoteCacheService;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.*;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class BatmanStrategyTest{
 final ScriptMasterRepository scripts=mock(ScriptMasterRepository.class);final BrokerCredentialsRepository credentials=mock(BrokerCredentialsRepository.class);
 final BrokerServiceFactory brokers=mock(BrokerServiceFactory.class);final BrokerService broker=mock(BrokerService.class);final DhanOptionChainClient chains=mock(DhanOptionChainClient.class);
 final DhanInstrumentResolver identities=mock(DhanInstrumentResolver.class);final BatmanBooks books=mock(BatmanBooks.class);final BatmanBasketRepository baskets=mock(BatmanBasketRepository.class);
 final BatmanBasketStore store=mock(BatmanBasketStore.class);final StrategyRunRepository runs=mock(StrategyRunRepository.class);final TriggeredTradeSetupRepository trades=mock(TriggeredTradeSetupRepository.class);
 final TradeExecutionService execution=mock(TradeExecutionService.class);final StrategyRunCoordinator coordinator=mock(StrategyRunCoordinator.class);final NseMarketCalendar calendar=mock(NseMarketCalendar.class);
 final StrategySupport support=mock(StrategySupport.class);final SharedMarketDecisions shared=new SharedMarketDecisions(Clock.fixed(Instant.parse("2026-09-15T03:50:01Z"),BatmanRules.IST));
 final BatmanStrategy strategy=new BatmanStrategy(scripts,credentials,brokers,chains,identities,books,baskets,store,runs,trades,execution,coordinator,calendar,support,shared);
 BatmanBasket claimed;final List<TriggeredTradeSetupEntity> actual=new ArrayList<>();final List<String> placedSides=new ArrayList<>();final Instant now=Instant.parse("2026-09-15T03:50:01Z");
 @BeforeEach void setup(){
  ReflectionTestUtils.setField(strategy,"clock",Clock.fixed(now,BatmanRules.IST));
  when(credentials.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().id(2L).appUserId(1L).brokerName("Simulator").active(true).tradingEnabled(true).build()));
  when(brokers.getService("Simulator")).thenReturn(broker);when(broker.supportsCarryOptionShorts()).thenReturn(true);
  when(calendar.isTradingDay(any())).thenAnswer(a->{LocalDate d=a.getArgument(0);return d.getDayOfWeek().getValue()<6&&!d.equals(LocalDate.of(2026,9,14));});
  when(scripts.findAllOptionExpiriesByTradingSymbolAndOptionType(eq("NIFTY"),anyString())).thenReturn(List.of("06/10/2026","13/10/2026","20/10/2026","27/10/2026"));
  var chain=List.of(o("CE",24500,.2,1),o("CE",24600,.16,3),o("PE",23500,-.2,2),o("PE",23400,-.16,4));
  when(chains.fetch("NIFTY",LocalDate.of(2026,10,27))).thenReturn(new DhanOptionChainClient.Chain("NIFTY",LocalDate.of(2026,10,27),24000,now,chain));
  for(var option:chain){int code=Integer.parseInt(option.securityId());var script=ScriptMasterEntity.builder().scripCode(code).exchange("NF").tradingSymbol("NIFTY").strikePrice(option.strike()).optionType(option.side()).expiry("27/10/2026").lotSize(65).build();
   when(scripts.findByTradingSymbolAndStrikePriceAndOptionTypeAndExpiry("NIFTY",option.strike(),option.side(),"27/10/2026")).thenReturn(Optional.of(script));
   when(books.book(eq(code),eq("NF"),anyBoolean(),anyLong())).thenReturn(QuoteCacheService.QuoteSnapshot.builder().scripCode(code).coherentBook(true).bestBid(60d).bestAsk(95d).bidQuantity(10000d).askQuantity(10000d).lastBookAt(now).build());
  }
  // Validity checks use the strategy clock; use a fixed source timestamp instead of the test process wall clock.
  when(books.book(anyInt(),eq("NF"),anyBoolean(),anyLong())).thenAnswer(a->{int code=a.getArgument(0);double bid=code<=2?99:70,ask=code<=2?100:71;
   return QuoteCacheService.QuoteSnapshot.builder().scripCode(code).coherentBook(true).bestBid(bid).bestAsk(ask).bidQuantity(10000d).askQuantity(10000d).lastBookAt(now).build();});
  when(identities.resolve(any())).thenAnswer(a->{TriggeredTradeSetupEntity t=a.getArgument(0);return new DhanInstrumentResolver.Instrument("NSE_FNO",chain.stream().filter(o->o.strike()==t.getStrikePrice()&&o.side().equals(t.getOptionType())).findFirst().orElseThrow().securityId(),65,new BigDecimal(".05"),1800);});
  when(coordinator.ensureCurrentRun()).thenReturn("run");when(store.claim(any())).thenAnswer(a->{claimed=a.getArgument(0);return true;});
  when(trades.findByStrategyRunId("run")).thenAnswer(a->List.copyOf(actual));
  when(execution.executeTriggeredTrade(any())).thenAnswer(a->{TriggerRequest r=a.getArgument(0);placedSides.add(r.getAction());actual.add(fill(r));return TriggerTradeRequestEntity.builder().id((long)actual.size()).status(TriggeredTradeStatus.EXECUTED).build();});
 }
 BatmanRules.Option o(String side,double k,double delta,int code){return new BatmanRules.Option(side,k,delta,70,71,10000,10000,Integer.toString(code));}
 StrategyApplyRequest request(){var r=new StrategyApplyRequest();r.setTemplateId(BatmanRules.TEMPLATE);r.setSymbol("NIFTY");r.setLots(1);r.setUserId(1L);r.setBrokerCredentialsId(2L);return r;}
 TriggeredTradeSetupEntity fill(TriggerRequest r){int code=r.getStrikePrice()==24500?1:r.getStrikePrice()==23500?2:r.getStrikePrice()==24600?3:4;return TriggeredTradeSetupEntity.builder().id((long)actual.size()+1).strategyRunId("run").appUserId(1L).brokerCredentialsId(2L).symbol("NIFTY").exchange("NF").scripCode(code).expiry(r.getExpiry()).strikePrice(r.getStrikePrice()).optionType(r.getOptionType()).entryAction(r.getAction()).source(r.getSource()).quantity((long)65*r.getQuantity()).actualEntryPrice(r.getEntryPrice()).intraday(false).status(TriggeredTradeStatus.EXECUTED).build();}
 @Test void resumesSameReviewedMonthlyPlanThroughNormalFourLegPipeline(){
  strategy.apply(request());actual.clear();placedSides.clear();
  var plan=new org.json.JSONArray(claimed.getPlanJson());for(int i=0;i<plan.length();i++)plan.getJSONObject(i).put("marketEntry",true);claimed.setPlanJson(plan.toString());
  var r=request();r.setBatmanManualEntryDay(LocalDate.of(2026,9,15));r.setBatmanRetryOfRunId("original");r.setBatmanMarketEntry(true);
  when(store.resumeNoOrder(1L,2L,"NIFTY",null,LocalDate.of(2026,9,15),"original")).thenReturn(claimed);
  when(coordinator.evaluate(eq(r),any())).thenAnswer(a->((java.util.function.Supplier<StrategyApplyResponse>)a.getArgument(1)).get());
  assertThat(strategy.resumeMarket(r).getStatus()).isEqualTo("triggered");
  assertThat(placedSides).containsExactly("BUY","BUY","SELL","SELL");assertThat(actual).hasSize(4);
  verify(store,times(2)).arm(eq(claimed),eq(new BigDecimal("5200.00")));verify(store,times(1)).claim(any());
 }
 @Test void confirmsBothLongsBeforeShortsAndArmsActualCredit(){
  var response=strategy.apply(request());assertThat(placedSides).containsExactly("BUY","BUY","SELL","SELL");assertThat(response.getStatus()).isEqualTo("triggered");
  verify(store).arm(eq(claimed),eq(new BigDecimal("5200.00")));assertThat(claimed.getBaseLots()).isEqualTo(1);assertThat(actual).allMatch(t->Boolean.FALSE.equals(t.getIntraday()));
 }
 @Test void unconfirmedLongAbortsBeforeAnyShortAndNeverRetriesClaim(){
  doAnswer(a->{TriggerRequest r=a.getArgument(0);placedSides.add(r.getAction());var t=fill(r);t.setStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);actual.add(t);return TriggerTradeRequestEntity.builder().id(1L).build();}).when(execution).executeTriggeredTrade(any());
  strategy.apply(request());assertThat(placedSides).containsExactly("BUY");verify(coordinator).requestClose("run","BATMAN_ENTRY_ABORT");verify(store,never()).arm(any(),any());
  when(baskets.existsById(anyString())).thenReturn(true);strategy.apply(request());assertThat(placedSides).hasSize(1);
 }
 @Test void unsupportedExecutionAdapterRejectedBeforeMarketReadsOrLongEntries(){when(broker.supportsCarryOptionShorts()).thenReturn(false);assertThatThrownBy(()->strategy.apply(request())).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(chains,execution);}
 @Test void missingGreeksWaitsWithoutRunOrClaim(){when(chains.fetch(anyString(),any())).thenReturn(new DhanOptionChainClient.Chain("NIFTY",LocalDate.of(2026,10,27),24000,now,List.of()));strategy.apply(request());verifyNoInteractions(execution);verify(store,never()).claim(any());verify(coordinator,never()).ensureCurrentRun();}
 @Test void mismatchedDataAndExecutionContractCannotEnter(){doReturn(new DhanInstrumentResolver.Instrument("NSE_FNO","999",65,BigDecimal.ONE,1800)).when(identities).resolve(any());strategy.apply(request());verifyNoInteractions(execution);verify(store,never()).claim(any());}
 @Test void persistedAbortIntentRetriesRunCloseAfterTransientFailure(){
  var b=BatmanBasket.builder().id("b").strategyRunId("run").state("EXITING").expiry(LocalDate.of(2026,10,27)).build();
  when(baskets.findByStateIn(anyList())).thenReturn(List.of(b));when(runs.findById("run")).thenReturn(Optional.of(StrategyRun.builder().id("run").status("OPEN").build()));
  strategy.monitor();verify(coordinator).requestClose("run","BATMAN_ENTRY_ABORT");verifyNoInteractions(execution);
 }
 @Test void legacySimulatorRoutingFlagIsPreserved(){
  when(credentials.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().id(2L).appUserId(1L).brokerName("Simulator").active(true).tradingEnabled(null).build()));
  strategy.validateConfiguration(request());verify(broker).supportsCarryOptionShorts();
 }

 @Test void reviewedMarketRetryCompletesAllLegsDespiteThinDepthAndOldObservations(){
  var r=request();r.setSubscriptionId(191L);r.setBatmanManualEntryDay(LocalDate.of(2026,9,15));r.setBatmanRetryOfRunId("prior");r.setBatmanMarketEntry(true);
  when(store.claim(any(),eq("prior"))).thenAnswer(a->{claimed=a.getArgument(0);return true;});
  when(books.marketBook(anyInt(),eq("NF"))).thenAnswer(a->{int code=a.getArgument(0);return QuoteCacheService.QuoteSnapshot.builder()
      .scripCode(code).bestBid(code<=2?99d:70d).bestAsk(code<=2?100d:71d).bidQuantity(0d).askQuantity(0d).lastBookAt(now.minusSeconds(120)).build();});
  assertThat(strategy.apply(r).getStatus()).isEqualTo("triggered");assertThat(placedSides).containsExactly("BUY","BUY","SELL","SELL");
  verify(store).arm(eq(claimed),eq(new BigDecimal("5200.00")));assertThat(claimed.getPlanJson()).contains("marketEntry");
  verify(books,never()).book(anyInt(),anyString(),anyBoolean(),anyLong());
 }
 @Test void marketRetryCanUseAnIdentifiedDhanObservedPremiumWhenQuoteRefreshIsUnavailable(){
  var r=request();r.setSubscriptionId(191L);r.setBatmanManualEntryDay(LocalDate.of(2026,9,15));r.setBatmanRetryOfRunId("prior");r.setBatmanMarketEntry(true);
  when(store.claim(any(),eq("prior"))).thenAnswer(a->{claimed=a.getArgument(0);return true;});
  assertThat(strategy.apply(r).getStatus()).isEqualTo("triggered");assertThat(placedSides).hasSize(4);
  verify(store).arm(eq(claimed),eq(new BigDecimal("8970.00")));
 }
 @Test void marketModeRequiresReviewedRetryScope(){var r=request();r.setBatmanMarketEntry(true);assertThatThrownBy(()->strategy.apply(r)).hasMessageContaining("Reviewed same-day retry");verifyNoInteractions(chains,execution);}
 @Test void longPreparationCannotAdmitAChainThatExpiredWhileBooksWerePrepared(){
  var later=now.plusSeconds(11);
  doAnswer(a->{ReflectionTestUtils.setField(strategy,"clock",Clock.fixed(later,BatmanRules.IST));return null;}).when(books).prepare(anyList());
  when(books.book(anyInt(),anyString(),anyBoolean(),anyLong())).thenAnswer(a->{int code=a.getArgument(0);return QuoteCacheService.QuoteSnapshot.builder()
      .scripCode(code).coherentBook(true).bestBid(code<=2?99d:70d).bestAsk(code<=2?100d:71d).bidQuantity(10000d).askQuantity(10000d).lastBookAt(later).build();});
  strategy.apply(request());verifyNoInteractions(execution);verify(coordinator,never()).ensureCurrentRun();
 }
 @Test void manualSimulatorEntryKeepsDailyClaimAndUsesNormalFourLegPipeline(){
  var later=now.plusSeconds(3600);ReflectionTestUtils.setField(strategy,"clock",Clock.fixed(later,BatmanRules.IST));
  var r=request();r.setBatmanManualEntryDay(LocalDate.of(2026,9,15));
  when(chains.fetch(anyString(),any())).thenReturn(new DhanOptionChainClient.Chain("NIFTY",LocalDate.of(2026,10,27),24000,later,
      List.of(o("CE",24500,.2,1),o("CE",24600,.16,3),o("PE",23500,-.2,2),o("PE",23400,-.16,4))));
  // The shared-decision clock must agree with the strategy clock.
  ReflectionTestUtils.setField(strategy,"shared",new SharedMarketDecisions(Clock.fixed(later,BatmanRules.IST)));
  when(books.book(anyInt(),anyString(),anyBoolean(),anyLong())).thenAnswer(a->{int code=a.getArgument(0);return QuoteCacheService.QuoteSnapshot.builder()
      .scripCode(code).coherentBook(true).bestBid(code<=2?99d:70d).bestAsk(code<=2?100d:71d).bidQuantity(10000d).askQuantity(10000d).lastBookAt(later).build();});
  assertThat(strategy.apply(r).getStatus()).isEqualTo("triggered");assertThat(placedSides).containsExactly("BUY","BUY","SELL","SELL");
  when(baskets.existsById(anyString())).thenReturn(true);strategy.apply(r);assertThat(placedSides).hasSize(4);
 }
 @Test void manualOverrideCannotReachLiveAdapter(){
  when(credentials.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().id(2L).appUserId(1L).brokerName("Sharekhan").active(true).tradingEnabled(true).build()));
  var r=request();r.setBatmanManualEntryDay(LocalDate.of(2026,9,15));
  assertThatThrownBy(()->strategy.apply(r)).hasMessageContaining("only for Simulator");verifyNoInteractions(chains,execution);
 }
 @Test void manualWindowDoesNotPersistToTomorrowOrAfter1500(){
  var r=request();r.setBatmanManualEntryDay(LocalDate.of(2026,9,15));
  assertThat(BatmanStrategy.entryWindow(r,LocalDateTime.of(2026,9,15,11,0))).isTrue();
  assertThat(BatmanStrategy.entryWindow(r,LocalDateTime.of(2026,9,16,11,0))).isFalse();
  assertThat(BatmanStrategy.entryWindow(r,LocalDateTime.of(2026,9,15,15,0))).isFalse();
  r.setBatmanManualEntryDay(null);assertThat(BatmanStrategy.entryWindow(r,LocalDateTime.of(2026,9,15,11,0))).isFalse();
 }
 @Test void manualFlagCannotBeSetThroughGenericApplyJson() throws Exception {
  var r=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
       .readValue("{\"batmanManualEntryDay\":\"2026-09-15\"}",StrategyApplyRequest.class);
  assertThat(r.getBatmanManualEntryDay()).isNull();
  var forged=new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"batmanMarketEntry\":true,\"batmanRetryOfRunId\":\"prior\"}",StrategyApplyRequest.class);
  assertThat(forged.isBatmanMarketEntry()).isFalse();assertThat(forged.getBatmanRetryOfRunId()).isNull();
 }
 @Test void reentryGateRunsBeforeGreekSelectionAndAnyExecution(){
  when(store.entryBlockReason(1L,2L,"NIFTY",LocalDate.of(2026,9,15))).thenReturn("Prior basket has unresolved short exposure");
  strategy.apply(request());verifyNoInteractions(chains,execution);verify(store,never()).claim(any());
 }

}
