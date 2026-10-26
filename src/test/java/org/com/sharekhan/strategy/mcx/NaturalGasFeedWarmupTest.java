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
        new NaturalGasFeedWarmup(subs,scripts,live,execution).warm(LocalDateTime.of(2026,10,26,8,55));
        verify(live).watch(42);verify(execution).warmUpSpotLtp(eq(future),anyString());verifyNoMoreInteractions(execution);
    }
}
