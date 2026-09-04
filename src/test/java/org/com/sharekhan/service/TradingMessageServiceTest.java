package org.com.sharekhan.service;

import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradingMessageServiceTest {

    @Test
    void appliesDuplicateProtectionToSharekhanAndStockBazaariSources() {
        assertTrue(TradingMessageService.isDuplicateProtectedSource("Sharekhan"));
        assertTrue(TradingMessageService.isDuplicateProtectedSource("stockbazaari"));
        assertFalse(TradingMessageService.isDuplicateProtectedSource("telegram"));
    }

    @Test
    void defaultsStockBazaariToThreeLotsAndHonorsPerSourceOverride() {
        TradingMessageService service = new TradingMessageService();
        UserConfigService configService = mock(UserConfigService.class);
        ReflectionTestUtils.setField(service, "userConfigService", configService);

        TriggerRequest defaultRequest = stockBazaariRequest();
        ReflectionTestUtils.invokeMethod(service, "applySourceDefaultLots", defaultRequest);
        assertThat(defaultRequest.getQuantity()).isEqualTo(3);
        assertThat(defaultRequest.getLots()).isEqualTo(3);

        when(configService.getConfig(7L, "source_default_lots.stockbazaari", null)).thenReturn("5");
        TriggerRequest configuredRequest = stockBazaariRequest();
        ReflectionTestUtils.invokeMethod(service, "applySourceDefaultLots", configuredRequest);
        assertThat(configuredRequest.getQuantity()).isEqualTo(5);
        assertThat(configuredRequest.getLots()).isEqualTo(5);
    }

    @Test
    void keepsExplicitLotsFromStockBazaariSignal() {
        TradingMessageService service = new TradingMessageService();
        TriggerRequest request = stockBazaariRequest();
        request.setQuantity(2);

        ReflectionTestUtils.invokeMethod(service, "applySourceDefaultLots", request);

        assertThat(request.getQuantity()).isEqualTo(2);
        assertThat(request.getLots()).isNull();
    }

    @Test
    void sizesEnabledStockBazaariEquityFromConfiguredAmount() {
        TradingMessageService service = new TradingMessageService();
        UserConfigService configService = mock(UserConfigService.class);
        ReflectionTestUtils.setField(service, "userConfigService", configService);
        when(configService.getConfig(7L, "stockbazaari.equity_enabled", "false")).thenReturn("true");
        when(configService.getConfig(7L, "stockbazaari.equity_amount", null)).thenReturn("25000");

        TriggerRequest request = stockBazaariEquityRequest();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service,
                "applyStockBazaariEquityConfiguration", request)).isTrue();
        assertThat(request.getQuantity()).isEqualTo(17);
        assertThat(request.getExchange()).isEqualTo("NC");
        assertThat(request.getLots()).isNull();
        assertThat(request.getIntraday()).isFalse();
        assertThat(request.getTslEnabled()).isTrue();
    }

    @Test
    void skipsStockBazaariEquityUntilExplicitlyEnabled() {
        TradingMessageService service = new TradingMessageService();
        UserConfigService configService = mock(UserConfigService.class);
        ReflectionTestUtils.setField(service, "userConfigService", configService);
        when(configService.getConfig(7L, "stockbazaari.equity_enabled", "false")).thenReturn("false");
        when(configService.getConfig(7L, "stockbazaari.equity_amount", null)).thenReturn("25000");

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service,
                "applyStockBazaariEquityConfiguration", stockBazaariEquityRequest())).isFalse();
    }

    @Test
    void mapsStockBazaariEquityAsDeliveryWithTsl() {
        TradingMessageService service = new TradingMessageService();
        TriggerRequest request = ReflectionTestUtils.invokeMethod(service, "mapToTriggerRequest", Map.of(
                "symbol", "RELIANCE",
                "exchange", "NC",
                "entry", 1450.0,
                "intraday", false,
                "tslEnabled", true));

        assertThat(request.getIntraday()).isFalse();
        assertThat(request.getTslEnabled()).isTrue();
    }

    @Test
    void mapsAwrSourceSoItsPerUserConfigurationIsUsed() {
        TradingMessageService service = new TradingMessageService();

        TriggerRequest request = ReflectionTestUtils.invokeMethod(service, "mapToTriggerRequest", Map.of(
                "symbol", "ASIANPAINT",
                "source", "awr",
                "entry", 56.0));

        assertThat(request.getSource()).isEqualTo("awr");
    }

    @Test
    void canonicalizesLowercaseStockBazaariApiSource() {
        TradingMessageService service = new TradingMessageService();
        TriggerRequest request = stockBazaariEquityRequest();
        request.setSource("stockbazaari");

        ReflectionTestUtils.invokeMethod(service, "canonicalizeKnownSource", request);

        assertThat(request.getSource()).isEqualTo("StockBazaari");
    }

    @Test
    void blocksSameDayStockBazaariSignalForAllUsersBeforeFanOut() {
        TradingMessageService service = new TradingMessageService();
        TriggerTradeRequestRepository requests = mock(TriggerTradeRequestRepository.class);
        ReflectionTestUtils.setField(service, "triggerTradeRequestRepository", requests);
        when(requests.countBySourceSymbolAndOptionTypeCreatedBetween(
                org.mockito.ArgumentMatchers.eq("StockBazaari"),
                org.mockito.ArgumentMatchers.eq("PNBHOUSING"),
                org.mockito.ArgumentMatchers.eq("CE"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class))).thenReturn(1L);

        Boolean duplicate = ReflectionTestUtils.invokeMethod(service,
                "isSameDayStockBazaariDuplicate", stockBazaariOptionRequest());

        assertThat(duplicate).isTrue();
    }

    @Test
    void reservesNewSameDayStockBazaariSignalSoConcurrentDeliveryCannotFanOutTwice() {
        TradingMessageService service = new TradingMessageService();
        TriggerTradeRequestRepository requests = mock(TriggerTradeRequestRepository.class);
        ReflectionTestUtils.setField(service, "triggerTradeRequestRepository", requests);

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service,
                "isSameDayStockBazaariDuplicate", stockBazaariOptionRequest())).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service,
                "isSameDayStockBazaariDuplicate", stockBazaariOptionRequest())).isTrue();
    }

    @Test
    void resolvesSourceConfigurationUsingNormalizedKey() {
        TradingMessageService service = new TradingMessageService();
        UserConfigService configService = mock(UserConfigService.class);
        ReflectionTestUtils.setField(service, "userConfigService", configService);
        when(configService.getConfig(7L, "stockbazaari", "false")).thenReturn("true");

        boolean enabled = ReflectionTestUtils.invokeMethod(service,
                "isSourceEnabledForUser", 7L, "StockBazaari");

        assertThat(enabled).isTrue();
        verify(configService).getConfig(7L, "stockbazaari", "false");
    }

    private TriggerRequest stockBazaariRequest() {
        TriggerRequest request = new TriggerRequest();
        request.setUserId(7L);
        request.setSource("StockBazaari");
        return request;
    }

    private TriggerRequest stockBazaariEquityRequest() {
        TriggerRequest request = stockBazaariRequest();
        request.setExchange("NSE");
        request.setEntryPrice(1450.0);
        request.setIntraday(true);
        return request;
    }

    private TriggerRequest stockBazaariOptionRequest() {
        TriggerRequest request = stockBazaariRequest();
        request.setInstrument("PNBHOUSING");
        request.setOptionType("CE");
        request.setStrikePrice(1140d);
        request.setExpiry("25/08/2026");
        return request;
    }
}
