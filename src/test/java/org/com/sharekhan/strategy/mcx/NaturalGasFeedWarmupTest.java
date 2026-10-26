package org.com.sharekhan.strategy.mcx;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.TradeExecutionService;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
class NaturalGasFeedWarmupTest {
    @Test void preopenRetainsExactResolvedFutureWithoutApplyingStrategyOrOrdering(){
        var subs=mock(StrategySubscriptionRepository.class);var scripts=mock(ScriptMasterRepository.class);
        var live=mock(NaturalGasLiveCandles.class);var execution=mock(TradeExecutionService.class);
        when(subs.findByStatusInOrderByIdDesc(anyList())).thenReturn(List.of(StrategySubscriptionEntity.builder().templateId(NaturalGasSession.TEMPLATE).build()));
        var future=ScriptMasterEntity.builder().scripCode(42).exchange("MX").tradingSymbol("NATURALGAS").instrumentType("FS").expiry("24/11/2026").build();
        var option=ScriptMasterEntity.builder().exchange("MX").tradingSymbol("NATURALGAS").instrumentType("OS").optionType("CE").expiry("20/11/2026").build();
        when(scripts.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase("MX","NATURALGAS")).thenReturn(List.of(future,option));
        new NaturalGasFeedWarmup(subs,scripts,live,execution,mock(BrokerCredentialsRepository.class),new NaturalGasDataPolicy(NaturalGasDataMode.FULL_SESSION),mock(NaturalGasShoonyaCandles.class)).warm(LocalDateTime.of(2026,10,26,8,55));
        verify(live).watch(42);verify(execution).warmUpSpotLtp(eq(future),anyString());verifyNoMoreInteractions(execution);
    }
    @Test void prefetchesOnlyForActiveSimulatorAndNeverSubmitsOrders() {
        var subs=mock(StrategySubscriptionRepository.class);var scripts=mock(ScriptMasterRepository.class);
        var live=mock(NaturalGasLiveCandles.class);var execution=mock(TradeExecutionService.class);var brokers=mock(BrokerCredentialsRepository.class);var source=mock(NaturalGasShoonyaCandles.class);
        when(subs.findByStatusInOrderByIdDesc(anyList())).thenReturn(List.of(StrategySubscriptionEntity.builder().templateId(NaturalGasSession.TEMPLATE).brokerCredentialsId(4L).build()));
        var future=ScriptMasterEntity.builder().scripCode(42).exchange("MX").tradingSymbol("NATURALGAS").instrumentType("FS").expiry("24/11/2026").build();
        var option=ScriptMasterEntity.builder().exchange("MX").tradingSymbol("NATURALGAS").optionType("CE").expiry("20/11/2026").build();
        when(scripts.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase("MX","NATURALGAS")).thenReturn(List.of(future,option));
        when(brokers.findById(4L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().brokerName("Simulator").build()));
        var warmup=new NaturalGasFeedWarmup(subs,scripts,live,execution,brokers,new NaturalGasDataPolicy(NaturalGasDataMode.ROLLING_WINDOW,NaturalGasCandleSource.SHOONYA_TPS),source);
        var now=LocalDateTime.of(2026,10,26,19,0);warmup.warm(now);verify(source).completed(future,now);
        clearInvocations(source);when(brokers.findById(4L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().brokerName("Sharekhan").build()));
        warmup.warm(now.plusMinutes(1));verifyNoInteractions(source);
        verify(execution,times(2)).warmUpSpotLtp(eq(future),anyString());verifyNoMoreInteractions(execution);
    }

}
