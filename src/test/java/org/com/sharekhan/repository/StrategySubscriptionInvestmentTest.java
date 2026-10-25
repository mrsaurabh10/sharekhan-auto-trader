package org.com.sharekhan.repository;

import jakarta.persistence.EntityManager;
import org.com.sharekhan.entity.StrategySubscriptionEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@org.springframework.context.annotation.Import(org.com.sharekhan.service.StrategySubscriptionLifecycleService.class)
class StrategySubscriptionInvestmentTest {
    @Autowired StrategySubscriptionRepository repository;
    @Autowired EntityManager entityManager;
    @Autowired AppUserRepository users;
    @Autowired TriggerTradeRequestRepository requests;
    @Autowired TriggeredTradeSetupRepository trades;
    @Autowired org.com.sharekhan.service.StrategySubscriptionLifecycleService lifecycle;
    @org.springframework.boot.test.mock.mockito.MockBean org.com.sharekhan.onboarding.UserTradingAccess access;
    @Test void pauseAndResumePersistWithoutResettingDailyCompletion() {
        var owner = new org.com.sharekhan.entity.AppUser(); owner.setUsername("pause-owner");
        users.saveAndFlush(owner);
        var completed = java.time.LocalDateTime.of(2026,10,22,9,30);
        var row = repository.saveAndFlush(StrategySubscriptionEntity.builder().templateId("TEST")
                .symbol("NIFTY").status("ACTIVE").appUserId(owner.getId()).brokerCredentialsId(2L)
                .completedAt(completed).generatedTradeRequestId(88L).lots(3).build());
        entityManager.clear();
        lifecycle.pause(row.getId(),owner.getId(),false); entityManager.flush(); entityManager.clear();
        assertThat(repository.findById(row.getId()).orElseThrow().getStatus()).isEqualTo("PAUSED");
        lifecycle.resume(row.getId(),owner.getId(),false); entityManager.flush(); entityManager.clear();
        var resumed = repository.findById(row.getId()).orElseThrow();
        assertThat(resumed.getStatus()).isEqualTo("ACTIVE"); assertThat(resumed.getCompletedAt()).isEqualTo(completed);
        assertThat(resumed.getGeneratedTradeRequestId()).isEqualTo(88L); assertThat(resumed.getLots()).isEqualTo(3);
        org.mockito.Mockito.verify(access).requireReady(owner.getId(),2L);
    }
    @Test void staleEvaluationAndSymbolEditCannotReactivatePausedStrategy() {
        var row = repository.saveAndFlush(StrategySubscriptionEntity.builder().templateId("TEST")
                .symbol("NIFTY").status("ACTIVE").appUserId(1L).build());
        entityManager.detach(row);
        var current = repository.findById(row.getId()).orElseThrow();
        current.setStatus("PAUSED"); repository.saveAndFlush(current); entityManager.clear();
        row.setStatus("ACTIVE"); row.setLastMessage("Stale scheduler result");
        assertThat(repository.recordEvaluationIfActive(row)).isZero();
        repository.updateConfiguredSymbols(row.getId(), "BANKNIFTY", "Updated symbols");
        entityManager.clear();
        assertThat(repository.findById(row.getId()).orElseThrow().getStatus()).isEqualTo("PAUSED");
        assertThat(repository.findByAppUserIdAndStatusInOrderByIdDesc(1L, java.util.List.of("PAUSED"))).hasSize(1);
        assertThat(repository.findByStatusInOrderByIdDesc(java.util.List.of("ACTIVE", "TRIGGERED"))).isEmpty();
    }
    @Test void retainsRupeesAndPaiseAcrossReloadAndAllowsLegacyLotsWithoutBudget() {
        var stock = repository.saveAndFlush(StrategySubscriptionEntity.builder()
                .templateId("STOCK_0925_MOVER_ATR_BREAKOUT").symbol("FNO_UNIVERSE").status("ACTIVE")
                .investmentPerStock(new BigDecimal("12345.67")).build());
        var option = repository.saveAndFlush(StrategySubscriptionEntity.builder()
                .templateId("FNO_0925_MOVER_ATR_BREAKOUT").symbol("FNO_UNIVERSE").status("ACTIVE").lots(3).build());
        entityManager.clear();
        assertThat(repository.findById(stock.getId()).orElseThrow().getInvestmentPerStock()).isEqualByComparingTo("12345.67");
        var reloaded = repository.findById(option.getId()).orElseThrow();
        assertThat(reloaded.getInvestmentPerStock()).isNull(); assertThat(reloaded.getLots()).isEqualTo(3);
    }
    @Test void straddleProfitTargetSurvivesPersistenceWithoutOptingInLegacySubscriptions() {
        var row=repository.saveAndFlush(StrategySubscriptionEntity.builder().templateId("EXPIRY_LONG_STRADDLE")
                .symbol("NIFTY").status("ACTIVE").lots(2).profitTargetPerLot(new BigDecimal("2000.25")).build());
        var legacy=repository.saveAndFlush(StrategySubscriptionEntity.builder().templateId("EXPIRY_LONG_STRADDLE")
                .symbol("BANKNIFTY").status("ACTIVE").lots(1).build());
        entityManager.clear();
        assertThat(repository.findById(row.getId()).orElseThrow().getProfitTargetPerLot()).isEqualByComparingTo("2000.25");
        assertThat(repository.findById(legacy.getId()).orElseThrow().getProfitTargetPerLot()).isNull();
    }

    @Test void naturalGasDailyGuardsAreBrokerScopedAndCleanupRetainsItsAudit() {
        var start=java.time.LocalDate.of(2026,10,26).atStartOfDay();
        var source=org.com.sharekhan.strategy.mcx.NaturalGasSession.SOURCE;
        var row=requests.saveAndFlush(org.com.sharekhan.entity.TriggerTradeRequestEntity.builder().source(source).appUserId(10L)
                .brokerCredentialsId(20L).symbol("NATURALGAS").intraday(true).createdAt(start.plusHours(10))
                .status(org.com.sharekhan.enums.TriggeredTradeStatus.ENTRY_SUBMITTING).build());
        assertThat(requests.findNaturalGasDailyRequests(source,10L,20L,start,start.plusDays(1))).extracting(org.com.sharekhan.entity.TriggerTradeRequestEntity::getId).containsExactly(row.getId());
        assertThat(requests.findNaturalGasDailyRequests(source,10L,21L,start,start.plusDays(1))).isEmpty();
        requests.deleteStaleRequestsCreatedBeforePreservingNonIntradayStatus(start.plusHours(15),org.com.sharekhan.enums.TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
        entityManager.clear();assertThat(requests.findById(row.getId())).isPresent();
        trades.saveAndFlush(org.com.sharekhan.entity.TriggeredTradeSetupEntity.builder().source(source).appUserId(10L).brokerCredentialsId(20L)
                .entryAt(start.plusHours(10)).orderId("SIM-TEST").status(org.com.sharekhan.enums.TriggeredTradeStatus.EXITED_SUCCESS).build());
        assertThat(trades.countNaturalGasDailyEntries(source,10L,20L,start,start.plusDays(1))).isEqualTo(1);
        assertThat(trades.countNaturalGasDailyEntries(source,10L,21L,start,start.plusDays(1))).isZero();
    }
}
