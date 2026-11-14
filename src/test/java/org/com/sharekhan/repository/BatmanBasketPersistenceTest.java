package org.com.sharekhan.repository;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.strategy.batman.*;
import org.com.sharekhan.strategy.run.StrategyRiskSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.math.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@DataJpaTest
class BatmanBasketPersistenceTest{
 @Autowired StrategyRunRepository runs;@Autowired BatmanBasketRepository baskets;@Autowired EntityManager em;
 @Test void exactActualCreditArmsRunAtomicallyAndCannotChangeClosedRuns(){
  var run=runs.saveAndFlush(StrategyRun.builder().id("b-run").appUserId(1L).brokerCredentialsId(2L).templateId(BatmanRules.TEMPLATE).symbol("NIFTY").tradingDay(LocalDate.now()).instance("DAY").status("OPEN").createdAt(LocalDateTime.now()).build());
  var b=baskets.saveAndFlush(BatmanBasket.builder().id("b").appUserId(1L).brokerCredentialsId(2L).symbol("NIFTY").strategyRunId(run.getId()).expiry(LocalDate.now().plusDays(43)).claimedAt(LocalDateTime.now()).baseLots(1).lotSize(65).planJson("[]").state("ENTERING").build());
  assertThat(baskets.arm("b",new BigDecimal("5200.25"))).isEqualTo(1);
  assertThat(runs.armBatmanRisk("b-run",new BigDecimal("5200.25"),StrategyRiskSettings.AmountMode.TOTAL)).isEqualTo(1);em.clear();
  var saved=runs.findById("b-run").orElseThrow();assertThat(saved.getRiskSettings().getProfitTarget()).isEqualByComparingTo("5200.25");assertThat(saved.getRiskSettings().getMaxLoss()).isEqualByComparingTo("5200.25");assertThat(saved.getRiskSettings().getAmountMode()).isEqualTo(StrategyRiskSettings.AmountMode.TOTAL);
  assertThat(baskets.findByStrategyRunId("b-run").orElseThrow().getState()).isEqualTo("ARMED");assertThat(baskets.arm("b",BigDecimal.TEN)).isZero();
  runs.requestClose("b-run","TEST",LocalDateTime.now());assertThat(runs.armBatmanRisk("b-run",BigDecimal.TEN,StrategyRiskSettings.AmountMode.TOTAL)).isZero();
 }
 @Test void pollingSkipsOpenAndSameDayBasketsButAllowsOlderConfirmedClosure(){
  var day=LocalDate.of(2026,11,16);var start=day.atStartOfDay();
  var run=runs.saveAndFlush(StrategyRun.builder().id("poll-run").appUserId(1L).brokerCredentialsId(2L).templateId(BatmanRules.TEMPLATE).symbol("NIFTY").tradingDay(day.minusDays(3)).instance("DAY").status("CLOSED").createdAt(start.minusDays(3)).closedAt(start.minusDays(3).plusHours(14)).build());
  var b=baskets.saveAndFlush(BatmanBasket.builder().id("poll-basket").appUserId(1L).brokerCredentialsId(2L).symbol("NIFTY").strategyRunId(run.getId()).expiry(day.plusDays(43)).claimedAt(start.minusDays(3).plusHours(9)).baseLots(1).lotSize(65).planJson("[]").state("CLOSED").build());
  assertThat(baskets.hasDailyEntryBlocker(1L,2L,"NIFTY",start)).isFalse();
  run.setClosedAt(start);runs.saveAndFlush(run);assertThat(baskets.hasDailyEntryBlocker(1L,2L,"NIFTY",start)).isTrue();
  run.setClosedAt(null);run.setStatus("OPEN");runs.saveAndFlush(run);assertThat(baskets.hasDailyEntryBlocker(1L,2L,"NIFTY",start)).isTrue();
  run.setStatus("CLOSED");run.setClosedAt(start.minusDays(1));runs.saveAndFlush(run);b.setClaimedAt(start);baskets.saveAndFlush(b);assertThat(baskets.hasDailyEntryBlocker(1L,2L,"NIFTY",start)).isTrue();
  assertThat(baskets.hasDailyEntryBlocker(1L,9L,"NIFTY",start)).isFalse();
 }
}
