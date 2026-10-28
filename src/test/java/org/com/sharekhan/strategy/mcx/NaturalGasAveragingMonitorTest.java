package org.com.sharekhan.strategy.mcx;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.com.sharekhan.cache.QuoteCacheService;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.strategy.StrategySupport;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NaturalGasAveragingMonitorTest {
    final StrategyRunRepository runs=mock(StrategyRunRepository.class);
    final BrokerCredentialsRepository brokers=mock(BrokerCredentialsRepository.class);
    final TriggeredTradeSetupRepository trades=mock(TriggeredTradeSetupRepository.class);
    final ScriptMasterRepository scripts=mock(ScriptMasterRepository.class);
    final NaturalGasMarketData data=mock(NaturalGasMarketData.class);
    final QuoteCacheService quotes=mock(QuoteCacheService.class);
    final NaturalGasAveragingMonitor monitor=new NaturalGasAveragingMonitor(runs,brokers,trades,scripts,data,quotes);
    final LocalDateTime now=NaturalGasAveragingSignalsTest.NOW;
    StrategyRun run;
    TriggeredTradeSetupEntity leg;
    @BeforeEach void setup() {
        run=StrategyRun.builder().id("B1").appUserId(1L).brokerCredentialsId(2L).tradingDay(now.toLocalDate()).templateId(NaturalGasAveragingStrategy.TEMPLATE).status("OPEN").build();
        when(runs.findByTemplateIdAndStatusIn(NaturalGasAveragingStrategy.TEMPLATE,List.of("OPEN"))).thenReturn(List.of(run));
        when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(1L).brokerName("Simulator").build()));
        leg=NaturalGasAveragingBookTest.leg(1,"B1",20);leg.setEntryAt(now.minusMinutes(10));
        when(trades.findBySource(NaturalGasAveragingStrategy.SOURCE)).thenReturn(List.of(leg));
        var future=NaturalGasContractsTest.row(1,"FUTC",null,"24/11/2026",0);future.setLotSize(1250);future.setTickSize(.1);
        var option=NaturalGasContractsTest.row(42,"OPTC","CE","20/11/2026",295);option.setLotSize(1250);option.setTickSize(.05);
        when(scripts.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase("MX","NATURALGAS")).thenReturn(List.of(future,option));
        when(data.shoonyaCandles(future,now)).thenReturn(NaturalGasAveragingSignalsTest.bars(true));
        book(20,20.1);
    }
    void book(double bid,double ask) {
        when(quotes.getSnapshot(42)).thenReturn(Optional.of(QuoteCacheService.QuoteSnapshot.builder().scripCode(42).bestBid(bid).bestAsk(ask)
                .lastBookAt(now.atZone(StrategySupport.MARKET_ZONE).toInstant()).coherentBook(true).bookSource("SHAREKHAN_WS").bookProviderIdentity("scrip:42").build()));
    }
    @Test void exitsAtPaperNetTargetWithoutRequiringMasterOrTrendAvailability() {
        book(21.1,21.2);monitor.monitor(now);
        verify(runs).requestClose("B1","MCX_AVERAGING_BASKET_TARGET",now);verifyNoInteractions(scripts,data);
    }
    @Test void grossTargetAloneDoesNotCloseBeforeCostsAreRecovered() {
        book(21,21.1);monitor.monitor(now);verify(runs,never()).requestClose(anyString(),anyString(),any());
    }
    @Test void stopFloorAndDailyLossWorkEvenWithWideSpreadAndMissingMaster() {
        book(17.7,20);monitor.monitor(now);
        verify(runs).requestClose("B1","MCX_AVERAGING_DAILY_LOSS",now);verifyNoInteractions(scripts,data);
    }
    @Test void trendReversalRequestsWholeBasketCloseWithoutOptionBook() {
        when(quotes.getSnapshot(42)).thenReturn(Optional.empty());
        when(data.shoonyaCandles(any(),eq(now))).thenReturn(NaturalGasAveragingSignalsTest.bars(false));
        monitor.monitor(now);verify(runs).requestClose("B1","MCX_AVERAGING_TREND_REVERSED",now);
    }
    @Test void bullishReversalAlsoClosesBoughtPutBasket() {
        leg.setOptionType("PE");monitor.monitor(now);verify(runs).requestClose("B1","MCX_AVERAGING_TREND_REVERSED",now);
    }
    @Test void missingTrendOrMarksIsNotInventedAsProfitOrTrendReversal() {
        when(quotes.getSnapshot(42)).thenReturn(Optional.empty());when(data.shoonyaCandles(any(),eq(now))).thenReturn(List.of());
        monitor.monitor(now);verify(runs,never()).requestClose(anyString(),anyString(),any());
        verify(runs).recordMessage(eq("B1"),contains("unavailable"));
    }
    @Test void unknownFilledEntryClosesAdmissionAndEntersReconciliation() {
        leg.setReason("ENTRY_STATUS_UNCONFIRMED");monitor.monitor(now);
        verify(runs).requestClose("B1","MCX_AVERAGING_RECONCILIATION",now);
    }
    @Test void liveOrOtherOwnersRunNeverEntersSimulatorMonitor() {
        when(brokers.findById(2L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().appUserId(99L).brokerName("Sharekhan").build()));
        monitor.monitor(now);verifyNoInteractions(trades,quotes,scripts,data);
    }
}
