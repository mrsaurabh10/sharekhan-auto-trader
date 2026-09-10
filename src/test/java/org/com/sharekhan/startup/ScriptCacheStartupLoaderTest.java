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
        when(repository.findDistinctExchanges()).thenReturn(java.util.List.of("NF", "NC", "BF", "BC", "MX"));
        new ScriptCacheStartupLoader(service, repository).loadScriptsIfEmpty();
        verifyNoInteractions(service);
    }
    @Test
    void broker503DoesNotAbortStartupOrOtherExchangesAndRetriesOnlyFailedExchange() throws Throwable {
        var repository = mock(ScriptMasterRepository.class);
        var service = mock(ScriptMasterCacheService.class);
        doThrow(new com.sharekhan.http.exceptions.SharekhanAPIException("HTTP 503"))
                .doReturn(java.util.Map.of()).when(service).getScriptCache("NF");
        var loader = new ScriptCacheStartupLoader(service, repository);
        loader.loadScriptsIfEmpty();
        for (String exchange : new String[]{"NC", "BF", "BC", "MX"}) verify(service).getScriptCache(exchange);
        loader.retryPendingExchanges();
        loader.retryPendingExchanges();
        verify(service, times(2)).getScriptCache("NF");
        for (String exchange : new String[]{"NC", "BF", "BC", "MX"}) verify(service, times(1)).getScriptCache(exchange);
    }

    @Test
    void fillsMissingExchangesAfterPartialPreviousStartup() throws Throwable {
        var repository = mock(ScriptMasterRepository.class);
        var service = mock(ScriptMasterCacheService.class);
        when(repository.count()).thenReturn(5520L);
        when(repository.findDistinctExchanges()).thenReturn(java.util.List.of("NC", "BF", "BC", "MX"));
        new ScriptCacheStartupLoader(service, repository).loadScriptsIfEmpty();
        verify(service).getScriptCache("NF");
        verify(service, never()).getScriptCache("NC");
    }

    @Test
    void waitsForApplicationReadyBeforeScheduledRetry() {
        var repository = mock(ScriptMasterRepository.class);
        var service = mock(ScriptMasterCacheService.class);
        new ScriptCacheStartupLoader(service, repository).retryPendingExchanges();
        verifyNoInteractions(repository, service);
    }
}
