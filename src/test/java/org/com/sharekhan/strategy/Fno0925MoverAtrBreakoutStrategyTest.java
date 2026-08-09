package org.com.sharekhan.strategy;

import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.service.MStockGainerLoserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Fno0925MoverAtrBreakoutStrategyTest {

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
