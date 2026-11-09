package org.com.sharekhan.strategy;

import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupertrendSimulatorStrategyTest {
    @ParameterizedTest
    @CsvSource({"NIFTY,CE,03/11/2026", "NIFTY,PE,03/11/2026", "BANKNIFTY,CE,23/11/2026", "BANKNIFTY,PE,23/11/2026"})
    void qualifiedSimulatorEntryUsesRolloverAndOriginalSpotBracket(String symbol,String side,String expected) {
        var f=new Fixture(symbol,side,false,true);
        assertThat(f.template.apply(f.request,f.now).getStatus()).isEqualTo("triggered");
        var order=ArgumentCaptor.forClass(TriggerRequest.class);verify(f.support).executeTriggeredTrade(order.capture());
        assertThat(order.getValue().getExpiry()).isEqualTo(expected);
        assertThat(order.getValue().getStopLoss()).isEqualTo(side.equals("CE")?190:210);
        assertThat(order.getValue().getTarget1()).isEqualTo(side.equals("CE")?210:190);
        assertThat(order.getValue().getLots()).isEqualTo(3);
        assertThat(order.getValue().getUseSpotForSl()).isTrue();
        verify(f.support,never()).nearestExpiry(anyString(),anyString());
    }
    @ParameterizedTest
    @CsvSource({"CE", "PE"})
    void wideSimulatorSignalCannotResolveContractOrPlaceOrder(String side) {
        var f=new Fixture("NIFTY",side,true,true);
        var result=f.template.apply(f.request,f.now);
        assertThat(result.getStatus()).isEqualTo("waiting");assertThat(result.getMessage()).contains("1.5 x five-minute ATR(14)");
        verify(f.support,never()).nearestStrike(anyString(),anyString(),anyString(),anyDouble());
        verify(f.support,never()).executeTriggeredTrade(any());
        var decision=ArgumentCaptor.forClass(SupertrendSignalRules.Result.class);
        verify(f.diagnostics).record(eq(f.request),any(),eq("NIFTY"),any(),decision.capture());
        assertThat(decision.getValue().failures()).extracting(SupertrendSignalRules.Failure::rule).contains("ATR_STOP_WIDTH");
    }
    @ParameterizedTest
    @CsvSource({"CE", "PE"})
    void liveAccountsKeepOriginalExpiryAndAreNotSubjectToSimulatorVeto(String side) {
        var f=new Fixture("NIFTY",side,true,false);
        when(f.support.nearestExpiry("NIFTY",side)).thenReturn("27/10/2026");
        assertThat(f.template.apply(f.request,f.now).getStatus()).isEqualTo("triggered");
        var order=ArgumentCaptor.forClass(TriggerRequest.class);verify(f.support).executeTriggeredTrade(order.capture());
        assertThat(order.getValue().getExpiry()).isEqualTo("27/10/2026");
    }
    static class Fixture {
        final StrategySupport support=mock(StrategySupport.class);
        final SupertrendDecisionDiagnostics diagnostics=mock(SupertrendDecisionDiagnostics.class);
        final LocalDateTime now=LocalDateTime.of(2026,10,27,9,30);
        final AbstractSupertrendRsiEmaAdxStrategy template;
        final StrategyApplyRequest request=new StrategyApplyRequest();
        Fixture(String symbol,String side,boolean wide,boolean simulator) {
            var brokers=mock(BrokerCredentialsRepository.class);var scripts=mock(ScriptMasterRepository.class);
            when(brokers.findById(4L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().id(4L).appUserId(3L).brokerName(simulator?"Simulator":"Dhan").build()));
            when(scripts.findAllOptionExpiriesByTradingSymbolAndOptionType(symbol,side)).thenReturn(List.of("27/10/2026","03/11/2026","23/11/2026"));
            var policy=new SupertrendSimulatorPolicy(brokers,scripts,true);var indicators=mock(IndicatorService.class);
            var rules=new SupertrendSignalRules(new org.com.sharekhan.config.SupertrendStrategyProperties());
            template=side.equals("CE")?new SupertrendRsiEmaAdxCeStrategy(support,indicators,rules,diagnostics,policy)
                    :new SupertrendRsiEmaAdxPeStrategy(support,indicators,rules,diagnostics,policy);
            request.setTemplateId(template.metadata().id());request.setSymbol(symbol);request.setUserId(3L);request.setBrokerCredentialsId(4L);request.setLots(3);
            var bars=new ArrayList<StrategyCandle>();
            for(int i=0;i<49;i++)bars.add(new StrategyCandle(now.toLocalDate().minusDays(1),LocalTime.of(9,15).plusMinutes(i*5L),200,205,195,200,1000L));
            boolean pe=side.equals("PE");
            var signal=new StrategyCandle(now.toLocalDate(),LocalTime.of(9,25),pe?205:195,pe?(wide?230:210):205,pe?195:(wide?170:190),200,1000L);
            bars.add(signal);
            when(support.resolveSpotScript(symbol)).thenReturn(ScriptMasterEntity.builder().scripCode(20000).tradingSymbol(symbol).build());
            when(support.loadCompletedIndicatorCandles(any(),anyInt(),any())).thenReturn(new CandleLoad(bars,true,null));
            when(indicators.minimumCandles()).thenReturn(50);
            when(indicators.computeSnapshot(anyList())).thenReturn(new IndicatorSnapshot(signal,pe?210:190,pe?40:60,pe?41:59,pe?205:195,25,pe?15:30,pe?30:15));
            when(support.roundPrice(anyDouble())).thenAnswer(c->c.getArgument(0));
        }
    }
}
