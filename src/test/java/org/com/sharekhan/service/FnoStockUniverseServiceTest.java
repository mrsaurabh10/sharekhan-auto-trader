package org.com.sharekhan.service;

import org.com.sharekhan.repository.ScriptMasterRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FnoStockUniverseServiceTest {

    @Test
    void acceptsOnlyStockOptionUnderlyingsAndCanonicalizesProviderSuffixes() {
        ScriptMasterRepository repository = mock(ScriptMasterRepository.class);
        when(repository.findDistinctOptionStockUnderlyingSymbols()).thenReturn(List.of("BHARTIARTL", "HCLTECH"));
        FnoStockUniverseService service = new FnoStockUniverseService(repository);

        assertEquals("BHARTIARTL", service.resolveFnoStockUnderlying("BHARTIARTL.NS").orElseThrow());
        assertEquals("HCLTECH", service.resolveFnoStockUnderlying("HCLTECH-BL.NS").orElseThrow());
        assertTrue(service.resolveFnoStockUnderlying("^NSEI").isEmpty());
        assertTrue(service.resolveFnoStockUnderlying("NONFNO.NS").isEmpty());
    }
}
