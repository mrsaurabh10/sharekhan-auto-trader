package org.com.sharekhan.strategy.batman;
import org.junit.jupiter.api.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.cache.QuoteCacheService;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class BatmanMarketEntryTest {
 final BatmanBasketRepository baskets=mock(BatmanBasketRepository.class);final BrokerCredentialsRepository credentials=mock(BrokerCredentialsRepository.class);final BatmanBooks books=mock(BatmanBooks.class);
 final BatmanMarketEntry service=new BatmanMarketEntry(baskets,credentials,books);
 final BatmanBasket basket=BatmanBasket.builder().id("b").strategyRunId("r").appUserId(3L).brokerCredentialsId(4L).symbol("NIFTY").expiry(LocalDate.of(2026,12,29)).state("ENTERING")
  .planJson("[{\"marketEntry\":true,\"code\":1,\"exchange\":\"NF\",\"quantity\":130,\"action\":\"SELL\",\"strike\":26000,\"side\":\"CE\",\"admittedPremium\":80}]").build();
 TriggeredTradeSetupEntity trade(){return TriggeredTradeSetupEntity.builder().source("batman:b:0").strategyRunId("r").appUserId(3L).brokerCredentialsId(4L).symbol("NIFTY").scripCode(1).exchange("NF").quantity(130L).entryAction("SELL").strikePrice(26000d).optionType("CE").expiry("29/12/2026").build();}
 @BeforeEach void setup(){when(baskets.findByStrategyRunId("r")).thenReturn(Optional.of(basket));when(credentials.findById(4L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(3L).brokerName("Simulator").build()));}
 @Test void usesCurrentMarketPriceEvenIfDepthAndAdmittedPriceNoLongerMatch(){when(books.marketBook(1,"NF")).thenReturn(QuoteCacheService.QuoteSnapshot.builder().bestBid(70d).bestAsk(72d).bidQuantity(0d).askQuantity(0d).build());assertThat(service.price(trade()).getAsDouble()).isEqualTo(70);}
 @Test void refreshFailureUsesExplicitlyRecordedObservedPremium(){assertThat(service.price(trade()).getAsDouble()).isEqualTo(80);}
 @Test void cannotUseMarketAssumptionForLiveBroker(){when(credentials.findById(4L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(3L).brokerName("Sharekhan").build()));assertThatThrownBy(()->service.price(trade())).hasMessageContaining("Simulator-only");}
 @Test void changedLegCannotUseReviewedPlan(){var t=trade();t.setQuantity(65L);assertThatThrownBy(()->service.price(t)).hasMessageContaining("differs");}
 @Test void scheduledBasketAndAlreadyArmedBasketKeepNormalExecution(){basket.setPlanJson("[{\"marketEntry\":false}]");assertThat(service.price(trade())).isEmpty();basket.setState("ARMED");assertThat(service.price(trade())).isEmpty();}
}
