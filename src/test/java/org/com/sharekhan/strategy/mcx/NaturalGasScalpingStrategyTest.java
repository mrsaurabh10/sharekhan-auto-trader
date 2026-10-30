package org.com.sharekhan.strategy.mcx;
import org.junit.jupiter.api.*;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.TradeExecutionService;
import org.com.sharekhan.strategy.*;
import org.com.sharekhan.strategy.run.StrategyRiskSettings;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class NaturalGasScalpingStrategyTest {
    final ScriptMasterRepository scripts=mock(ScriptMasterRepository.class);
    final BrokerCredentialsRepository brokers=mock(BrokerCredentialsRepository.class);
    final TriggerTradeRequestRepository requests=mock(TriggerTradeRequestRepository.class);
    final TriggeredTradeSetupRepository setups=mock(TriggeredTradeSetupRepository.class);
    final NaturalGasMarketData data=mock(NaturalGasMarketData.class);
    final QuoteCacheService quotes=mock(QuoteCacheService.class);
    final TradeExecutionService execution=mock(TradeExecutionService.class);
    final StrategySupport support=mock(StrategySupport.class);
    final NaturalGasScalpingStrategy strategy=new NaturalGasScalpingStrategy(scripts,brokers,requests,setups,data,quotes,execution,support);
    final StrategyApplyRequest request=new NaturalGasScalpDayTest().request();
    final java.time.LocalDateTime now=NaturalGasScalpSignalsTest.NOW;
    @BeforeEach void setup(){
        var future=NaturalGasContractsTest.row(1,"FUTC",null,"24/11/2026",0);future.setTickSize(.1);future.setLotSize(1250);
        var option=NaturalGasContractsTest.row(2,"OPTC","CE","20/11/2026",155);option.setLotSize(1250);option.setTickSize(.05);
        when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(1L).brokerName("Simulator").build()));
        when(scripts.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase("MX","NATURALGAS")).thenReturn(List.of(future,option));
        when(data.threeMinuteCandles(future,now)).thenReturn(NaturalGasScalpSignalsTest.bars(3,40,true));
        when(data.shoonyaCandles(future,now)).thenReturn(NaturalGasScalpSignalsTest.bars(5,60,true));
        var instant=now.atZone(StrategySupport.MARKET_ZONE).toInstant();
        when(quotes.getSnapshot(1)).thenReturn(Optional.of(QuoteCacheService.QuoteSnapshot.builder().scripCode(1).lastTradedPrice(154.1).lastLtpAt(instant).ltpSource("SHAREKHAN_WS").ltpProviderIdentity("scrip:1").build()));
        when(quotes.getSnapshot(2)).thenReturn(Optional.of(QuoteCacheService.QuoteSnapshot.builder().scripCode(2).bestBid(4.95).bestAsk(5d).lastBookAt(instant).coherentBook(true).bookSource("SHAREKHAN_WS").bookProviderIdentity("scrip:2").build()));
        when(support.waiting(any(),anyString(),anyString())).thenAnswer(c->StrategyApplyResponse.builder().status("waiting").message(c.getArgument(2)).build());
    }
    @Test void separateNamedTemplateSubmitsOwnSourceAndFixedPremiumStops(){var r=strategy.apply(request,now);assertEquals("triggered",r.getStatus(),r.getMessage());assertEquals("Scalping",strategy.metadata().name());assertNotEquals(NaturalGasSession.TEMPLATE,strategy.metadata().id());var t=r.getTriggerRequest();assertEquals(NaturalGasScalpingStrategy.SOURCE,t.getSource());assertEquals(3.5,t.getStopLoss());assertEquals(6.5,t.getTarget1());assertTrue(r.getMessage().contains("₹1.50 premium stop/₹1.50 target"));verify(setups,never()).findBySource(NaturalGasSession.SOURCE);}
    @Test void reentryAfterConfirmedExitAndCooldownIsAllowed(){var t=TriggeredTradeSetupEntity.builder().id(1L).appUserId(1L).brokerCredentialsId(2L).status(TriggeredTradeStatus.EXITED_SUCCESS).actualEntryPrice(5d).exitPrice(5.5).quantity(1250L).entryAt(now.minusMinutes(20)).exitedAt(now.minusMinutes(7)).build();when(setups.findBySource(NaturalGasScalpingStrategy.SOURCE)).thenReturn(List.of(t));assertEquals("triggered",strategy.apply(request,now).getStatus());}
    @Test void dailyAllowanceIncludesPriorLossAndCostReserve(){var t=TriggeredTradeSetupEntity.builder().id(1L).appUserId(1L).brokerCredentialsId(2L).status(TriggeredTradeStatus.EXITED_SUCCESS).actualEntryPrice(5d).exitPrice(3.6).quantity(1250L).entryAt(now.minusMinutes(20)).exitedAt(now.minusMinutes(7)).build();when(setups.findBySource(NaturalGasScalpingStrategy.SOURCE)).thenReturn(List.of(t));assertTrue(strategy.apply(request,now).getMessage().contains("remaining daily allowance"));verify(support,never()).executeTriggeredTrade(any());}
    @Test void rejectsLiveBrokerOversizeAndExcessDailyLimit(){when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(1L).brokerName("Sharekhan").build()));assertThrows(IllegalArgumentException.class,()->strategy.apply(request,now));request.setLots(2);assertThrows(IllegalArgumentException.class,()->NaturalGasScalpingStrategy.validate(request));request.setLots(1);request.getRiskSettings().setMaxLoss(new BigDecimal("2501"));assertThrows(IllegalArgumentException.class,()->NaturalGasScalpingStrategy.validate(request));verifyNoInteractions(execution);}
    @Test void mcxClockAndSourceRecognizeBothStrategies(){assertTrue(NaturalGasSession.isTemplate(strategy.metadata().id()));assertTrue(NaturalGasSession.owns(NaturalGasScalpingStrategy.SOURCE));assertTrue(NaturalGasSession.owns(NaturalGasSession.SOURCE));assertFalse(NaturalGasSession.owns("strategy:OTHER"));assertEquals("waiting",strategy.apply(request,now.withHour(23)).getStatus());verifyNoInteractions(data,execution);}

    @Test void ceAndPeUseTheSameRupeeOffsetsAtDifferentPremiumLevels() {
        for(boolean bull:List.of(true,false)) for(double ask:List.of(5d,17d,25d,40d)) {
            market(bull,ask-.05,ask);
            var result=strategy.apply(request,now);
            assertEquals("triggered",result.getStatus(),result.getMessage());
            var trade=result.getTriggerRequest();
            assertEquals(bull?"CE":"PE",trade.getOptionType());
            assertEquals(ask-1.50,trade.getStopLoss(),1e-9);
            assertEquals(ask+1.50,trade.getTarget1(),1e-9);
            assertFalse(trade.getUseSpotForSl());assertFalse(trade.getUseSpotForTarget());
            assertFalse(trade.getTslEnabled());
        }
    }
    @Test void tickRoundedAskUsesDecimalOffsetsWithoutAnExtraStopTick() {
        market(true,17d,17.03);
        var trade=strategy.apply(request,now).getTriggerRequest();
        assertNotNull(trade);assertEquals(17.05,trade.getEntryPrice());
        assertEquals(15.55,trade.getStopLoss());assertEquals(18.55,trade.getTarget1());
    }
    @Test void rejectsPremiumThatCannotSupportPositiveFixedStop() {
        market(true,1.49,1.50);
        assertTrue(strategy.apply(request,now).getMessage().contains("premium too small"));
        verify(support,never()).executeTriggeredTrade(any());
    }
    @Test void fixedRiskAdmissionIncludesPaperReserveAtExactBudgetBoundary() {
        market(true,17d,17.05);
        request.getRiskSettings().setMaxLoss(new BigDecimal("1974.99"));
        assertTrue(strategy.apply(request,now).getMessage().contains("remaining daily allowance"));
        verify(support,never()).executeTriggeredTrade(any());
        request.getRiskSettings().setMaxLoss(new BigDecimal("1975.00"));
        assertEquals("triggered",strategy.apply(request,now).getStatus());
    }
    @Test void fullFixedStopConsumesTooMuchAllowanceForAnotherEntry() {
        var closed=TriggeredTradeSetupEntity.builder().id(1L).appUserId(1L).brokerCredentialsId(2L)
                .status(TriggeredTradeStatus.EXITED_SUCCESS).actualEntryPrice(17d).exitPrice(15.5)
                .quantity(1250L).entryAt(now.minusMinutes(20)).exitedAt(now.minusMinutes(7)).build();
        when(setups.findBySource(NaturalGasScalpingStrategy.SOURCE)).thenReturn(List.of(closed));
        assertTrue(strategy.apply(request,now).getMessage().contains("remaining daily allowance"));
        verify(support,never()).executeTriggeredTrade(any());
    }
    @Test void fixedTargetStillMustCoverDisplayedSpreadAndCostReserve() {
        market(true,39.25,40);
        assertTrue(strategy.apply(request,now).getMessage().contains("target too small"));
        verify(support,never()).executeTriggeredTrade(any());
    }
    @Test void existingOpenTradeKeepsItsSavedExitLevelsAndBlocksNewEntry() {
        var open=TriggeredTradeSetupEntity.builder().id(1L).appUserId(1L).brokerCredentialsId(2L)
                .status(TriggeredTradeStatus.EXECUTED).actualEntryPrice(17d).stopLoss(15.3).target1(19.55).build();
        when(setups.findBySource(NaturalGasScalpingStrategy.SOURCE)).thenReturn(List.of(open));
        assertTrue(strategy.apply(request,now).getMessage().contains("existing Natural Gas position"));
        assertEquals(15.3,open.getStopLoss());assertEquals(19.55,open.getTarget1());
        verify(setups,never()).save(any());verify(support,never()).executeTriggeredTrade(any());
    }
    private void market(boolean bull,double bid,double ask) {
        var future=NaturalGasContractsTest.row(1,"FUTC",null,"24/11/2026",0);future.setTickSize(.1);future.setLotSize(1250);
        var option=NaturalGasContractsTest.row(2,"OPTC",bull?"CE":"PE","20/11/2026",bull?155:165);option.setLotSize(1250);option.setTickSize(.05);
        when(scripts.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase("MX","NATURALGAS")).thenReturn(List.of(future,option));
        var entry=NaturalGasScalpSignalsTest.bars(3,40,bull);var trend=NaturalGasScalpSignalsTest.bars(5,60,bull);
        when(data.threeMinuteCandles(future,now)).thenReturn(entry);when(data.shoonyaCandles(future,now)).thenReturn(trend);
        var signal=NaturalGasScalpSignals.inspect(entry,trend,now,.1).signal().orElseThrow();
        var instant=now.atZone(StrategySupport.MARKET_ZONE).toInstant();
        when(quotes.getSnapshot(1)).thenReturn(Optional.of(QuoteCacheService.QuoteSnapshot.builder().scripCode(1)
                .lastTradedPrice(signal.trigger()).lastLtpAt(instant).ltpSource("SHAREKHAN_WS").ltpProviderIdentity("scrip:1").build()));
        when(quotes.getSnapshot(2)).thenReturn(Optional.of(QuoteCacheService.QuoteSnapshot.builder().scripCode(2)
                .bestBid(bid).bestAsk(ask).lastBookAt(instant).coherentBook(true).bookSource("SHAREKHAN_WS").bookProviderIdentity("scrip:2").build()));
    }
}
