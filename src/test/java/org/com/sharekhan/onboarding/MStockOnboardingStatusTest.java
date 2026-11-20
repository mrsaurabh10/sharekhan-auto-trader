package org.com.sharekhan.onboarding;

import java.time.Instant;
import java.util.*;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.CurrentUserService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MStockOnboardingStatusTest {
    @Test void verifiedMstockAccountCountsAsReadyWithoutCustomerId() {
        var current=mock(CurrentUserService.class);var brokers=mock(BrokerCredentialsRepository.class);
        var connections=mock(BrokerConnectionRepository.class);
        when(current.requireAppUserId()).thenReturn(2L);
        when(brokers.findByAppUserId(2L)).thenReturn(List.of(BrokerCredentialsEntity.builder().id(7L).appUserId(2L)
            .brokerName("MStock").apiKey("encrypted-key").active(true).tradingEnabled(true).build()));
        var connection=new BrokerConnection();connection.setCredentialId(7L);connection.setVerifiedUntil(Instant.now().plusSeconds(3600));
        when(connections.findAllById(List.of(7L))).thenReturn(List.of(connection));
        var service=new OnboardingStatusService(current,mock(AccountProfileRepository.class),brokers,connections,
            mock(StrategySubscriptionRepository.class),mock(TriggerTradeRequestRepository.class));
        var status=service.status();assertThat(status.connectedBrokers()).isEqualTo(1);assertThat(status.tradingReadyBrokers()).isEqualTo(1);
        assertThat(status.nextStep()).isEqualTo("WORKFLOW");
    }
}
