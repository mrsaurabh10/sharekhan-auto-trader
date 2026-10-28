package org.com.sharekhan.auth;

import java.time.*;
import java.util.*;
import org.com.sharekhan.config.ShoonyaProperties;
import org.com.sharekhan.entity.AccessTokenEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.repository.AccessTokenRepository;
import org.junit.jupiter.api.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ShoonyaGlobalSessionRefreshTest {
    final LocalDateTime now=LocalDateTime.of(2026,10,28,8,40);
    final ZoneId zone=ZoneId.of("Asia/Kolkata");
    final AccessTokenRepository tokens=mock(AccessTokenRepository.class);
    final ShoonyaProperties properties=new ShoonyaProperties();
    final BrokerAuthProvider provider=mock(BrokerAuthProvider.class);
    final TokenStoreService store=mock(TokenStoreService.class);
    ShoonyaGlobalSessionRefresh refresh;
    AccessTokenEntity saved(LocalDateTime issued) {
        return AccessTokenEntity.builder().token("encrypted-test-session")
                .expiry(issued.plusSeconds(properties.getAccessTokenTtlSeconds()-60).atZone(zone).toInstant()).build();
    }
    @BeforeEach void setup() {
        when(provider.getBroker()).thenReturn(Broker.SHOONYA);
        when(provider.loginAndFetchToken()).thenReturn(new AuthTokenResult("new-session",28800));
        when(tokens.findAllByBrokerName("Shoonya")).thenReturn(List.of());
        refresh=spy(new ShoonyaGlobalSessionRefresh(tokens,properties,new BrokerAuthProviderRegistry(List.of(provider)),store));
        doReturn(800L).when(refresh).availableMemoryMiB();
    }
    @Test void missingGlobalSessionIsLoggedInAndPersistedWithoutCustomerCredentials() {
        refresh.maintain(now);
        verify(provider).loginAndFetchToken();verify(provider,never()).loginAndFetchToken(any());
        verify(store).updateToken(Broker.SHOONYA,"new-session",28800);
        verify(tokens,never()).save(any());
    }
    @Test void previousDayRequiresNewLoginEvenIfExpiryEstimateIsInFuture() {
        var old=saved(now.minusDays(1));old.setExpiry(now.plusHours(2).atZone(zone).toInstant());
        // A custom longer TTL can leave yesterday's issued session locally unexpired.
        properties.setAccessTokenTtlSeconds(24*3600);
        when(provider.loginAndFetchToken()).thenReturn(new AuthTokenResult("new-session",24*3600));
        when(tokens.findAllByBrokerName("Shoonya")).thenReturn(List.of(old));
        refresh.maintain(now);verify(provider).loginAndFetchToken();
    }
    @Test void validCurrentDaySessionMakesAllMorningRetriesNoOps() {
        when(tokens.findAllByBrokerName("Shoonya")).thenReturn(List.of(saved(now.minusMinutes(1))));
        for(int minute:List.of(40,50,55))refresh.maintain(now.withMinute(minute));
        verify(provider,never()).loginAndFetchToken();verifyNoInteractions(store);
    }
    @Test void currentDayLocalExpiryDoesNotForceAnotherLogin() {
        when(tokens.findAllByBrokerName("Shoonya")).thenReturn(List.of(saved(now.withHour(0).withMinute(1))));
        refresh.maintain(now);verify(provider,never()).loginAndFetchToken();
    }
    @Test void customerAndUserSessionsNeverSuppressGlobalRefresh() {
        var scoped=saved(now.minusMinutes(1));scoped.setBrokerCredentialsId(9L);
        var user=saved(now.minusMinutes(1));user.setUserId(3L);
        when(tokens.findAllByBrokerName("Shoonya")).thenReturn(List.of(scoped,user));
        refresh.maintain(now);verify(provider).loginAndFetchToken();
    }
    @Test void missingTokenAndFutureIssuedTokenDoNotCountAsCurrent() {
        var blank=saved(now.minusMinutes(1));blank.setToken(" ");
        when(tokens.findAllByBrokerName("Shoonya")).thenReturn(List.of(blank,saved(now.plusHours(1))));
        refresh.maintain(now);verify(provider).loginAndFetchToken();
    }
    @Test void lowOrUnknownMemoryDefersUntilLaterSlot() {
        doReturn(449L,-1L,800L).when(refresh).availableMemoryMiB();
        refresh.maintain(now);refresh.maintain(now.withMinute(50));
        verify(provider,never()).loginAndFetchToken();
        refresh.maintain(now.withMinute(55));verify(provider).loginAndFetchToken();
    }
    @Test void failedLoginCanRetryWithoutPersistingFailure() {
        when(provider.loginAndFetchToken()).thenThrow(new IllegalStateException("private provider detail"))
                .thenReturn(new AuthTokenResult("new-session",28800));
        refresh.maintain(now);verifyNoInteractions(store);
        refresh.maintain(now.withMinute(50));verify(store).updateToken(Broker.SHOONYA,"new-session",28800);
    }
    @Test void invalidResultsCannotReplaceSavedSession() {
        when(provider.loginAndFetchToken()).thenReturn(null,new AuthTokenResult("",28800),new AuthTokenResult("bad-ttl",60));
        for(int minute:List.of(40,50,55))refresh.maintain(now.withMinute(minute));
        verifyNoInteractions(store);
    }
    @Test void noStartupIntradayWeekendOrDisabledLogin() {
        for(var time:List.of(now.withHour(7),now.withMinute(30),now.withHour(9),now.plusDays(3)))refresh.maintain(time);
        ReflectionTestUtils.setField(refresh,"enabled",false);refresh.maintain(now);
        verifyNoInteractions(tokens,store);verify(provider,never()).loginAndFetchToken();
    }
    @Test void cronUsesIstAndThreeBoundedMorningSlots() throws Exception {
        var scheduled=ShoonyaGlobalSessionRefresh.class.getMethod("scheduled").getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("0 40,50,55 8 * * MON-FRI");
        assertThat(scheduled.zone()).isEqualTo("Asia/Kolkata");
    }
}
