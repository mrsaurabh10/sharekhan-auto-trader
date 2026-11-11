package org.com.sharekhan.strategy.adxdmi;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.mockito.Mockito.*;
class AdxDmiSchedulerTest {
    @Test void indexTimeoutContinuesAfterNoonAndWithoutSpotAndUsesNormalSquareOff() {
        var subscriptions=mock(StrategySubscriptionRepository.class);var trades=mock(TriggeredTradeSetupRepository.class);
        var service=mock(StrategySubscriptionService.class);var paper=mock(AdxDmiPaperBroker.class);var engine=mock(TradeExecutionService.class);
        var policy=mock(AdxDmiIndexExitPolicy.class);var quotes=mock(AdxDmiMarketData.class);
        var open=TriggeredTradeSetupEntity.builder().id(8L).strategyRunId("run").source(AdxDmiRules.PREMIUM_SOURCE)
                .comment("ADX_EXIT="+AdxDmiIndexExitRules.VERSION+";").spotScripCode(20000).exchange("NF").status(TriggeredTradeStatus.EXECUTED).build();
        when(trades.findBySourceAndStatusIn(eq(AdxDmiRules.PREMIUM_SOURCE),anyList())).thenAnswer(i->((List<?>)i.getArgument(1)).contains(TriggeredTradeStatus.EXECUTED)?List.of(open):List.of());
        when(quotes.spot(20000,"NC")).thenThrow(new IllegalStateException("Provider unavailable"));
        var now=LocalDateTime.of(2026,11,11,12,15);
        when(policy.observe(8L,null,now)).thenReturn(AdxDmiIndexExitRules.TIMEOUT);
        new AdxDmiScheduler(subscriptions,trades,service,paper,engine,policy,quotes).poll(now);
        verify(engine).squareOffStrategyRunTrade(8L,"run",AdxDmiIndexExitRules.TIMEOUT);
        verifyNoInteractions(service,paper);
    }
    @Test void squareOffAt1520UsesStandardEngineAndReusesExistingExit(){
        var subscriptions=mock(StrategySubscriptionRepository.class);var trades=mock(TriggeredTradeSetupRepository.class);
        var service=mock(StrategySubscriptionService.class);var paper=mock(AdxDmiPaperBroker.class);var engine=mock(TradeExecutionService.class);
        var open=TriggeredTradeSetupEntity.builder().id(1L).strategyRunId("run").status(TriggeredTradeStatus.EXECUTED).build();
        var target=TriggeredTradeSetupEntity.builder().id(2L).strategyRunId("run").status(TriggeredTradeStatus.TARGET_ORDER_PLACED).build();
        when(trades.findBySourceAndStatusIn(eq(AdxDmiRules.PREMIUM_SOURCE),anyList())).thenAnswer(i->((List<?>)i.getArgument(1)).contains(TriggeredTradeStatus.EXECUTED)?List.of(open,target):List.of());
        var scheduler=new AdxDmiScheduler(subscriptions,trades,service,paper,engine,mock(AdxDmiIndexExitPolicy.class),mock(AdxDmiMarketData.class));
        scheduler.poll(LocalDateTime.of(2026,11,11,15,19));verifyNoInteractions(engine);
        scheduler.poll(LocalDateTime.of(2026,11,11,15,20));
        verify(engine).squareOffStrategyRunTrade(1L,"run","INTRADAY_CLOSE");verify(engine).startIntradayExitChase(target);verifyNoInteractions(service,paper);
    }
}
