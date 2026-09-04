package org.com.sharekhan.service;

import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MStockLtpPollingServiceTest {

    @Test
    void detectsFreshSharekhanQuoteForMStockFallbackGate() {
        WebSocketSubscriptionService subscriptionService = mock(WebSocketSubscriptionService.class);
        MStockLtpService mStockLtpService = mock(MStockLtpService.class);
        LtpCacheService ltpCacheService = mock(LtpCacheService.class);
        QuoteCacheService quoteCacheService = mock(QuoteCacheService.class);
        PriceTriggerService priceTriggerService = mock(PriceTriggerService.class);
        MStockInstrumentResolver instrumentResolver = mock(MStockInstrumentResolver.class);
        TokenStoreService tokenStoreService = mock(TokenStoreService.class);

        MStockLtpPollingService service = new MStockLtpPollingService(
                subscriptionService,
                mStockLtpService,
                ltpCacheService,
                quoteCacheService,
                priceTriggerService,
                mock(ScripExecutorManager.class),
                instrumentResolver,
                tokenStoreService);
        ReflectionTestUtils.setField(service, "sharekhanQuoteStaleMs", 2000L);

        QuoteCacheService.QuoteSnapshot quote = QuoteCacheService.QuoteSnapshot.builder()
                .scripCode(123456)
                .bestBid(10.50)
                .bestAsk(10.60)
                .lastTradedPrice(10.55)
                .midPrice(10.55)
                .spreadAbsolute(0.10)
                .spreadPercent(0.95)
                .updatedAt(Instant.now())
                .build();

        when(quoteCacheService.getSnapshot(123456)).thenReturn(Optional.of(quote));
        when(quoteCacheService.isStale(eq(quote), any(Duration.class))).thenReturn(false);

        Boolean fresh = ReflectionTestUtils.invokeMethod(service, "hasFreshSharekhanQuote", 123456);

        assertThat(fresh).isTrue();
    }

    @Test
    void rejectsShoonyaResponseForDifferentTokenWithoutUpdatingOptionLtpCache() {
        WebSocketSubscriptionService subscriptionService = mock(WebSocketSubscriptionService.class);
        LtpCacheService ltpCacheService = mock(LtpCacheService.class);
        QuoteCacheService quoteCacheService = mock(QuoteCacheService.class);
        ScripExecutorManager executorManager = mock(ScripExecutorManager.class);
        MStockLtpPollingService service = new MStockLtpPollingService(
                subscriptionService,
                mock(MStockLtpService.class),
                ltpCacheService,
                quoteCacheService,
                mock(PriceTriggerService.class),
                executorManager,
                mock(MStockInstrumentResolver.class),
                mock(TokenStoreService.class));
        ShoonyaQuoteService shoonyaQuoteService = mock(ShoonyaQuoteService.class);
        ScriptMasterRepository scriptMasterRepository = mock(ScriptMasterRepository.class);
        ReflectionTestUtils.setField(service, "shoonyaQuoteService", shoonyaQuoteService);
        ReflectionTestUtils.setField(service, "scriptMasterRepository", scriptMasterRepository);
        ReflectionTestUtils.setField(service, "shoonyaPollMaxActiveScrips", 1);

        ScriptMasterEntity option = ScriptMasterEntity.builder()
                .scripCode(68389)
                .tradingSymbol("DIVISLAB29SEP26C9300")
                .exchange("NF")
                .optionType("CE")
                .build();
        when(scriptMasterRepository.findByScripCode(68389)).thenReturn(option);
        when(shoonyaQuoteService.getQuote(option)).thenReturn(Optional.of(new ShoonyaQuoteService.LiveQuote(
                "DIVISLAB29SEP26C9300", "68389", "DIVISLAB-EQ", "10940", 9279d, null, null)));

        ReflectionTestUtils.invokeMethod(service, "refreshActiveQuotesFromShoonya", Set.of("NF68389"));

        verify(ltpCacheService, never()).updateLtp(68389, 9279d);
        verify(quoteCacheService, never()).recordQuote(eq(68389), any(), any(), any());
        verify(executorManager, never()).submitTriggerTask(eq(68389), any());
        verify(executorManager, never()).submitMonitorTask(eq(68389), any());
    }
}
