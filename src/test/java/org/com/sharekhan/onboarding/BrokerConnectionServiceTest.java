package org.com.sharekhan.onboarding;
import org.com.sharekhan.auth.*;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class BrokerConnectionServiceTest {
    UserTradingAccess access=mock(UserTradingAccess.class);
    BrokerCredentialsRepository brokers=mock(BrokerCredentialsRepository.class);
    BrokerConnectionRepository connections=mock(BrokerConnectionRepository.class);
    SharekhanAuthProvider provider=mock(SharekhanAuthProvider.class);
    AccessTokenPersistenceService tokens=mock(AccessTokenPersistenceService.class);
    StrategySubscriptionRepository subscriptions=mock(StrategySubscriptionRepository.class);
    TriggeredTradeSetupRepository trades=mock(TriggeredTradeSetupRepository.class);
    TriggerTradeRequestRepository requests=mock(TriggerTradeRequestRepository.class);
    BrokerConnectionService service=new BrokerConnectionService(access,brokers,connections,provider,mock(DhanAuthProvider.class),tokens,subscriptions,trades,requests);
    @Test void verifiesWithoutImplicitlyEnablingTradingOrReturningToken() {
        var broker=broker();when(provider.loginAndFetchToken(broker)).thenReturn(new AuthTokenResult("private-token",3600));
        var result=service.connect(1L);
        assertThat(result.toString()).doesNotContain("private-token");assertThat(broker.getTradingEnabled()).isFalse();
        verify(tokens).replaceTokenForBrokerCredentials(eq("Sharekhan"),eq(1L),eq("private-token"),any());
        verify(connections).save(any());
    }
    @Test void invalidCredentialsReturnSanitizedMessage() {
        var b=broker();when(provider.loginAndFetchToken(b)).thenThrow(new IllegalStateException("sensitive-detail"));
        assertThatThrownBy(()->service.connect(1L)).isInstanceOf(ResponseStatusException.class).hasMessageNotContaining("sensitive-detail");
        verifyNoInteractions(tokens);
    }
    @Test void enablingTradingRequiresVerifiedSession() {
        broker();assertThatThrownBy(()->service.setTrading(1L,true)).isInstanceOf(ResponseStatusException.class);
        verify(brokers,never()).save(any());
    }
    @Test void disconnectRefusesOpenExposure() {
        broker();when(subscriptions.findByAppUserIdOrderByIdDesc(7L)).thenReturn(List.of());
        when(trades.existsByBrokerCredentialsIdAndStatusIn(eq(1L),any())).thenReturn(true);
        assertThatThrownBy(()->service.disconnect(1L)).isInstanceOf(ResponseStatusException.class);
        verify(brokers,never()).save(any());
    }
    BrokerCredentialsEntity broker() {
        var b=BrokerCredentialsEntity.builder().id(1L).appUserId(7L).customerId(12345L).brokerName("Sharekhan").active(false).tradingEnabled(false).build();
        when(access.owned(1L)).thenReturn(b);return b;
    }
}
