package org.com.sharekhan.service;

import org.com.sharekhan.dto.TriggerRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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

    private TriggerRequest stockBazaariRequest() {
        TriggerRequest request = new TriggerRequest();
        request.setUserId(7L);
        request.setSource("StockBazaari");
        return request;
    }
}
