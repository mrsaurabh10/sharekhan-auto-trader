package org.com.sharekhan.repository;

import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class TriggeredTradeSetupRepositoryAnalyticsTest {
    @Autowired
    private TriggeredTradeSetupRepository repository;

    @Test
    void findForAnalyticsFiltersUserSymbolBrokerAndIntraday() {
        TriggeredTradeSetupEntity matchingTrade = trade(1L, "NIFTY", "telegram", 10L, true);
        TriggeredTradeSetupEntity otherUser = trade(2L, "NIFTY", "telegram", 10L, true);
        TriggeredTradeSetupEntity otherSymbol = trade(1L, "RELIANCE", "telegram", 10L, true);
        TriggeredTradeSetupEntity otherSource = trade(1L, "NIFTY", "admin-ui", 10L, true);
        TriggeredTradeSetupEntity otherBroker = trade(1L, "NIFTY", "telegram", 11L, true);
        TriggeredTradeSetupEntity otherIntraday = trade(1L, "NIFTY", "telegram", 10L, false);
        repository.save(matchingTrade);
        repository.save(otherUser);
        repository.save(otherSymbol);
        repository.save(otherSource);
        repository.save(otherBroker);
        repository.save(otherIntraday);

        var result = repository.findForAnalytics(1L, "nif", "tele", 10L, true);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAppUserId()).isEqualTo(1L);
        assertThat(result.get(0).getSymbol()).isEqualTo("NIFTY");
        assertThat(result.get(0).getSource()).isEqualTo("telegram");
        assertThat(result.get(0).getBrokerCredentialsId()).isEqualTo(10L);
        assertThat(result.get(0).getIntraday()).isTrue();
    }

    private TriggeredTradeSetupEntity trade(Long userId, String symbol, String source, Long brokerCredentialsId, Boolean intraday) {
        return TriggeredTradeSetupEntity.builder()
                .symbol(symbol)
                .source(source)
                .appUserId(userId)
                .brokerCredentialsId(brokerCredentialsId)
                .intraday(intraday)
                .status(TriggeredTradeStatus.EXITED_SUCCESS)
                .quantity(50L)
                .entryPrice(100.0)
                .exitPrice(120.0)
                .pnl(1000.0)
                .triggeredAt(LocalDateTime.of(2026, 4, 1, 9, 30))
                .exitedAt(LocalDateTime.of(2026, 4, 1, 10, 30))
                .build();
    }
}
