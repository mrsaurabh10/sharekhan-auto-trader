package org.com.sharekhan.strategy.mcx;
import org.junit.jupiter.api.Test;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.TradeExecutionService;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class NaturalGasScalpRiskMonitorTest {
    final StrategyRunRepository runs=mock(StrategyRunRepository.class);
    final BrokerCredentialsRepository brokers=mock(BrokerCredentialsRepository.class);
    final TriggeredTradeSetupRepository trades=mock(TriggeredTradeSetupRepository.class);
    final TradeExecutionService execution=mock(TradeExecutionService.class);
    final NaturalGasScalpRiskMonitor monitor=new NaturalGasScalpRiskMonitor(new NaturalGasScalpPolicy(),runs,brokers,trades,execution);
    final java.time.LocalDateTime now=NaturalGasScalpSignalsTest.NOW;
    StrategyRun run(String template){return StrategyRun.builder().id("scalp-day").templateId(template).tradingDay(now.toLocalDate()).appUserId(1L).brokerCredentialsId(2L).lots(1).build();}
    void setup(String template,String broker){when(runs.findByStatusIn(anyList())).thenReturn(List.of(run(template)));when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(1L).brokerName(broker).build()));}
    @Test void conservativeLossBudgetRequestsCloseIncludingReserve(){setup(NaturalGasScalpingStrategy.TEMPLATE,"Simulator");var t=TriggeredTradeSetupEntity.builder().appUserId(1L).brokerCredentialsId(2L).entryAt(now.minusMinutes(20)).status(TriggeredTradeStatus.EXITED_SUCCESS).actualEntryPrice(5d).exitPrice(3.05).quantity(1250L).build();when(trades.findBySource(NaturalGasScalpingStrategy.SOURCE)).thenReturn(List.of(t));monitor.monitor(now);verify(runs).requestClose("scalp-day","MCX_SCALP_DAILY_LOSS",now);verifyNoInteractions(execution);}
    @Test void originalStrategyAndRealBrokersAreUnaffected(){setup(NaturalGasSession.TEMPLATE,"Simulator");monitor.monitor(now);verifyNoInteractions(trades,execution);setup(NaturalGasScalpingStrategy.TEMPLATE,"Sharekhan");monitor.monitor(now);verifyNoInteractions(trades,execution);verify(runs,never()).requestClose(anyString(),anyString(),any());}
    @Test void missingFreshExitBookCannotInventLossOrClose(){setup(NaturalGasScalpingStrategy.TEMPLATE,"Simulator");var t=TriggeredTradeSetupEntity.builder().appUserId(1L).brokerCredentialsId(2L).entryAt(now.minusMinutes(20)).status(TriggeredTradeStatus.EXECUTED).actualEntryPrice(5d).quantity(1250L).build();when(trades.findBySource(NaturalGasScalpingStrategy.SOURCE)).thenReturn(List.of(t));when(execution.strategyProfitExitQuote(eq(t),any())).thenReturn(Optional.empty());monitor.monitor(now);verify(runs,never()).requestClose(anyString(),anyString(),any());}
}
