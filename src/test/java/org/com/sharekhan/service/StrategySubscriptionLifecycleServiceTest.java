package org.com.sharekhan.service;

import jakarta.persistence.EntityManager;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.onboarding.UserTradingAccess;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class StrategySubscriptionLifecycleServiceTest {
    final StrategySubscriptionRepository subscriptions = mock(StrategySubscriptionRepository.class);
    final AppUserRepository users = mock(AppUserRepository.class);
    final UserTradingAccess access = mock(UserTradingAccess.class);
    final EntityManager em = mock(EntityManager.class);
    final StrategySubscriptionLifecycleService service = new StrategySubscriptionLifecycleService(subscriptions, users, access, em);
    final StrategySubscriptionEntity row = StrategySubscriptionEntity.builder().id(9L).appUserId(1L)
            .brokerCredentialsId(2L).templateId("ST_RSI_EMA_ADX_CE").symbol("NIFTY").lots(3)
            .status("ACTIVE").generatedTradeRequestId(88L).completedAt(LocalDateTime.now()).build();
    StrategySubscriptionLifecycleServiceTest() {
        when(subscriptions.findById(9L)).thenReturn(Optional.of(row));
        when(subscriptions.lockForRunEntry(9L)).thenReturn(Optional.of(row));
        when(subscriptions.save(any())).thenAnswer(c -> c.getArgument(0));
        when(users.lockForEnrollment(1L)).thenReturn(Optional.of(new AppUser()));
    }
    @Test void pauseResumeRetainsIdentitySettingsDailyLimitAndRequest() {
        var completed = row.getCompletedAt();
        service.pause(9L, 1L, false); assertThat(row.getStatus()).isEqualTo("PAUSED");
        service.pause(9L, 1L, false);
        service.resume(9L, 1L, false); assertThat(row.getStatus()).isEqualTo("ACTIVE");
        service.resume(9L, 1L, false);
        assertThat(row.getCompletedAt()).isEqualTo(completed);
        assertThat(row.getGeneratedTradeRequestId()).isEqualTo(88L);
        assertThat(row.getLots()).isEqualTo(3); assertThat(row.getId()).isEqualTo(9L);
        verify(access).requireReady(1L, 2L); verify(subscriptions, times(2)).save(row);
    }
    @Test void foreignAndAnonymousUsersCannotChangeSchedule() {
        for (Long user : Arrays.asList(3L, null)) {
            assertThatThrownBy(() -> service.pause(9L, user, false)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.resume(9L, user, false)).isInstanceOf(AccessDeniedException.class);
        }
        verify(subscriptions, never()).save(any()); verifyNoInteractions(access, users);
    }
    @Test void cancelledCannotBeResumedAndReadinessFailureKeepsPaused() {
        row.setStatus("CANCELLED");
        assertThatThrownBy(() -> service.resume(9L, 1L, false)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.pause(9L, 1L, false)).isInstanceOf(ResponseStatusException.class);
        row.setStatus("PAUSED");
        doThrow(new IllegalArgumentException("Connect broker")).when(access).requireReady(1L,2L);
        assertThatThrownBy(() -> service.resume(9L, 1L, false)).hasMessage("Connect broker");
        assertThat(row.getStatus()).isEqualTo("PAUSED"); verify(subscriptions, never()).save(any());
    }
    @Test void adminCanManageSelectedOwnerButUsesTheirBrokerReadiness() {
        service.pause(9L, 99L, true); service.resume(9L, 99L, true);
        verify(access).requireOnboardedReady(1L, 2L);
    }
}
