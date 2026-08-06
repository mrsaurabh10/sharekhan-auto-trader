package org.com.sharekhan.service;

import org.com.sharekhan.entity.ShoonyaInstrumentEntity;
import org.com.sharekhan.repository.ShoonyaInstrumentRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class ShoonyaInstrumentMasterWriterTest {

    @Test
    void flushesTheDeletedExchangeBeforeInsertingItsReplacementRows() {
        ShoonyaInstrumentRepository repository = mock(ShoonyaInstrumentRepository.class);
        ShoonyaInstrumentMasterWriter writer = new ShoonyaInstrumentMasterWriter(repository);
        List<ShoonyaInstrumentEntity> instruments = List.of(ShoonyaInstrumentEntity.builder()
                .instrumentKey("NSE:NIFTY INDEX")
                .exchange("NSE")
                .tradingSymbol("NIFTY INDEX")
                .token("26000")
                .build());

        writer.replace("NSE", instruments);

        var calls = inOrder(repository);
        calls.verify(repository).deleteByExchangeIgnoreCase("NSE");
        calls.verify(repository).flush();
        calls.verify(repository).saveAll(instruments);
    }
}
