package org.com.sharekhan.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingMessageServiceTest {

    @Test
    void appliesDuplicateProtectionToSharekhanAndStockBazaariSources() {
        assertTrue(TradingMessageService.isDuplicateProtectedSource("Sharekhan"));
        assertTrue(TradingMessageService.isDuplicateProtectedSource("stockbazaari"));
        assertFalse(TradingMessageService.isDuplicateProtectedSource("telegram"));
    }
}
