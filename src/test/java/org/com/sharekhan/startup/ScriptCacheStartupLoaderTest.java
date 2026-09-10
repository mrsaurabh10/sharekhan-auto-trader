package org.com.sharekhan.startup;

import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.service.ScriptMasterCacheService;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class ScriptCacheStartupLoaderTest {
    @Test
    void refreshesExistingMasterWhenTickSizesAreMissingWithoutDeletingRows() throws Throwable {
        var repository = mock(ScriptMasterRepository.class);
        var service = mock(ScriptMasterCacheService.class);
        when(repository.count()).thenReturn(5520L);
        when(repository.existsByTickSizeIsNull()).thenReturn(true);
        new ScriptCacheStartupLoader(service, repository).loadScriptsIfEmpty();
        for (String exchange : new String[]{"NF", "NC", "BF", "BC", "MX"}) verify(service).getScriptCache(exchange);
        verify(repository, never()).deleteAll();
        verify(repository, never()).deleteAllInBatch();
    }

    @Test
    void skipsFetchWhenTickSizesAreAlreadyPresent() {
        var repository = mock(ScriptMasterRepository.class);
        var service = mock(ScriptMasterCacheService.class);
        when(repository.count()).thenReturn(5520L);
        new ScriptCacheStartupLoader(service, repository).loadScriptsIfEmpty();
        verifyNoInteractions(service);
    }
}
