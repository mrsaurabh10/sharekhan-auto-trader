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
}
