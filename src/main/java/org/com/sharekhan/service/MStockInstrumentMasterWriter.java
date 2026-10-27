package org.com.sharekhan.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.MStockInstrumentEntity;
import org.com.sharekhan.repository.MStockInstrumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** Replace one complete download atomically; a failed batch keeps the previous master. */
@Service
@RequiredArgsConstructor
public class MStockInstrumentMasterWriter {
    private final MStockInstrumentRepository repository;
    @PersistenceContext private EntityManager entityManager;

    @Transactional
    public void replace(List<MStockInstrumentEntity> records) {
        if (records == null || records.isEmpty()) throw new IllegalArgumentException("Empty instrument master");
        repository.deleteAllInBatch();
        for (int from = 0; from < records.size(); from += 1000) {
            repository.saveAll(records.subList(from, Math.min(from + 1000, records.size())));
            repository.flush();
            entityManager.clear();
        }
    }
}
