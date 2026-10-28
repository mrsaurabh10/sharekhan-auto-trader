package org.com.sharekhan.auth;

import java.time.Instant;
import java.util.Optional;
import org.com.sharekhan.entity.AccessTokenEntity;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class SavedFundsSessionTest {
    @Test void fundsSessionNeverFallsBackToAnotherAccountOrStartsAuthentication() {
        var persistence=mock(AccessTokenPersistenceService.class);
        var registry=mock(BrokerAuthProviderRegistry.class);
        var credentials=mock(BrokerCredentialsRepository.class);
        var store=new TokenStoreService(persistence,registry,null,null,credentials);
        when(credentials.findById(55L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder()
                .id(55L).brokerName("Sharekhan").appUserId(9L).customerId(123L).apiKey("test-key").build()));
        when(persistence.findLatestByBrokerAndBrokerCredentialsId("Sharekhan",55L)).thenReturn(Optional.empty());
        assertThat(store.getSavedTokenInfoForBrokerCredentials(Broker.SHAREKHAN,55L)).isNull();
        var token=new AccessTokenEntity();token.setToken("saved-test-token");token.setExpiry(Instant.now().minusSeconds(1));
        when(persistence.findLatestByBrokerAndBrokerCredentialsId("Sharekhan",55L)).thenReturn(Optional.of(token));
        assertThat(store.getSavedTokenInfoForBrokerCredentials(Broker.SHAREKHAN,55L)).isNull();
        token.setExpiry(Instant.now().plusSeconds(300));
        assertThat(store.getSavedTokenInfoForBrokerCredentials(Broker.SHAREKHAN,55L).getCustomerId()).isEqualTo(123L);
        assertThat(store.getSavedTokenInfoForBrokerCredentials(Broker.SHAREKHAN,99L)).isNull();
        verifyNoInteractions(registry);
        verify(persistence,never()).replaceTokenForBrokerCredentials(anyString(),anyLong(),anyString(),any());
    }
}
