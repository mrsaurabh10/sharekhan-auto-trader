package org.com.sharekhan.strategy.mcx;
import org.junit.jupiter.api.Test;
import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.strategy.run.StrategyRiskSettings;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class NaturalGasScalpDayTest {
    final LocalDateTime now=NaturalGasScalpSignalsTest.NOW;
    StrategyApplyRequest request(){var r=new StrategyApplyRequest();r.setUserId(1L);r.setBrokerCredentialsId(2L);r.setLots(1);var risk=new StrategyRiskSettings();risk.setMaxLoss(new BigDecimal("2500"));r.setRiskSettings(risk);return r;}
    TriggeredTradeSetupEntity closed(long id,double exit){return TriggeredTradeSetupEntity.builder().id(id).triggerRequestId(id).appUserId(1L).brokerCredentialsId(2L).actualEntryPrice(5d).exitPrice(exit).quantity(1250L).entryAt(now.minusMinutes(20)).exitedAt(now.minusMinutes(7)).status(TriggeredTradeStatus.EXITED_SUCCESS).build();}
    @Test void persistedLossAndPaperReserveReduceBudgetButProfitsDoNotReplenish(){var d=NaturalGasScalpDay.inspect(request(),List.of(),List.of(closed(1,4),closed(2,6)),now);assertEquals(0,new BigDecimal("1050").compareTo(d.remaining()));assertEquals(2,d.attempts());assertNull(d.problem());}
    @Test void sameRequestAndFillCountAsOneAttempt(){var t=closed(1,5);var r=TriggerTradeRequestEntity.builder().id(1L).appUserId(1L).brokerCredentialsId(2L).createdAt(now.minusMinutes(20)).build();assertEquals(1,NaturalGasScalpDay.inspect(request(),List.of(r),List.of(t),now).attempts());}
    @Test void fiveAttemptsAndCooldownBlock(){var trades=new ArrayList<TriggeredTradeSetupEntity>();for(int i=1;i<=5;i++)trades.add(closed(i,5));assertTrue(NaturalGasScalpDay.inspect(request(),List.of(),trades,now).problem().contains("cap"));var t=closed(6,5);t.setExitedAt(now.minusMinutes(5));assertTrue(NaturalGasScalpDay.inspect(request(),List.of(),List.of(t),now).problem().contains("cooldown"));}
    @Test void missingExitAndForeignOwnerHandled(){var t=closed(1,4);t.setExitedAt(null);assertNotNull(NaturalGasScalpDay.inspect(request(),List.of(),List.of(t),now).problem());t.setAppUserId(99L);assertEquals(0,NaturalGasScalpDay.inspect(request(),List.of(),List.of(t),now).attempts());}
    @Test void explicitNoOrderWaitDoesNotConsumeAttempt(){var r=TriggerTradeRequestEntity.builder().appUserId(1L).brokerCredentialsId(2L).status(TriggeredTradeStatus.REJECTED).reason(org.com.sharekhan.exception.EntryNotReadyException.NO_ORDER_REASON).build();assertEquals(0,NaturalGasScalpDay.inspect(request(),List.of(r),List.of(),now).attempts());}
}
