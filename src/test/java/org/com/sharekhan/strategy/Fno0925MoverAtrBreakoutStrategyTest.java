package org.com.sharekhan.strategy;

import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.service.MStockGainerLoserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class Fno0925MoverAtrBreakoutStrategyTest {

    @Test
    void directionRejectionDoesNotSubmitOrConsumeTheCandidatesDailyEntry() {
        StrategySupport support = mock(StrategySupport.class);
        ScriptMasterRepository scripts = mock(ScriptMasterRepository.class);
        MStockGainerLoserService movers = mock(MStockGainerLoserService.class);
        Fno925EntryQualificationService qualification = mock(Fno925EntryQualificationService.class);
        LocalDateTime now = LocalDateTime.of(2026, 10, 19, 10, 0);
        Clock clock = Clock.fixed(now.atZone(StrategySupport.MARKET_ZONE).toInstant(), StrategySupport.MARKET_ZONE);
        Fno0925MoverAtrBreakoutStrategy strategy = new Fno0925MoverAtrBreakoutStrategy(support, scripts, movers, qualification, clock);
        ScriptMasterEntity spot = ScriptMasterEntity.builder().tradingSymbol("TCS").scripCode(11536).exchange("NC").build();
        when(scripts.findDistinctOptionUnderlyingSymbols()).thenReturn(List.of("TCS"));
        when(movers.topGainers()).thenReturn(List.of(new MStockGainerLoserService.Mover("TCS", 107d, 5d)));
        when(movers.topLosers()).thenReturn(List.of());
        when(support.resolveSpotScript("TCS")).thenReturn(spot);
        when(support.waiting(any(), any(), anyString())).thenAnswer(call -> StrategyApplyResponse.builder()
                .status("waiting").message(call.getArgument(2)).build());
        when(support.roundPrice(anyDouble())).thenAnswer(call -> call.getArgument(0));
        when(support.preferredFnoExpiry("TCS", "CE")).thenReturn("27/10/2026");
        when(support.resolveFnoEntryContract(any(), any(), any(), any(), anyDouble()))
                .thenReturn(new StrategySupport.FnoOptionContract("27/10/2026", 100d));
        when(support.executeTriggeredTrade(any())).thenReturn(TriggerTradeRequestEntity.builder().id(1L).build());
        String reason = "entry requires a bullish completed five-minute signal candle (close > open)";
        var candle = new StrategyCandle(now.toLocalDate(), now.toLocalTime().minusMinutes(5),
                103d, 108d, 102d, 107d, 250L);
        var signal = new Fno925EntryQualificationService.Signal(107d, 100d, 4d, 106d, 95d, candle, "MORNING_ORB");
        when(qualification.qualifyWithDirectionalCandle(any(), eq(now)))
                .thenReturn(Fno925EntryQualificationService.Qualification.waiting(reason))
                .thenReturn(Fno925EntryQualificationService.Qualification.qualified(signal));
        StrategyApplyRequest request = new StrategyApplyRequest();
        request.setUserId(1L);
        request.setBrokerCredentialsId(2L);
        request.setLots(1);

        assertThat(strategy.apply(request).getMessage()).contains(reason);
        verify(support, never()).executeTriggeredTrade(any());
        verify(support).auditStrategy(eq(request), any(), eq("TCS"), eq("STRATEGY_EVALUATION"),
                eq("REJECTED"), eq(reason), isNull(), isNull());

        assertThat(strategy.apply(request).getStatus()).isEqualTo("triggered");
        verify(qualification, times(2)).qualifyWithDirectionalCandle(any(), eq(now));
        verify(qualification, never()).qualify(any(), any());
        verify(support, times(1)).executeTriggeredTrade(any());
    }

    @Test
    void usesAtrTargetsAndTheFartherOfAtrOrStructuralStopForPe() {
        StrategySupport support = mock(StrategySupport.class);
        when(support.roundPrice(anyDouble())).thenAnswer(invocation -> invocation.getArgument(0));
        when(support.preferredFnoExpiry("ADANIPORTS", "PE")).thenReturn("25/08/2026");
        when(support.nearestStrike("ADANIPORTS", "PE", "25/08/2026", 1690d)).thenReturn(1680d);
        when(support.resolveFnoEntryContract(any(), any(), any(), any(), anyDouble()))
                .thenReturn(new StrategySupport.FnoOptionContract("25/08/2026", 1680d));

        Fno0925MoverAtrBreakoutStrategy strategy = new Fno0925MoverAtrBreakoutStrategy(
                support, mock(ScriptMasterRepository.class), mock(MStockGainerLoserService.class),
                mock(Fno925EntryQualificationService.class));
        Fno925Candidate candidate = new Fno925Candidate("ADANIPORTS", ScriptMasterEntity.builder().build(), "PE");
        Fno925EntryQualificationService.Signal signal = new Fno925EntryQualificationService.Signal(
                1690d, 1691.25d, 4d, 0d, 0d, null, "MORNING_ORB");
        StrategyApplyRequest request = new StrategyApplyRequest();

        TriggerRequest trigger = ReflectionTestUtils.invokeMethod(strategy, "buildTrigger", request, candidate, signal);

        assertThat(trigger.getStopLoss()).isEqualTo(1696d); // 1.5 ATR is farther than the swing stop.
        assertThat(trigger.getTarget1()).isEqualTo(1682d);
        assertThat(trigger.getTarget2()).isEqualTo(1678d);
        assertThat(trigger.getTarget3()).isEqualTo(1674d);
    }
}
