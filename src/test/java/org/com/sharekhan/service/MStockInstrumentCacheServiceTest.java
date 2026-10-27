package org.com.sharekhan.service;

import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.config.MStockProperties;
import org.com.sharekhan.repository.MStockInstrumentRepository;
import org.com.sharekhan.util.CryptoService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MStockInstrumentCacheServiceTest {

    @Test
    void startupRefreshesLegacyMasterWithoutExchangeTokens() {
        MStockInstrumentRepository repository = mock(MStockInstrumentRepository.class);
        MStockInstrumentCacheService service = spy(new MStockInstrumentCacheService(
                repository,
                mock(TokenStoreService.class),
                mock(CryptoService.class),
                mock(MStockProperties.class), mock(MStockInstrumentMasterWriter.class)));
        when(repository.count()).thenReturn(50_000L);
        when(repository.existsByExchangeTokenIsNotNull()).thenReturn(false);
        doReturn(true).when(service).refreshInstrumentMaster();

        assertThat(service.refreshInstrumentMasterIfEmpty()).isTrue();
        verify(service).refreshInstrumentMaster();
    }

    @Test
    void startupKeepsTodaysPopulatedMasterWithExchangeTokens() {
        MStockInstrumentRepository repository = mock(MStockInstrumentRepository.class);
        MStockInstrumentCacheService service = spy(new MStockInstrumentCacheService(
                repository,
                mock(TokenStoreService.class),
                mock(CryptoService.class),
                mock(MStockProperties.class), mock(MStockInstrumentMasterWriter.class)));
        when(repository.count()).thenReturn(50_000L);
        when(repository.existsByExchangeTokenIsNotNull()).thenReturn(true);
        when(repository.latestFetchTime()).thenReturn(java.time.LocalDateTime.now());

        assertThat(service.refreshInstrumentMasterIfEmpty()).isFalse();
        verify(service, never()).refreshInstrumentMaster();
    }

    @Test
    void populatedAugustMasterMustRefreshForNovemberContracts() {
        var repository = mock(MStockInstrumentRepository.class);
        var writer = mock(MStockInstrumentMasterWriter.class);
        var service = spy(new MStockInstrumentCacheService(repository, mock(TokenStoreService.class),
                mock(CryptoService.class), mock(MStockProperties.class), writer));
        when(repository.count()).thenReturn(50000L);
        when(repository.existsByExchangeTokenIsNotNull()).thenReturn(true);
        when(repository.latestFetchTime()).thenReturn(java.time.LocalDateTime.of(2026, 8, 13, 23, 9));
        doReturn(true).when(service).refreshInstrumentMaster();
        assertThat(service.refreshInstrumentMasterIfEmpty()).isTrue();
        verify(service).refreshInstrumentMaster();
    }

    @Test
    void failedDownloadNeverReplacesExistingMaster() {
        var writer = mock(MStockInstrumentMasterWriter.class);
        var service = new MStockInstrumentCacheService(mock(MStockInstrumentRepository.class),
                mock(TokenStoreService.class), mock(CryptoService.class), mock(MStockProperties.class), writer);
        assertThat(service.refreshInstrumentMaster()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(writer);
    }

}
