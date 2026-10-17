package org.com.sharekhan.repository;

import jakarta.persistence.EntityManager;
import org.com.sharekhan.entity.StrategySubscriptionEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class StrategySubscriptionInvestmentTest {
    @Autowired StrategySubscriptionRepository repository;
    @Autowired EntityManager entityManager;
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
}
