package org.com.sharekhan.service;

import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.entity.StrategySubscriptionEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.repository.StrategySubscriptionRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;

class StrategySubscriptionServiceTest {
    @ParameterizedTest
    @ValueSource(strings={"SPOT_ATR_PDH_BIGTRADEPLUS", "SPOT_ATR_PDL_BIGTRADEPLUS"})
    void preparesBothBtpDirectionsImmediatelyAndReevaluatesExistingUnevaluatedRuns(String template) {
        var repo=mock(StrategySubscriptionRepository.class);
        var evaluator=mock(StrategyTemplateService.class);
        var calendar=mock(NseMarketCalendar.class);
        var service=new StrategySubscriptionService(repo,evaluator,calendar);
        when(repo.save(any())).thenAnswer(call -> call.getArgument(0));
        var pending=new TriggerTradeRequestEntity(); pending.setId(123L);
        when(evaluator.apply(any())).thenReturn(StrategyApplyResponse.builder().status("triggered")
                .message("Pending brackets created").tradeRequest(pending).build());
        var request=new StrategyApplyRequest(); request.setTemplateId(template); request.setSymbol("ASTRAL");
        request.setLots(9); request.setUserId(1L); request.setBrokerCredentialsId(2L);
        var created=service.start(request);
        assertThat(created.getGeneratedTradeRequestId()).isEqualTo(123L);
        assertThat(created.getLastEvaluatedAt()).isNotNull();
        verify(evaluator).apply(argThat(r -> template.equals(r.getTemplateId()) && r.getLots()==9));
        var existing=StrategySubscriptionEntity.builder().id(120L).templateId(template).symbol("ASTRAL")
                .lots(9).appUserId(1L).brokerCredentialsId(2L).status("ACTIVE").build();
        when(repo.findByStatusInAndTemplateIdIgnoreCaseAndSymbolIgnoreCaseAndAppUserId(
                anyList(),eq(template),eq("ASTRAL"),eq(1L))).thenReturn(List.of(existing));
        assertThat(service.start(request)).isSameAs(existing);
        assertThat(existing.getGeneratedTradeRequestId()).isEqualTo(123L);
        verify(evaluator,times(2)).apply(any());
        verifyNoInteractions(calendar);
    }
    @org.junit.jupiter.api.Test
    void unrelatedDuplicatesDoNotCompleteTheDayButMatchingCurrentDayRequestDoes() {
        var repo=mock(StrategySubscriptionRepository.class);
        var evaluator=mock(StrategyTemplateService.class);
        var service=new StrategySubscriptionService(repo,evaluator,mock(NseMarketCalendar.class));
        var subscription=StrategySubscriptionEntity.builder().templateId("ST_RSI_EMA_ADX_CE").symbol("NIFTY")
                .appUserId(1L).brokerCredentialsId(2L).status("ACTIVE").build();
        var duplicate=TriggerTradeRequestEntity.builder().id(123L).symbol("NIFTY").appUserId(1L)
                .brokerCredentialsId(3L).source("strategy:ST_RSI_EMA_ADX_CE").optionType("CE")
                .status(org.com.sharekhan.enums.TriggeredTradeStatus.TRIGGERED)
                .createdAt(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata"))).build();
        when(evaluator.apply(any())).thenReturn(StrategyApplyResponse.builder().status("duplicate").message("existing").tradeRequest(duplicate).build());
        service.evaluate(subscription);
        assertThat(subscription.getCompletedAt()).isNull();
        assertThat(subscription.getGeneratedTradeRequestId()).isNull();
        duplicate.setBrokerCredentialsId(2L); duplicate.setSource("strategy:ORB_915_930_CE");
        service.evaluate(subscription); assertThat(subscription.getCompletedAt()).isNull();
        duplicate.setSource("strategy:ST_RSI_EMA_ADX_CE"); duplicate.setOptionType("PE");
        service.evaluate(subscription); assertThat(subscription.getCompletedAt()).isNull();
        duplicate.setOptionType("CE"); duplicate.setCreatedAt(duplicate.getCreatedAt().minusDays(1));
        service.evaluate(subscription); assertThat(subscription.getCompletedAt()).isNull();
        duplicate.setCreatedAt(duplicate.getCreatedAt().plusDays(1));
        service.evaluate(subscription); assertThat(subscription.getCompletedAt()).isNotNull();
        assertThat(subscription.getGeneratedTradeRequestId()).isEqualTo(123L);
        service.evaluate(subscription);
        verify(evaluator,times(5)).apply(any());
    }

    @org.junit.jupiter.api.Test
    void startingTheSameTemplateForAnotherBrokerCreatesAnotherSubscription() {
        var repo=mock(StrategySubscriptionRepository.class);
        var service=new StrategySubscriptionService(repo,mock(StrategyTemplateService.class),mock(NseMarketCalendar.class));
        var existing=StrategySubscriptionEntity.builder().templateId("ST_RSI_EMA_ADX_CE").symbol("NIFTY")
                .appUserId(1L).brokerCredentialsId(2L).status("ACTIVE").build();
        when(repo.findByStatusInAndTemplateIdIgnoreCaseAndSymbolIgnoreCaseAndAppUserId(anyList(),anyString(),anyString(),anyLong()))
                .thenReturn(List.of(existing));
        when(repo.save(any())).thenAnswer(call->call.getArgument(0));
        var request=new StrategyApplyRequest(); request.setTemplateId("ST_RSI_EMA_ADX_CE"); request.setSymbol("NIFTY");
        request.setUserId(1L); request.setBrokerCredentialsId(3L);
        var created=service.start(request);
        assertThat(created).isNotSameAs(existing);
        assertThat(created.getBrokerCredentialsId()).isEqualTo(3L);
    }

}
