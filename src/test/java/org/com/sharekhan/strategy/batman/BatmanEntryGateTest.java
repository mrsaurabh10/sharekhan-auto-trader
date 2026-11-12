package org.com.sharekhan.strategy.batman;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class BatmanEntryGateTest{
 final BatmanBasketRepository baskets=mock(BatmanBasketRepository.class);final StrategyRunRepository runs=mock(StrategyRunRepository.class);
 final TriggeredTradeSetupRepository trades=mock(TriggeredTradeSetupRepository.class);final TriggerTradeRequestRepository requests=mock(TriggerTradeRequestRepository.class);
 final EntityManager em=mock(EntityManager.class);final AppUserRepository users=mock(AppUserRepository.class);
 final BatmanBasketStore store=new BatmanBasketStore(baskets,runs,em,mock(PlatformTransactionManager.class),users,trades,requests);
 final LocalDate day=LocalDate.of(2026,11,16);
 BatmanBasket b(String run){return BatmanBasket.builder().id(run).appUserId(1L).brokerCredentialsId(2L).symbol("NIFTY").strategyRunId(run).claimedAt(day.minusDays(3).atTime(9,20)).expiry(day.plusDays(43)).build();}
 StrategyRun r(String id,String status){return StrategyRun.builder().id(id).appUserId(1L).brokerCredentialsId(2L).symbol("NIFTY").templateId(BatmanRules.TEMPLATE).status(status).exitReason("STRATEGY_PROFIT_TARGET").closedAt(day.minusDays(3).atTime(14,0)).build();}
 @Test void anyOlderOpenBasketBlocksEvenIfLatestCycleClosed(){when(baskets.findByAppUserIdAndBrokerCredentialsIdAndSymbolOrderByClaimedAtDesc(1L,2L,"NIFTY")).thenReturn(List.of(b("latest"),b("older")));when(runs.findById("latest")).thenReturn(Optional.of(r("latest","CLOSED")));when(runs.findById("older")).thenReturn(Optional.of(r("older","OPEN")));assertThat(store.entryBlockReason(1L,2L,"NIFTY",day)).contains("unresolved");}
 @Test void completeTargetYesterdayAllowsNewDailyClaim(){when(baskets.findByAppUserIdAndBrokerCredentialsIdAndSymbolOrderByClaimedAtDesc(1L,2L,"NIFTY")).thenReturn(List.of(b("prior")));when(runs.findById("prior")).thenReturn(Optional.of(r("prior","CLOSED")));assertThat(store.entryBlockReason(1L,2L,"NIFTY",day)).isNull();}
 @Test void missingHistoricalRunBlocksRatherThanReentering(){when(baskets.findByAppUserIdAndBrokerCredentialsIdAndSymbolOrderByClaimedAtDesc(1L,2L,"NIFTY")).thenReturn(List.of(b("missing")));assertThat(store.entryBlockReason(1L,2L,"NIFTY",day)).contains("unresolved");}
 @Test void transactionalClaimRechecksExposureAfterTakingOwnerLock(){
  var user=new AppUser();user.setId(1L);when(users.lockForEnrollment(1L)).thenReturn(Optional.of(user));
  when(baskets.findByAppUserIdAndBrokerCredentialsIdAndSymbolOrderByClaimedAtDesc(1L,2L,"NIFTY")).thenReturn(List.of(),List.of(b("raced")));
  when(runs.findById("raced")).thenReturn(Optional.of(r("raced","OPEN")));
  assertThat(store.entryBlockReason(1L,2L,"NIFTY",day)).isNull();var next=b("next");next.setClaimedAt(day.atTime(9,20));
  assertThat(store.claim(next)).isFalse();verify(users).lockForEnrollment(1L);verify(em,never()).persist(any());
 }

}
