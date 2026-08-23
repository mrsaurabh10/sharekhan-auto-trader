package org.com.sharekhan.strategy;

import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.MarketauxEntitySentimentEntity;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.repository.MarketauxEntitySentimentRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketauxSentimentSwingAtrStrategyTest {

    private final MarketauxEntitySentimentRepository sentiments = mock(MarketauxEntitySentimentRepository.class);
    private final StrategySupport support = mock(StrategySupport.class);
    private final MarketauxNewsSwingQualificationService qualificationService = mock(MarketauxNewsSwingQualificationService.class);
    private final MarketauxSentimentSwingAtrStrategy strategy = new MarketauxSentimentSwingAtrStrategy(
            sentiments, support, qualificationService);

    @Test
    void createsSingleThreeAtrTargetForDirectPositiveNewsAfterSwingQualification() {
        when(sentiments.findTop500ByTradingDateOrderByCollectedAtDesc(any())).thenReturn(List.of(
                sentiment("BLUESTARCO", "Blue Star Ltd", "Blue Star among F&O stocks with a sharp rise", .91, false)));
        ScriptMasterEntity spot = ScriptMasterEntity.builder().tradingSymbol("BLUESTARCO").exchange("NC").scripCode(123).build();
        when(support.resolveSpotScript("BLUESTARCO")).thenReturn(spot);
        when(support.mstockAvailabilityFailure(spot)).thenReturn(Optional.empty());
        Fno925EntryQualificationService.Signal signal = new Fno925EntryQualificationService.Signal(
                100d, 90d, 4d, 99d, 80d,
                new StrategyCandle(LocalDate.now(), LocalTime.of(10, 0), 99d, 101d, 98d, 100d, 1000L), "SWING_HIGH");
        when(qualificationService.qualify(any(), any(), any(), any())).thenReturn(
                Fno925EntryQualificationService.Qualification.qualified(signal));
        when(support.preferredFnoExpiry("BLUESTARCO", "CE")).thenReturn("28/08/2026");
        when(support.nearestStrike("BLUESTARCO", "CE", "28/08/2026", 100d)).thenReturn(100d);
        when(support.resolveFnoEntryContract(any(), any(), any(), any(), anyDouble())).thenReturn(
                new StrategySupport.FnoOptionContract("28/08/2026", 100d));
        when(support.createPendingTradeRequest(any())).thenReturn(TriggerTradeRequestEntity.builder().id(44L).build());
        when(support.roundPrice(anyDouble())).thenAnswer(invocation -> invocation.getArgument(0));

        StrategyApplyRequest request = new StrategyApplyRequest();
        request.setUserId(7L);
        var response = strategy.apply(request);

        assertThat(response.getStatus()).isEqualTo("triggered");
        org.mockito.ArgumentCaptor<TriggerRequest> trigger = org.mockito.ArgumentCaptor.forClass(TriggerRequest.class);
        verify(support).createPendingTradeRequest(trigger.capture());
        assertThat(trigger.getValue().getOptionType()).isEqualTo("CE");
        assertThat(trigger.getValue().getStopLoss()).isEqualTo(92d);
        assertThat(trigger.getValue().getTarget1()).isEqualTo(112d);
        assertThat(trigger.getValue().getTarget2()).isNull();
        assertThat(trigger.getValue().getTarget3()).isNull();
        assertThat(trigger.getValue().getUseSpotForEntry()).isTrue();
        assertThat(trigger.getValue().getUseSpotForSl()).isTrue();
        assertThat(trigger.getValue().getUseSpotForTarget()).isTrue();
    }

    @Test
    void rejectsBroadMarketNewsEvenWhenItsScoreIsHigh() {
        when(sentiments.findTop500ByTradingDateOrderByCollectedAtDesc(any())).thenReturn(List.of(
                sentiment("HDFCBANK", "HDFC Bank Ltd", "HDFC Bank shares in focus today", .95, true)));
        when(support.waiting(any(), any(), any())).thenAnswer(invocation -> org.com.sharekhan.dto.StrategyApplyResponse.builder()
                .status("waiting").message(invocation.getArgument(2)).build());

        var response = strategy.apply(new StrategyApplyRequest());

        assertThat(response.getStatus()).isEqualTo("waiting");
        verify(support, never()).createPendingTradeRequest(any());
    }

    private MarketauxEntitySentimentEntity sentiment(String symbol, String name, String title, double score, boolean broad) {
        return MarketauxEntitySentimentEntity.builder().entitySymbol(symbol).entityName(name).articleTitle(title)
                .sentimentScore(score).broadMarketArticle(broad).collectedAt(java.time.LocalDateTime.now()).build();
    }
}
