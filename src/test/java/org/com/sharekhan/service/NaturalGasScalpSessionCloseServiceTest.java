package org.com.sharekhan.service;

import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.strategy.mcx.NaturalGasScalpingStrategy;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NaturalGasScalpSessionCloseServiceTest {
    final ZoneId zone=ZoneId.of("Asia/Kolkata");
    final LocalDateTime cutoff=LocalDateTime.of(2026,11,18,23,20);
    final TriggeredTradeSetupRepository trades=mock(TriggeredTradeSetupRepository.class);
    final QuoteCacheService quotes=mock(QuoteCacheService.class);
    final WebSocketSubscriptionService feeds=mock(WebSocketSubscriptionService.class);
    final ExitChaseControlService controls=mock(ExitChaseControlService.class);
    final TradeAuditService audit=mock(TradeAuditService.class);
    NaturalGasScalpSessionCloseService service;TriggeredTradeSetupEntity trade;
    NaturalGasScalpSessionCloseService instance(){return new NaturalGasScalpSessionCloseService(trades,quotes,feeds,controls,audit);}
    @BeforeEach void setup() {
        service=instance();
        trade=TriggeredTradeSetupEntity.builder().id(11411L).source(NaturalGasScalpingStrategy.SOURCE).exchange("MX")
                .symbol("NATURALGAS").appUserId(3L).brokerCredentialsId(4L).scripCode(583837).intraday(true)
                .entryAt(cutoff.withHour(20).withMinute(40)).actualEntryPrice(7.45).quantity(1250L)
                .status(TriggeredTradeStatus.EXECUTED).build();
        when(trades.findById(11411L)).thenReturn(Optional.of(trade));
        when(trades.latchNaturalGasSessionClose(eq(11411L),any())).thenAnswer(inv->{
            trade.setExitReason(NaturalGasScalpSessionCloseService.REASON);
            if(trade.getStatus()!=TriggeredTradeStatus.EXIT_ORDER_PLACED)trade.setStatus(TriggeredTradeStatus.EXIT_TRIGGERED);
            return 1;
        });
        when(feeds.retainFullFeed(anyString(),anyString())).thenReturn(true);
    }
    QuoteCacheService.QuoteSnapshot.QuoteSnapshotBuilder book(LocalDateTime at,double bid) {
        return QuoteCacheService.QuoteSnapshot.builder().scripCode(583837).bestBid(bid).bestAsk(bid+.05)
                .bookSource("SHAREKHAN_WS").bookExchange("MX").bookProviderIdentity("scrip:583837")
                .coherentBook(true).lastBookAt(at.atZone(zone).toInstant());
    }
    @Test void stale2320BookLatchesIntentAndReportsOnceRatherThanAttemptingFalseFill() {
        when(quotes.getSnapshot(583837)).thenReturn(Optional.of(book(cutoff.withHour(21).withMinute(26),6.8).build()));
        assertThat(service.prepare(11411L,cutoff)).isEmpty();
        assertThat(service.prepare(11411L,cutoff.plusSeconds(1))).isEmpty();
        assertThat(trade.getStatus()).isEqualTo(TriggeredTradeStatus.EXIT_TRIGGERED);
        assertThat(trade.getExitReason()).isEqualTo("MCX_SESSION_CLOSE");
        var order=inOrder(trades,quotes);order.verify(trades).findById(11411L);
        order.verify(trades).latchNaturalGasSessionClose(11411L,cutoff);
        order.verify(trades).findById(11411L);order.verify(quotes,atLeastOnce()).getSnapshot(583837);
        verify(controls).quoteAttention(eq(trade),contains("fresh exact option bid"),any());
        verify(audit).record(any());verify(feeds,atLeastOnce()).refreshFullFeed("ngas-scalp-position:11411","MX583837");
    }
    @Test void restartAndNextMorningUseFreshBidWithSessionCloseReason() {
        service.prepare(11411L,cutoff);service=instance();
        var morning=cutoff.plusDays(1).withHour(9).withMinute(0);
        when(quotes.getSnapshot(583837)).thenReturn(Optional.of(book(morning,14.45).build()));
        var exit=service.prepare(11411L,morning).orElseThrow();
        assertThat(exit.bid()).isEqualTo(14.45);assertThat(exit.trade().getExitReason()).isEqualTo("MCX_SESSION_CLOSE");
    }
    @Test void existingSellIdIsRetainedForRepricing() {
        trade.setExitOrderId("same-sell");trade.setStatus(TriggeredTradeStatus.EXIT_ORDER_PLACED);
        when(quotes.getSnapshot(583837)).thenReturn(Optional.of(book(cutoff,6.8).build()));
        var exit=service.prepare(11411L,cutoff).orElseThrow();
        assertThat(exit.trade().getExitOrderId()).isEqualTo("same-sell");
        assertThat(exit.trade().getStatus()).isEqualTo(TriggeredTradeStatus.EXIT_ORDER_PLACED);
    }
    @Test void freshLtpOrWrongIdentityDoesNotMakeBadBookExecutable() {
        for(var q:List.of(book(cutoff,6.8).bookSource("UNKNOWN").build(),
                book(cutoff,6.8).bookProviderIdentity("scrip:583838").build(),
                book(cutoff,6.8).coherentBook(false).build(),book(cutoff,6.8).bestAsk(6.7).build(),
                book(cutoff.plusSeconds(1),6.8).build(),book(cutoff.minusSeconds(6),6.8).lastLtpAt(cutoff.atZone(zone).toInstant()).build())) {
            when(quotes.getSnapshot(583837)).thenReturn(Optional.of(q));
            assertThat(service.prepare(11411L,cutoff)).isEmpty();
        }
    }
    @Test void afterHoursAndWeekendCannotExecuteEvenWithFreshBook() {
        for(var at:List.of(cutoff.withHour(23).withMinute(30),cutoff.plusDays(3).withHour(9))) {
            when(quotes.getSnapshot(583837)).thenReturn(Optional.of(book(at,6.8).build()));
            assertThat(service.prepare(11411L,at)).isEmpty();
        }
    }
    @Test void normalIntradayTradeIsWatchedButNotClosedBeforeCutoff() {
        assertThat(service.prepare(11411L,cutoff.minusSeconds(1))).isEmpty();
        service.retain(trade,cutoff.minusMinutes(10).atZone(zone).toInstant());
        verify(trades,never()).latchNaturalGasSessionClose(anyLong(),any());
        verify(feeds).retainFullFeed("ngas-scalp-position:11411","MX583837");
        service.releaseFlat(Set.of(11411L));verify(feeds,never()).releaseFullFeed(anyString());
        service.releaseFlat(Set.of());verify(feeds,never()).releaseFullFeed(anyString());
        trade.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);trade.setReason("FILL_UNKNOWN");
        service.releaseFlat(Set.of());verify(feeds,never()).releaseFullFeed(anyString());
        trade.setReason(null);
        service.releaseFlat(Set.of());verify(feeds).releaseFullFeed("ngas-scalp-position:11411");
    }
    @Test void otherStrategiesAndAlreadyFlatRowsAreUntouched() {
        trade.setSource("strategy:MCX_NATURALGAS_ATM_BREAKOUT");assertThat(service.prepare(11411L,cutoff)).isEmpty();
        trade.setSource(NaturalGasScalpingStrategy.SOURCE);trade.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);
        assertThat(service.prepare(11411L,cutoff)).isEmpty();
        verify(trades,never()).latchNaturalGasSessionClose(anyLong(),any());verifyNoInteractions(feeds,controls,audit);
    }
}
