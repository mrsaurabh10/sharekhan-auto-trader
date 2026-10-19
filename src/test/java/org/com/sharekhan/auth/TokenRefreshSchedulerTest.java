package org.com.sharekhan.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.com.sharekhan.entity.AccessTokenEntity;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TokenRefreshSchedulerTest {
    TokenStoreService store=mock(TokenStoreService.class);
    AccessTokenPersistenceService persistence=mock(AccessTokenPersistenceService.class);
    BrokerCredentialsRepository credentials=mock(BrokerCredentialsRepository.class);
    BrokerAuthProvider provider=mock(BrokerAuthProvider.class);
    TokenRefreshScheduler scheduler;
    BrokerCredentialsEntity dhan;

    @BeforeEach void setup() {
        when(provider.getBroker()).thenReturn(Broker.DHAN);
        scheduler=new TokenRefreshScheduler(store,new BrokerAuthProviderRegistry(List.of(provider)),credentials,persistence);
        dhan=BrokerCredentialsEntity.builder().id(6L).appUserId(4L).brokerName("Dhan")
            .active(true).brokerPassword("encrypted-pin").totpSecret("encrypted-seed").build();
        when(credentials.findAll()).thenReturn(List.of(dhan));
        when(provider.loginAndFetchToken(dhan)).thenReturn(new AuthTokenResult("new-test-token",3600));
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void bothMorningJobsForceRefreshAndPersistDhanByCredential(boolean at830) {
        if(at830)scheduler.refreshTokenAt830IST(); else scheduler.refreshTokenAt730IST();
        var expiry=org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(persistence).replaceTokenForBrokerCredentials(eq("Dhan"),eq(6L),eq("new-test-token"),expiry.capture());
        assertThat(expiry.getValue()).isBetween(Instant.now().plusSeconds(3500),Instant.now().plusSeconds(3541));
        verifyNoInteractions(store);
        verify(provider,never()).loginAndFetchToken();
    }
    @Test void hourlyMaintenanceUsesScopedUnexpiredTokenAndDoesNotGenerateAnother() {
        when(persistence.findLatestByBrokerAndBrokerCredentialsId("Dhan",6L)).thenReturn(Optional.of(
            AccessTokenEntity.builder().token("saved-test-token").expiry(Instant.now().plusSeconds(3600)).build()));
        scheduler.refreshTokenIfNeeded();
        verify(provider,never()).loginAndFetchToken(any());
        verifyNoInteractions(store);
    }
    @Test void hourlyExpiredTokenIsPersistedForSameCredential() {
        when(persistence.findLatestByBrokerAndBrokerCredentialsId("Dhan",6L)).thenReturn(Optional.of(
            AccessTokenEntity.builder().token("old-test-token").expiry(Instant.now().minusSeconds(1)).build()));
        scheduler.refreshTokenIfNeeded();
        verify(persistence).replaceTokenForBrokerCredentials(eq("Dhan"),eq(6L),eq("new-test-token"),any());
        verifyNoInteractions(store);
    }
    @Test void noCustomerIdDoesNotUseAnotherAccountsGlobalExpiry() {
        scheduler.refreshTokenIfNeeded();
        verify(persistence).findLatestByBrokerAndBrokerCredentialsId("Dhan",6L);
        verify(provider).loginAndFetchToken(dhan);
        verifyNoInteractions(store);
    }
    @Test void inactiveCredentialsAreNotAuthenticated() {
        dhan.setActive(false);
        scheduler.refreshTokenAt830IST();
        verify(provider,never()).loginAndFetchToken(any());
        verifyNoInteractions(persistence,store);
    }
    @Test void apiKeyOnlyDhanNeedsBrowserConsentAndIsNotAutoAuthenticated() {
        dhan.setBrokerPassword(null); dhan.setTotpSecret(null); dhan.setApiKey("encrypted-key");
        scheduler.refreshTokenAt830IST();
        verify(provider,never()).loginAndFetchToken(any());
        verifyNoInteractions(persistence,store);
    }
    @Test void sharekhanRetainsItsCustomerRefreshPath() {
        dhan.setBrokerName("Sharekhan"); dhan.setCustomerId(123L);
        when(provider.getBroker()).thenReturn(Broker.SHAREKHAN);
        scheduler.refreshTokenAt830IST();
        verify(store).updateTokenForCustomer(Broker.SHAREKHAN,123L,4L,"new-test-token",3600);
        verifyNoInteractions(persistence);
    }
    @Test void failureForOneAccountDoesNotStopNextAccount() {
        var other=BrokerCredentialsEntity.builder().id(9L).appUserId(8L).brokerName("Dhan")
            .active(true).brokerPassword("other-pin").totpSecret("other-seed").build();
        when(credentials.findAll()).thenReturn(List.of(dhan,other));
        when(provider.loginAndFetchToken(dhan)).thenThrow(new IllegalStateException("synthetic failure"));
        when(provider.loginAndFetchToken(other)).thenReturn(new AuthTokenResult("other-token",3600));
        scheduler.refreshTokenAt830IST();
        verify(persistence,never()).replaceTokenForBrokerCredentials(eq("Dhan"),eq(6L),anyString(),any());
        verify(persistence).replaceTokenForBrokerCredentials(eq("Dhan"),eq(9L),eq("other-token"),any());
    }
    @Test void invalidProviderResultIsNotPersisted() {
        when(provider.loginAndFetchToken(dhan)).thenReturn(new AuthTokenResult("",3600),new AuthTokenResult("short-token",60));
        scheduler.refreshTokenAt830IST();scheduler.refreshTokenAt830IST();
        verifyNoInteractions(persistence,store);
    }
}
