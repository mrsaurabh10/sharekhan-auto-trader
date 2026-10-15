package org.com.sharekhan.onboarding;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.service.CurrentUserService;
import org.com.sharekhan.dto.TriggerRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class UserTradingAccessTest {
    CurrentUserService current=mock(CurrentUserService.class);
    BrokerCredentialsRepository brokers=mock(BrokerCredentialsRepository.class);
    BrokerConnectionRepository connections=mock(BrokerConnectionRepository.class);
    AccountProfileRepository profiles=mock(AccountProfileRepository.class);
    UserTradingAccess access=new UserTradingAccess(current,brokers,connections,profiles);
    @Test void rejectsForeignBrokerEvenWithValidSession() {
        var b=readyBroker(); b.setAppUserId(99L);
        assertThatThrownBy(()->access.requireReady(7L,1L)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void rejectsMissingExpiredInactiveAndDisabledBroker() {
        assertThatThrownBy(()->access.requireReady(7L,null)).isInstanceOf(AccessDeniedException.class);
        var b=readyBroker(); b.setTradingEnabled(false);
        assertThatThrownBy(()->access.requireReady(7L,1L)).isInstanceOf(ResponseStatusException.class);
        b.setTradingEnabled(true); b.setActive(false);
        assertThatThrownBy(()->access.requireReady(7L,1L)).isInstanceOf(ResponseStatusException.class);
        b.setActive(true); var c=new BrokerConnection();c.setVerifiedUntil(Instant.EPOCH);when(connections.findById(1L)).thenReturn(Optional.of(c));
        assertThatThrownBy(()->access.requireReady(7L,1L)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void replacesSpoofedOwnerAndRemovesFanOutHints() {
        readyBroker();when(current.requireAppUserId()).thenReturn(7L);
        TriggerRequest request=new TriggerRequest();request.setBrokerCredentialsId(1L);request.setUserId(99L);
        request.setBrokerName("Sharekhan");request.setTargetBrokerCredentialsIds(List.of(99L));request.setRoutingMode("ALL_ENABLED");request.setLots(999);request.setQuickTrade(true);request.setUseSpotForEntry(true);
        request.setInstrument("RELIANCE");request.setExchange("NC");request.setQuantity(1);request.setEntryPrice(100.0);request.setStopLoss(90.0);
        access.scope(request);
        assertThat(request.getUserId()).isEqualTo(7L);assertThat(request.getTargetBrokerCredentialsIds()).isNull();
        assertThat(request.getLots()).isNull();assertThat(request.getQuickTrade()).isFalse();assertThat(request.getUseSpotForEntry()).isFalse();
        assertThat(request.getBrokerName()).isNull();assertThat(request.getRoutingMode()).isNull();
    }
    @Test void rejectsNonFinitePrices() {
        readyBroker();when(current.requireAppUserId()).thenReturn(7L);
        TriggerRequest r=new TriggerRequest();r.setBrokerCredentialsId(1L);r.setInstrument("TCS");r.setExchange("NC");r.setQuantity(1);r.setEntryPrice(Double.NaN);r.setStopLoss(10.0);
        assertThatThrownBy(()->access.scope(r)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void backgroundEntryUsesDurableAccountOwnership() {
        when(profiles.existsById(7L)).thenReturn(true);
        assertThatThrownBy(()->access.requireOnboardedReady(7L,99L)).isInstanceOf(AccessDeniedException.class);
    }
    BrokerCredentialsEntity readyBroker() {
        var b=BrokerCredentialsEntity.builder().id(1L).appUserId(7L).customerId(12345L).brokerName("Sharekhan").active(true).tradingEnabled(true).build();
        when(brokers.findById(1L)).thenReturn(Optional.of(b)); var c=new BrokerConnection();c.setVerifiedUntil(Instant.now().plusSeconds(60));
        when(connections.findById(1L)).thenReturn(Optional.of(c));return b;
    }
}
