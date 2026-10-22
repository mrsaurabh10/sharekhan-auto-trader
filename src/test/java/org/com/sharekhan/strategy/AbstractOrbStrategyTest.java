package org.com.sharekhan.strategy;

import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.config.OrbStrategyProperties;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AbstractOrbStrategyTest {
    private final StrategySupport support=mock(StrategySupport.class);
    private final LtpCacheService prices=mock(LtpCacheService.class);
    private final Orb915930CeStrategy strategy=new Orb915930CeStrategy(support,prices,new OrbStrategyProperties());
    private final LocalDate day=LocalDate.of(2026,10,5);
    private final StrategyApplyRequest request=new StrategyApplyRequest();

    private void prepare() {
        request.setSymbol("NIFTY"); request.setUserId(1L); request.setBrokerCredentialsId(2L);
        when(support.resolveSpotScript("NIFTY")).thenReturn(ScriptMasterEntity.builder().scripCode(20000).build());
        when(support.loadCandles(any())).thenReturn(new CandleLoad(List.of(
                candle(9,15,99,100),candle(9,20,99,100),candle(9,25,99,100),
                candle(9,30,101,103),candle(9,35,103,103)),false,null));
        when(support.roundPrice(anyDouble())).thenAnswer(call -> call.getArgument(0));
        when(support.waiting(any(),anyString(),anyString())).thenAnswer(call -> StrategyApplyResponse.builder().status("waiting").message(call.getArgument(2)).build());
        when(prices.getLtp(20000)).thenReturn(103d);
        when(prices.getObservedAt(20000)).thenReturn(day.atTime(9,35,30));
    }
    @Test void oneOrbDecisionIsReusedWithoutSharingSubscriberRequests() {
        prepare();var at=day.atTime(9,35,30);
        var shared=new SharedMarketDecisions(Clock.fixed(at.atZone(StrategySupport.MARKET_ZONE).toInstant(),StrategySupport.MARKET_ZONE));
        org.springframework.test.util.ReflectionTestUtils.setField(strategy,"sharedDecisions",shared);
        request.setLots(1);assertThat(strategy.apply(request,at).getStatus()).isEqualTo("triggered");
        var other=new StrategyApplyRequest();other.setSymbol("NIFTY");other.setUserId(3L);other.setBrokerCredentialsId(4L);other.setLots(3);
        assertThat(strategy.apply(other,at).getStatus()).isEqualTo("triggered");
        verify(support).loadCandles(any());
        var orders=org.mockito.ArgumentCaptor.forClass(TriggerRequest.class);
        verify(support,times(2)).executeTriggeredTrade(orders.capture());
        assertThat(orders.getAllValues()).extracting(TriggerRequest::getUserId).containsExactly(1L,3L);
        assertThat(orders.getAllValues()).extracting(TriggerRequest::getQuantity).containsExactly(1,3);
        assertThat(orders.getAllValues().get(0)).isNotSameAs(orders.getAllValues().get(1));
    }
    @Test void oldBreakoutCannotBeReplayedLater() {
        prepare();
        assertThat(strategy.apply(request,day.atTime(10,0)).getMessage()).contains("No fresh ORB");
        verify(support,never()).executeTriggeredTrade(any());
    }
    @Test void freshBreakoutNearPlannedEntryCanExecute() {
        prepare();
        assertThat(strategy.apply(request,day.atTime(9,35,30)).getStatus()).isEqualTo("triggered");
        verify(support).executeTriggeredTrade(any());
    }
    @Test void movedPriceAndReturnInsideRangeCannotExecute() {
        prepare();
        for (double spot : new double[]{105,100}) {
            when(prices.getLtp(20000)).thenReturn(spot);
            assertThat(strategy.apply(request,day.atTime(9,35,30)).getMessage()).contains("entry skipped");
        }
        verify(support,never()).executeTriggeredTrade(any());
    }
    @Test void staleQuoteCannotExecute() {
        prepare(); when(prices.getObservedAt(20000)).thenReturn(day.atTime(9,34));
        assertThat(strategy.apply(request,day.atTime(9,35,30)).getMessage()).contains("fresh spot quote");
        verify(support,never()).executeTriggeredTrade(any());
    }
    @Test void signalOlderThanConfiguredDelayCannotExecute() {
        prepare();
        assertThat(strategy.apply(request,day.atTime(9,37,1)).getMessage()).contains("No fresh ORB");
        verify(support,never()).executeTriggeredTrade(any());
    }
    @Test void peUsesTheSameFreshnessAndPriceProtection() {
        prepare();
        var pe=new Orb915930PeStrategy(support,prices,new OrbStrategyProperties());
        when(support.loadCandles(any())).thenReturn(new CandleLoad(List.of(
                candle(9,15,99,100),candle(9,20,99,100),candle(9,25,99,100),
                candle(9,30,97,95),candle(9,35,95,95)),false,null));
        when(prices.getLtp(20000)).thenReturn(95d);
        assertThat(pe.apply(request,day.atTime(9,35,30)).getStatus()).isEqualTo("triggered");
        verify(support).executeTriggeredTrade(argThat(trigger -> "PE".equals(trigger.getOptionType()) && trigger.getEntryPrice()==95d));
        verify(support).warmUpSpotFeed(any());
        when(prices.getLtp(20000)).thenReturn(93d);
        assertThat(pe.apply(request,day.atTime(9,35,30)).getStatus()).isEqualTo("waiting");
        verify(support,times(1)).executeTriggeredTrade(any());
    }
    private StrategyCandle candle(int hour,int minute,double open,double close) {
        return new StrategyCandle(day,LocalTime.of(hour,minute),open,Math.max(open,close)+1,Math.min(open,close)-1,close,null);
    }
}
