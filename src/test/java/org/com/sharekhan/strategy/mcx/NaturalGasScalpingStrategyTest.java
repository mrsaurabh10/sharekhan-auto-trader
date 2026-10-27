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
    @Test void separateNamedTemplateSubmitsOwnSourceAndSmallPremiumStops(){var r=strategy.apply(request,now);assertEquals("triggered",r.getStatus(),r.getMessage());assertEquals("Scalping",strategy.metadata().name());assertNotEquals(NaturalGasSession.TEMPLATE,strategy.metadata().id());var t=r.getTriggerRequest();assertEquals(NaturalGasScalpingStrategy.SOURCE,t.getSource());assertEquals(4.5,t.getStopLoss());assertEquals(5.75,t.getTarget1());verify(setups,never()).findBySource(NaturalGasSession.SOURCE);}
    @Test void reentryAfterConfirmedExitAndCooldownIsAllowed(){var t=TriggeredTradeSetupEntity.builder().id(1L).appUserId(1L).brokerCredentialsId(2L).status(TriggeredTradeStatus.EXITED_SUCCESS).actualEntryPrice(5d).exitPrice(4.5).quantity(1250L).entryAt(now.minusMinutes(20)).exitedAt(now.minusMinutes(7)).build();when(setups.findBySource(NaturalGasScalpingStrategy.SOURCE)).thenReturn(List.of(t));assertEquals("triggered",strategy.apply(request,now).getStatus());}
    @Test void dailyAllowanceIncludesPriorLossAndCostReserve(){var t=TriggeredTradeSetupEntity.builder().id(1L).appUserId(1L).brokerCredentialsId(2L).status(TriggeredTradeStatus.EXITED_SUCCESS).actualEntryPrice(5d).exitPrice(3.6).quantity(1250L).entryAt(now.minusMinutes(20)).exitedAt(now.minusMinutes(7)).build();when(setups.findBySource(NaturalGasScalpingStrategy.SOURCE)).thenReturn(List.of(t));assertTrue(strategy.apply(request,now).getMessage().contains("remaining daily allowance"));verify(support,never()).executeTriggeredTrade(any());}
    @Test void rejectsLiveBrokerOversizeAndExcessDailyLimit(){when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(1L).brokerName("Sharekhan").build()));assertThrows(IllegalArgumentException.class,()->strategy.apply(request,now));request.setLots(2);assertThrows(IllegalArgumentException.class,()->NaturalGasScalpingStrategy.validate(request));request.setLots(1);request.getRiskSettings().setMaxLoss(new BigDecimal("2501"));assertThrows(IllegalArgumentException.class,()->NaturalGasScalpingStrategy.validate(request));verifyNoInteractions(execution);}
    @Test void mcxClockAndSourceRecognizeBothStrategies(){assertTrue(NaturalGasSession.isTemplate(strategy.metadata().id()));assertTrue(NaturalGasSession.owns(NaturalGasScalpingStrategy.SOURCE));assertTrue(NaturalGasSession.owns(NaturalGasSession.SOURCE));assertFalse(NaturalGasSession.owns("strategy:OTHER"));assertEquals("waiting",strategy.apply(request,now.withHour(23)).getStatus());verifyNoInteractions(data,execution);}
}
