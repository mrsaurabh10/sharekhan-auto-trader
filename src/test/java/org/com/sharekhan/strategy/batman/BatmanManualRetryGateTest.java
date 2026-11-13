package org.com.sharekhan.strategy.batman;
import org.junit.jupiter.api.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class BatmanManualRetryGateTest {
 final BatmanBasketRepository baskets=mock(BatmanBasketRepository.class);final StrategyRunRepository runs=mock(StrategyRunRepository.class);
 final TriggeredTradeSetupRepository trades=mock(TriggeredTradeSetupRepository.class);final TriggerTradeRequestRepository requests=mock(TriggerTradeRequestRepository.class);
 final BatmanBasketStore store=new BatmanBasketStore(baskets,runs,mock(EntityManager.class),mock(PlatformTransactionManager.class),mock(AppUserRepository.class),trades,requests);
 final LocalDate day=LocalDate.of(2026,11,13);
 final BatmanBasket prior=BatmanBasket.builder().id("original").strategyRunId("old").appUserId(3L).brokerCredentialsId(4L).symbol("NIFTY").state("CLOSED").claimedAt(day.atTime(12,0)).expiry(day.plusDays(46)).build();
 final StrategyRun old=StrategyRun.builder().id("old").appUserId(3L).brokerCredentialsId(4L).symbol("NIFTY").templateId(BatmanRules.TEMPLATE).subscriptionId(191L).instance("DAY").status("CLOSED").closedAt(day.atTime(12,1)).exitReason("BATMAN_ENTRY_ABORT").build();
 @BeforeEach void setup(){when(baskets.findByAppUserIdAndBrokerCredentialsIdAndSymbolOrderByClaimedAtDesc(3L,4L,"NIFTY")).thenReturn(List.of(prior));when(runs.findById("old")).thenReturn(Optional.of(old));}
 String reason(){return store.retryBlockReason(3L,4L,"NIFTY",day,"old",191L);}
 @Test void onlyReviewedOriginalAbortMayRetryToday(){assertThat(reason()).isNull();assertThat(store.entryBlockReason(3L,4L,"NIFTY",day)).isNotNull();}
 @Test void unresolvedExposureBlocksEvenExplicitRetry(){when(trades.findByStrategyRunId("old")).thenReturn(List.of(TriggeredTradeSetupEntity.builder().appUserId(3L).brokerCredentialsId(4L).strategyRunId("old").status(TriggeredTradeStatus.EXECUTED).build()));assertThat(reason()).contains("not confirmed flat");}
 @Test void profitOrStopClosureDoesNotGrantSameDayRetry(){old.setExitReason("STRATEGY_MAX_LOSS");assertThat(reason()).isNotNull();}
 @Test void differentSubscriptionOrRunCannotBeReviewedByThisCall(){assertThat(store.retryBlockReason(3L,4L,"NIFTY",day,"old",192L)).isNotNull();assertThat(store.retryBlockReason(3L,4L,"NIFTY",day,"other",191L)).isNotNull();}
 @Test void secondAttemptExhaustsAuthorizationEvenIfFlat(){var retry=BatmanBasket.builder().id("retry").strategyRunId("retry-run").claimedAt(day.atTime(13,0)).state("CLOSED").build();var r=StrategyRun.builder().id("retry-run").appUserId(3L).brokerCredentialsId(4L).symbol("NIFTY").templateId(BatmanRules.TEMPLATE).status("CLOSED").closedAt(day.atTime(13,1)).build();when(runs.findById("retry-run")).thenReturn(Optional.of(r));when(baskets.findByAppUserIdAndBrokerCredentialsIdAndSymbolOrderByClaimedAtDesc(3L,4L,"NIFTY")).thenReturn(List.of(retry,prior));assertThat(reason()).isNotNull();}
}
