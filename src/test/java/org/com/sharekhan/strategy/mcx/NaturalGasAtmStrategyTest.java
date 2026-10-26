package org.com.sharekhan.strategy.mcx;

import org.junit.jupiter.api.*;
import org.com.sharekhan.cache.*;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.TradeExecutionService;
import org.com.sharekhan.strategy.*;
import org.com.sharekhan.strategy.run.StrategyRiskSettings;
import java.math.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NaturalGasAtmStrategyTest {
    final ScriptMasterRepository scripts=mock(ScriptMasterRepository.class);
    final BrokerCredentialsRepository brokers=mock(BrokerCredentialsRepository.class);
    final TriggerTradeRequestRepository requests=mock(TriggerTradeRequestRepository.class);
    final TriggeredTradeSetupRepository setups=mock(TriggeredTradeSetupRepository.class);
    final NaturalGasMarketData data=mock(NaturalGasMarketData.class);
    final QuoteCacheService quotes=mock(QuoteCacheService.class);
    final TradeExecutionService execution=mock(TradeExecutionService.class);
    final StrategySupport support=mock(StrategySupport.class);
    final NaturalGasAtmStrategy strategy=new NaturalGasAtmStrategy(scripts,brokers,requests,setups,data,quotes,execution,support,new NaturalGasDataPolicy(NaturalGasDataMode.FULL_SESSION));
    final LocalDateTime now=NaturalGasSignalsTest.NOW;
    final StrategyApplyRequest request=new StrategyApplyRequest();
    ScriptMasterEntity future=NaturalGasContractsTest.row(1,"FUTC",null,"24/11/2026",0);
    ScriptMasterEntity option=NaturalGasContractsTest.row(2,"OPTC","CE","20/11/2026",155);
    QuoteCacheService.QuoteSnapshot book(Instant timestamp,String source,double bid,double ask){return QuoteCacheService.QuoteSnapshot.builder().scripCode(2).bestBid(bid).bestAsk(ask).lastBookAt(timestamp).coherentBook(true).bookSource(source).bookProviderIdentity("scrip:2").build();}
    QuoteCacheService.QuoteSnapshot futureQuote(LocalDateTime timestamp) {
        return QuoteCacheService.QuoteSnapshot.builder().scripCode(1).lastTradedPrice(153.15)
                .lastLtpAt(timestamp.atZone(StrategySupport.MARKET_ZONE).toInstant()).ltpSource("SHAREKHAN_WS").ltpProviderIdentity("scrip:1").build();
    }
    @BeforeEach void setup(){
        request.setUserId(1L);request.setBrokerCredentialsId(2L);request.setLots(1);
        var risk=new StrategyRiskSettings();risk.setMaxLoss(new BigDecimal("2000"));request.setRiskSettings(risk);
        when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().id(2L).appUserId(1L).brokerName("Simulator").build()));
        when(scripts.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase("MX","NATURALGAS")).thenReturn(List.of(future,option));
        when(data.candles(1,now)).thenReturn(NaturalGasSignalsTest.candles(true));future.setTickSize(.1);
        when(quotes.getSnapshot(1)).thenReturn(Optional.of(futureQuote(now)));
        when(quotes.getSnapshot(2)).thenReturn(Optional.of(book(now.atZone(StrategySupport.MARKET_ZONE).toInstant(),"SHAREKHAN_WS",4.95,5)));
        when(support.waiting(any(),anyString(),anyString())).thenAnswer(c->StrategyApplyResponse.builder().status("waiting").message(c.getArgument(2)).build());
        when(support.executeTriggeredTrade(any())).thenReturn(TriggerTradeRequestEntity.builder().id(7L).build());
    }
    @Test void createsBuyAtmCeWithPremiumRiskAndOwnedBroker(){var response=strategy.apply(request,now);assertEquals("triggered",response.getStatus());var t=response.getTriggerRequest();assertEquals("MX",t.getExchange());assertEquals("BUY",t.getAction());assertEquals("CE",t.getOptionType());assertEquals(155d,t.getStrikePrice());assertEquals(5d,t.getEntryPrice());assertEquals(3.75,t.getStopLoss());assertEquals(7.5,t.getTarget1());assertFalse(t.getUseSpotForSl());assertTrue(t.getIntraday());assertEquals(2L,t.getBrokerCredentialsId());}
    @Test void sameTemplateSupportsSharekhanWithoutChangingSelectedBroker(){when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().id(2L).appUserId(1L).brokerName("Sharekhan").build()));assertEquals("triggered",strategy.apply(request,now).getStatus());}
    @Test void noOrderWhenSizingExceedsRupeeLimit(){request.getRiskSettings().setMaxLoss(new BigDecimal("100"));assertEquals("waiting",strategy.apply(request,now).getStatus());verify(support,never()).executeTriggeredTrade(any());}
    @Test void noTradeWithoutExplicitMaxLoss(){request.setRiskSettings(null);assertThrows(IllegalArgumentException.class,()->strategy.apply(request,now));verifyNoInteractions(execution);}
    @Test void deniesUnsupportedOrForeignBroker(){when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(1L).brokerName("Dhan").build()));assertThrows(IllegalArgumentException.class,()->strategy.apply(request,now));when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(9L).brokerName("Sharekhan").build()));assertThrows(IllegalArgumentException.class,()->strategy.apply(request,now));verifyNoInteractions(execution);}
    @Test void previousUnresolvedRequestConsumesDayAcrossStrikes(){when(requests.findNaturalGasDailyRequests(anyString(),eq(1L),eq(2L),any(),any())).thenReturn(List.of(TriggerTradeRequestEntity.builder().status(org.com.sharekhan.enums.TriggeredTradeStatus.ENTRY_SUBMITTING).build()));assertEquals("waiting",strategy.apply(request,now).getStatus());verifyNoInteractions(data,execution);}
    @Test void persistedFillStillConsumesDayAfterRequestCleanup(){when(setups.countNaturalGasDailyEntries(anyString(),eq(1L),eq(2L),any(),any())).thenReturn(1L);assertEquals("waiting",strategy.apply(request,now).getStatus());verifyNoInteractions(data,execution);}
    @Test void staleLtpBlocksEntry(){when(quotes.getSnapshot(1)).thenReturn(Optional.of(futureQuote(now.minusSeconds(16))));assertEquals("waiting",strategy.apply(request,now).getStatus());verify(support,never()).executeTriggeredTrade(any());}
    @Test void staleBookIsNotRefreshedByLtp(){when(quotes.getSnapshot(2)).thenReturn(Optional.of(book(now.atZone(StrategySupport.MARKET_ZONE).toInstant().minusSeconds(16),"SHAREKHAN_WS",4.95,5)));assertEquals("waiting",strategy.apply(request,now).getStatus());verify(support,never()).executeTriggeredTrade(any());}
    @Test void rejectsCrossedWideAndUnprovenanceBooks(){var instant=now.atZone(StrategySupport.MARKET_ZONE).toInstant();assertFalse(NaturalGasAtmStrategy.usableBook(book(instant,"SHAREKHAN_WS",6,5),instant,2));assertFalse(NaturalGasAtmStrategy.usableBook(book(instant,"SHAREKHAN_WS",4,5),instant,2));assertFalse(NaturalGasAtmStrategy.usableBook(book(instant,"UNKNOWN",4.95,5),instant,2));assertFalse(NaturalGasAtmStrategy.usableBook(book(instant.plusSeconds(1),"SHAREKHAN_WS",4.95,5),instant,2));}
    @Test void noFeedsOrOrdersOutsideWindow(){assertEquals("waiting",strategy.apply(request,now.withHour(23)).getStatus());verifyNoInteractions(data,execution);}

    @Test void priorDayOpenPositionBlocksNewDayDespiteDailyCounterReset() {
        when(setups.findBySource(NaturalGasSession.SOURCE)).thenReturn(List.of(TriggeredTradeSetupEntity.builder().appUserId(1L).brokerCredentialsId(2L)
                .entryAt(now.minusDays(1)).status(org.com.sharekhan.enums.TriggeredTradeStatus.EXIT_ORDER_PLACED).build()));
        assertEquals("waiting",strategy.apply(request,now).getStatus());verifyNoInteractions(data,execution);
    }
    @Test void priorUnknownSubmissionBlocksNewDay() {
        when(requests.findBySourceIgnoreCaseAndStatusIn(eq(NaturalGasSession.SOURCE),anyList())).thenReturn(List.of(TriggerTradeRequestEntity.builder().appUserId(1L).brokerCredentialsId(2L)
                .status(org.com.sharekhan.enums.TriggeredTradeStatus.FAILED).reason("ENTRY_SUBMISSION_STATE_UNKNOWN").build()));
        assertEquals("waiting",strategy.apply(request,now).getStatus());verifyNoInteractions(data,execution);
    }
    @Test void flexibleSimulatorCanEvaluateLateCaptureWhileSharekhanStillRequiresOpening() {
        var evening=now.withHour(19).withMinute(0);
        when(data.candles(1,evening)).thenReturn(NaturalGasSignalsTest.lateCandles(true,LocalTime.of(17,0)));
        when(quotes.getSnapshot(1)).thenReturn(Optional.of(futureQuote(evening)));
        when(quotes.getSnapshot(2)).thenReturn(Optional.of(book(evening.atZone(StrategySupport.MARKET_ZONE).toInstant(),"SHAREKHAN_WS",4.95,5)));
        var flexible=new NaturalGasAtmStrategy(scripts,brokers,requests,setups,data,quotes,execution,support,new NaturalGasDataPolicy(NaturalGasDataMode.ROLLING_WINDOW));
        var simulated=flexible.apply(request,evening);assertEquals("triggered",simulated.getStatus());
        assertTrue(simulated.getMessage().contains("rolling VWAP"));assertTrue(simulated.getMessage().contains("17:00/2026-10-26T19:00"));
        verify(support).executeTriggeredTrade(any());clearInvocations(support);
        when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().id(2L).appUserId(1L).brokerName("Sharekhan").build()));
        var live=flexible.apply(request,evening);assertEquals("waiting",live.getStatus());assertTrue(live.getMessage().startsWith("Incomplete MCX session"));
        verify(support,never()).executeTriggeredTrade(any());
    }
    @Test void flexibleSimulatorStillRejectsExcessRiskAndStaleQuotes() {
        var flexible=new NaturalGasAtmStrategy(scripts,brokers,requests,setups,data,quotes,execution,support,new NaturalGasDataPolicy(NaturalGasDataMode.ROLLING_WINDOW));
        request.getRiskSettings().setMaxLoss(new BigDecimal("100"));
        assertTrue(flexible.apply(request,now).getMessage().contains("rupee loss limit"));
        request.getRiskSettings().setMaxLoss(new BigDecimal("2000"));
        when(quotes.getSnapshot(1)).thenReturn(Optional.of(futureQuote(now.minusSeconds(16))));
        assertTrue(flexible.apply(request,now).getMessage().contains("fresh exact-contract"));
        verify(support,never()).executeTriggeredTrade(any());
    }

    @Test void shoonyaCandlesRouteOnlySimulatorAndRetainSharekhanExecutionAndRiskGuards() {
        var p=new NaturalGasDataPolicy(NaturalGasDataMode.ROLLING_WINDOW,NaturalGasCandleSource.SHOONYA_TPS);
        var s=new NaturalGasAtmStrategy(scripts,brokers,requests,setups,data,quotes,execution,support,p);
        when(data.shoonyaCandles(future,now)).thenReturn(NaturalGasSignalsTest.candles(true));
        var response=s.apply(request,now);assertEquals("triggered",response.getStatus());assertTrue(response.getMessage().contains("candleSource=SHOONYA_TPS"));
        assertEquals(2L,response.getTriggerRequest().getBrokerCredentialsId());assertEquals(3.75,response.getTriggerRequest().getStopLoss());
        verify(data).shoonyaCandles(future,now);verify(data,never()).candles(anyInt(),any());
        clearInvocations(data,support);request.getRiskSettings().setMaxLoss(new BigDecimal("100"));
        assertEquals("waiting",s.apply(request,now).getStatus());verify(support,never()).executeTriggeredTrade(any());
        clearInvocations(data);when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().id(2L).appUserId(1L).brokerName("Sharekhan").build()));
        s.apply(request,now);verify(data).candles(1,now);verify(data,never()).shoonyaCandles(any(),any());
    }

}
