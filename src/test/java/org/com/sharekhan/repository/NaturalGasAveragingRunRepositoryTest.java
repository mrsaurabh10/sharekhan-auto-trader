package org.com.sharekhan.repository;
import org.com.sharekhan.entity.StrategyRun;
import org.com.sharekhan.strategy.mcx.NaturalGasAveragingStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
@DataJpaTest
class NaturalGasAveragingRunRepositoryTest {
    @Autowired StrategyRunRepository runs;
    @Test void basketQueriesAreScopedAndMessagesCannotOverwriteCloseState() {
        var day=LocalDate.of(2026,10,28);
        for(int i=1;i<=3;i++)runs.saveAndFlush(StrategyRun.builder().id("basket"+i).appUserId(i==3?9L:1L).brokerCredentialsId(2L)
                .templateId(NaturalGasAveragingStrategy.TEMPLATE).symbol("NATURALGAS").tradingDay(day).instance("BASKET"+i)
                .status("OPEN").createdAt(day.atTime(10,i)).build());
        assertThat(runs.findByAppUserIdAndBrokerCredentialsIdAndTemplateIdAndTradingDayOrderByCreatedAtAsc(1L,2L,NaturalGasAveragingStrategy.TEMPLATE,day))
                .extracting(StrategyRun::getId).containsExactly("basket1","basket2");
        assertThat(runs.recordMessage("basket1","risk mark")).isEqualTo(1);
        assertThat(runs.recordMessage("basket1","risk mark")).isZero();
        assertThat(runs.requestClose("basket1","TEST",day.atTime(11,0))).isEqualTo(1);
        assertThat(runs.recordMessage("basket1","stale update")).isZero();
        assertThat(runs.findByTemplateIdAndStatusIn(NaturalGasAveragingStrategy.TEMPLATE,List.of("OPEN"))).hasSize(2);
        assertThat(runs.findById("basket1").orElseThrow().getExitReason()).isEqualTo("TEST");
    }
}
