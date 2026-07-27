package org.com.sharekhan.strategy;

import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.MStockInstrumentEntity;
import org.com.sharekhan.repository.MStockInstrumentRepository;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.service.MStockInstrumentResolver;
import org.com.sharekhan.service.MStockIntradayCandleService;
import org.com.sharekhan.service.SharekhanHistoricalService;
import org.com.sharekhan.service.TradeExecutionService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StrategySupportTest {

    @Test
    void combinesMStockWithSharekhanHistoryWhenIntradayCandlesAreInsufficient() {
        MStockIntradayCandleService mStockIntradayCandleService = mock(MStockIntradayCandleService.class);
        SharekhanHistoricalService sharekhanHistoricalService = mock(SharekhanHistoricalService.class);
        StrategySupport support = support(mStockIntradayCandleService, sharekhanHistoricalService);

        ScriptMasterEntity spotScript = spotScript("NIFTY", "NC", 20000);
        LocalDate today = LocalDate.now(StrategySupport.MARKET_ZONE);
        LocalDate previousDay = today.minusDays(1);

        when(mStockIntradayCandleService.getIntradayCandles("NSE", "26000", "5minute"))
                .thenReturn(List.of(
                        new MStockIntradayCandleService.IntradayCandle(today, LocalTime.of(9, 20), 101, 103, 100, 102, 1_000L),
                        new MStockIntradayCandleService.IntradayCandle(today, LocalTime.of(9, 25), 102, 104, 101, 103, 1_200L)
                ));
        when(sharekhanHistoricalService.getHistoricalCandles(eq(20000), eq("5minute"), any(), eq(today)))
                .thenReturn(List.of(
                        new SharekhanHistoricalService.HistoricalCandle(previousDay, LocalTime.of(15, 20), 95, 96, 94, 95.5),
                        new SharekhanHistoricalService.HistoricalCandle(today, LocalTime.of(9, 20), 99, 100, 98, 99.5)
                ));

        CandleLoad result = support.loadCandlesWithHistoricalFallback(spotScript, 3);

        assertThat(result.candles()).hasSize(3);
        assertThat(result.candles().get(0).date()).isEqualTo(previousDay);
        assertThat(result.candles().get(2).time()).isEqualTo(LocalTime.of(9, 25));
        assertThat(result.candles().get(1).close()).isEqualTo(102); // overlapping timestamp prefers MStock
        assertThat(result.hasVolume()).isTrue();
    }

    @Test
    void skipsSharekhanFallbackWhenMStockAlreadyHasEnoughCandles() {
        MStockIntradayCandleService mStockIntradayCandleService = mock(MStockIntradayCandleService.class);
        SharekhanHistoricalService sharekhanHistoricalService = mock(SharekhanHistoricalService.class);
        StrategySupport support = support(mStockIntradayCandleService, sharekhanHistoricalService);

        ScriptMasterEntity spotScript = spotScript("NIFTY", "NC", 20000);
        LocalDate today = LocalDate.now(StrategySupport.MARKET_ZONE);

        when(mStockIntradayCandleService.getIntradayCandles("NSE", "26000", "5minute"))
                .thenReturn(List.of(
                        new MStockIntradayCandleService.IntradayCandle(today, LocalTime.of(9, 20), 100, 101, 99, 100.5, 1_000L),
                        new MStockIntradayCandleService.IntradayCandle(today, LocalTime.of(9, 25), 101, 102, 100, 101.5, 1_000L),
                        new MStockIntradayCandleService.IntradayCandle(today, LocalTime.of(9, 30), 102, 103, 101, 102.5, 1_000L)
                ));

        CandleLoad result = support.loadCandlesWithHistoricalFallback(spotScript, 3);

        assertThat(result.candles()).hasSize(3);
        verify(sharekhanHistoricalService, never()).getHistoricalCandles(any(), any(), any(), any());
    }

    @Test
    void usesBseEquityFallbackWhenNseInstrumentMasterRowIsMissing() {
        MStockInstrumentResolver resolver = mock(MStockInstrumentResolver.class);
        MStockInstrumentRepository instrumentRepository = mock(MStockInstrumentRepository.class);
        MStockIntradayCandleService intraday = mock(MStockIntradayCandleService.class);
        StrategySupport support = new StrategySupport(
                mock(ScriptMasterRepository.class), resolver, instrumentRepository, intraday,
                mock(SharekhanHistoricalService.class), mock(TradeExecutionService.class),
                mock(TriggerTradeRequestRepository.class));
        ScriptMasterEntity kotak = spotScript("KOTAKBANK", "NC", 1922);
        MStockInstrumentEntity bseKotak = MStockInstrumentEntity.builder()
                .instrumentToken(500247L)
                .instrumentKey("BSE:KOTAKBANK-A")
                .tradingSymbol("KOTAKBANK-A")
                .exchange("BSE")
                .instrumentType("Equity")
                .build();

        when(resolver.resolveInstrumentKey(kotak)).thenReturn(Optional.of("NSE:KOTAKBANK-EQ"));
        when(instrumentRepository.findByInstrumentKey("NSE:KOTAKBANK-EQ")).thenReturn(Optional.empty());
        when(instrumentRepository.findByExchangeAndTradingSymbolPattern("BSE", "KOTAKBANK%"))
                .thenReturn(List.of(bseKotak));
        when(intraday.getIntradayCandles("BSE", "500247", "5minute")).thenReturn(List.of());

        assertThat(support.mstockAvailabilityFailure(kotak)).isEmpty();
        CandleLoad result = support.loadCandles(kotak);

        assertThat(result.reason()).contains("BSE:KOTAKBANK-A");
        verify(intraday).getIntradayCandles("BSE", "500247", "5minute");
    }

    @Test
    void fnoExpirySkipsExpiryWithinThreeCalendarDays() {
        ScriptMasterRepository repository = mock(ScriptMasterRepository.class);
        StrategySupport support = new StrategySupport(
                repository, mock(MStockInstrumentResolver.class), mock(MStockInstrumentRepository.class),
                mock(MStockIntradayCandleService.class), mock(SharekhanHistoricalService.class),
                mock(TradeExecutionService.class), mock(TriggerTradeRequestRepository.class));
        LocalDate tradeDate = LocalDate.of(2026, 7, 27);
        when(repository.findAllOptionExpiriesByTradingSymbolAndOptionType("RELIANCE", "CE"))
                .thenReturn(List.of(
                        tradeDate.plusDays(1).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu")),
                        tradeDate.plusDays(3).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu")),
                        tradeDate.plusDays(8).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu"))));

        assertThat(support.preferredFnoExpiry("RELIANCE", "CE", tradeDate)).isEqualTo("04/08/2026");
    }

    @Test
    void warmsSpotAndPreferredExpiryAtmOptionForManualFnoMonitoring() {
        ScriptMasterRepository repository = mock(ScriptMasterRepository.class);
        MStockInstrumentResolver resolver = mock(MStockInstrumentResolver.class);
        MStockInstrumentRepository instrumentRepository = mock(MStockInstrumentRepository.class);
        MStockIntradayCandleService intraday = mock(MStockIntradayCandleService.class);
        TradeExecutionService execution = mock(TradeExecutionService.class);
        StrategySupport support = new StrategySupport(
                repository, resolver, instrumentRepository, intraday,
                mock(SharekhanHistoricalService.class), execution, mock(TriggerTradeRequestRepository.class));
        ScriptMasterEntity spot = spotScript("360ONE", "NC", 13061);
        MStockInstrumentEntity instrument = MStockInstrumentEntity.builder()
                .instrumentToken(13061L).instrumentKey("NSE:360ONE-EQ")
                .exchangeToken("13061").tradingSymbol("360ONE-EQ")
                .exchange("NSE").instrumentType("Equity").build();
        LocalDate today = LocalDate.now(StrategySupport.MARKET_ZONE);
        String expiry = today.plusDays(8).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu"));
        when(resolver.resolveInstrumentKey(spot)).thenReturn(Optional.of("NSE:360ONE-EQ"));
        when(instrumentRepository.findByInstrumentKey("NSE:360ONE-EQ")).thenReturn(Optional.of(instrument));
        when(intraday.getIntradayCandles("NSE", "13061", "5minute"))
                .thenReturn(List.of(new MStockIntradayCandleService.IntradayCandle(
                        today, LocalTime.of(9, 25), 1120, 1124, 1119, 1123, 1_000L)));
        when(repository.findAllOptionExpiriesByTradingSymbolAndOptionType("360ONE", "CE"))
                .thenReturn(List.of(expiry));
        when(repository.findStrikePricesByTradingSymbolAndOptionTypeAndExpiry("360ONE", "CE", expiry))
                .thenReturn(List.of(1120d, 1140d));
        when(execution.warmUpOptionLtp(any(), eq("F&O strategy monitoring"))).thenReturn(Optional.of(66826));

        StrategyApplyRequest request = new StrategyApplyRequest();
        request.setUserId(1L);
        request.setBrokerCredentialsId(2L);
        support.warmUpPreferredFnoFeeds(request, new StrategyMetadata("FNO", "FNO", "", "CE"), "360ONE", spot);

        verify(execution).warmUpSpotLtp(spot, "F&O strategy monitoring");
        verify(execution).warmUpOptionLtp(org.mockito.ArgumentMatchers.argThat(trigger ->
                        "360ONE".equals(trigger.getInstrument())
                                && Double.valueOf(1120d).equals(trigger.getStrikePrice())
                                && expiry.equals(trigger.getExpiry())),
                eq("F&O strategy monitoring"));
    }

    @Test
    void fallsBackToThePrewarmedStrikeWhenTheNewAtmBookIsUnavailable() {
        ScriptMasterRepository repository = mock(ScriptMasterRepository.class);
        MStockInstrumentResolver resolver = mock(MStockInstrumentResolver.class);
        MStockInstrumentRepository instrumentRepository = mock(MStockInstrumentRepository.class);
        MStockIntradayCandleService intraday = mock(MStockIntradayCandleService.class);
        TradeExecutionService execution = mock(TradeExecutionService.class);
        StrategySupport support = new StrategySupport(repository, resolver, instrumentRepository, intraday,
                mock(SharekhanHistoricalService.class), execution, mock(TriggerTradeRequestRepository.class));
        ScriptMasterEntity spot = spotScript("360ONE", "NC", 13061);
        MStockInstrumentEntity instrument = MStockInstrumentEntity.builder().instrumentToken(13061L)
                .exchangeToken("13061").instrumentKey("NSE:360ONE-EQ").tradingSymbol("360ONE-EQ")
                .exchange("NSE").instrumentType("Equity").build();
        LocalDate today = LocalDate.now(StrategySupport.MARKET_ZONE);
        String expiry = today.plusDays(8).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu"));
        when(resolver.resolveInstrumentKey(spot)).thenReturn(Optional.of("NSE:360ONE-EQ"));
        when(instrumentRepository.findByInstrumentKey("NSE:360ONE-EQ")).thenReturn(Optional.of(instrument));
        when(intraday.getIntradayCandles("NSE", "13061", "5minute"))
                .thenReturn(List.of(new MStockIntradayCandleService.IntradayCandle(
                        today, LocalTime.of(9, 25), 1120, 1124, 1119, 1123, 1_000L)));
        when(repository.findAllOptionExpiriesByTradingSymbolAndOptionType("360ONE", "CE")).thenReturn(List.of(expiry));
        when(repository.findStrikePricesByTradingSymbolAndOptionTypeAndExpiry("360ONE", "CE", expiry))
                .thenReturn(List.of(1120d, 1140d));
        when(execution.warmUpOptionLtp(any(), any())).thenReturn(Optional.of(66826));

        StrategyApplyRequest request = new StrategyApplyRequest();
        request.setUserId(1L);
        request.setBrokerCredentialsId(2L);
        StrategyMetadata metadata = new StrategyMetadata("FNO", "FNO", "", "CE");
        support.warmUpPreferredFnoFeeds(request, metadata, "360ONE", spot);

        StrategySupport.FnoOptionContract resolved = support.resolveFnoEntryContract(
                request, metadata, "360ONE", expiry, 1140d);

        assertThat(resolved).isEqualTo(new StrategySupport.FnoOptionContract(expiry, 1120d));
        verify(execution).hasFreshOptionBook(org.mockito.ArgumentMatchers.argThat(candidate ->
                Double.valueOf(1140d).equals(candidate.getStrikePrice())));
    }

    private StrategySupport support(MStockIntradayCandleService mStockIntradayCandleService,
                                    SharekhanHistoricalService sharekhanHistoricalService) {
        return new StrategySupport(
                mock(ScriptMasterRepository.class),
                mock(MStockInstrumentResolver.class),
                mock(MStockInstrumentRepository.class),
                mStockIntradayCandleService,
                sharekhanHistoricalService,
                mock(TradeExecutionService.class),
                mock(TriggerTradeRequestRepository.class)
        );
    }

    private ScriptMasterEntity spotScript(String symbol, String exchange, Integer scripCode) {
        return ScriptMasterEntity.builder()
                .tradingSymbol(symbol)
                .exchange(exchange)
                .scripCode(scripCode)
                .build();
    }
}
