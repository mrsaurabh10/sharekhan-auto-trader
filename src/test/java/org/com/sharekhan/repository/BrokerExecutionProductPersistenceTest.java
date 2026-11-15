package org.com.sharekhan.repository;

import jakarta.persistence.EntityManager;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class BrokerExecutionProductPersistenceTest {
    @Autowired TriggeredTradeSetupRepository trades;
    @Autowired EntityManager entityManager;

    @Autowired TriggerTradeRequestRepository requests;
    @Test void fnoSignalDeadlinePersistsAcrossReloadAndCannotBeExtendedByCommentOrEntityUpdates() {
        var candle=java.time.LocalDateTime.of(2026,11,13,9,55);
        var metadata=org.com.sharekhan.service.FnoMoverEntrySignal.decision(candle);
        var request=requests.saveAndFlush(org.com.sharekhan.entity.TriggerTradeRequestEntity.builder()
                .source(org.com.sharekhan.service.FnoMoverEntryRiskPolicy.SOURCE).strategyDecision(metadata).build());
        var trade=trades.saveAndFlush(TriggeredTradeSetupEntity.builder()
                .source(org.com.sharekhan.service.FnoMoverEntryRiskPolicy.SOURCE).strategyDecision(metadata).build());
        entityManager.clear();
        var storedRequest=requests.findById(request.getId()).orElseThrow();
        var storedTrade=trades.findById(trade.getId()).orElseThrow();
        assertThat(storedRequest.getStrategyDecision()).isEqualTo(metadata);
        assertThat(org.com.sharekhan.service.FnoMoverEntrySignal.valid(storedTrade.getStrategyDecision(),candle.plusMinutes(10))).isFalse();
        storedRequest.setComment("Repriced after restart");storedRequest.setStrategyDecision(org.com.sharekhan.service.FnoMoverEntrySignal.decision(candle.plusMinutes(5)));
        storedTrade.setComment("Changed display comment");storedTrade.setStrategyDecision(org.com.sharekhan.service.FnoMoverEntrySignal.decision(candle.plusMinutes(5)));
        requests.saveAndFlush(storedRequest);trades.saveAndFlush(storedTrade);entityManager.clear();
        assertThat(requests.findById(request.getId()).orElseThrow().getStrategyDecision()).isEqualTo(metadata);
        assertThat(trades.findById(trade.getId()).orElseThrow().getStrategyDecision()).isEqualTo(metadata);
    }

    @Test void cashShortDirectionSurvivesRestartForRequestsAndFilledPositions() {
        var request = requests.saveAndFlush(org.com.sharekhan.entity.TriggerTradeRequestEntity.builder().entryAction("SELL").build());
        var trade = trades.saveAndFlush(TriggeredTradeSetupEntity.builder().entryAction("SELL").intraday(true).brokerExecutionProduct("INTRADAY").build());
        entityManager.clear();
        assertThat(org.com.sharekhan.util.TradeSide.isShort(requests.findById(request.getId()).orElseThrow())).isTrue();
        assertThat(org.com.sharekhan.util.TradeSide.exit(trades.findById(trade.getId()).orElseThrow())).isEqualTo("BUY");
    }

    @Test void positionPnlCannotAttributeAnAggregateToSeveralOpenTradesOrOtherCredentials() {
        var one=trades.saveAndFlush(TriggeredTradeSetupEntity.builder().brokerCredentialsId(6L).symbol("NIFTY")
            .exchange("NF").expiry("27/10/2026").optionType("CE").strikePrice(22850d)
            .status(org.com.sharekhan.enums.TriggeredTradeStatus.EXECUTED).build());
        var statuses=java.util.Set.of(org.com.sharekhan.enums.TriggeredTradeStatus.EXECUTED);
        assertThat(trades.countOpenContract(6L,"NF","NIFTY","27/10/2026","CE",22850d,statuses)).isEqualTo(1);
        trades.saveAndFlush(TriggeredTradeSetupEntity.builder().brokerCredentialsId(6L).symbol("NIFTY")
            .exchange("NF").expiry("27/10/2026").optionType("CE").strikePrice(22850d)
            .status(org.com.sharekhan.enums.TriggeredTradeStatus.EXECUTED).build());
        entityManager.clear();
        assertThat(trades.countOpenContract(6L,"NF","NIFTY","27/10/2026","CE",22850d,statuses)).isEqualTo(2);
        assertThat(trades.countOpenContract(7L,"NF","NIFTY","27/10/2026","CE",22850d,statuses)).isZero();
    }

    @Test void restartRetainsDeliveryProductIndependentlyOfSameDayExitAndPreservesLegacyUnknown() {
        var current=trades.saveAndFlush(TriggeredTradeSetupEntity.builder().intraday(true).brokerExecutionProduct("CNC").build());
        var legacy=trades.saveAndFlush(TriggeredTradeSetupEntity.builder().intraday(true).build());
        entityManager.clear();
        var reloaded=trades.findById(current.getId()).orElseThrow();
        assertThat(reloaded.getBrokerExecutionProduct()).isEqualTo("CNC");
        assertThat(reloaded.getIntraday()).isTrue();
        assertThat(trades.findById(legacy.getId()).orElseThrow().getBrokerExecutionProduct()).isNull();
    }
}
