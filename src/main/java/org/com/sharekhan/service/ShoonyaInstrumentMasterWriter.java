package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.ShoonyaInstrumentEntity;
import org.com.sharekhan.repository.ShoonyaInstrumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Performs the destructive replace as one transaction, after a master has been downloaded and parsed. */
@Service
@RequiredArgsConstructor
class ShoonyaInstrumentMasterWriter {
    private final ShoonyaInstrumentRepository repository;

    @Transactional
    public void replace(String exchange, List<ShoonyaInstrumentEntity> instruments) {
        repository.deleteByExchangeIgnoreCase(exchange);
        repository.saveAll(instruments);
    }
}
