package org.com.sharekhan.strategy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.MoverSelectionSnapshot;
import org.com.sharekhan.repository.MoverSelectionSnapshotRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

@Slf4j
@Service
public class MoverSelectionSnapshotStore {
    private static final LocalTime OPENING = LocalTime.of(9,25);
    private static final LocalTime CAPTURE_DEADLINE = LocalTime.of(9,26);
    private final MoverSelectionSnapshotRepository repository;
    private final EntityManager em;
    private final TransactionTemplate insertTransaction;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public MoverSelectionSnapshotStore(MoverSelectionSnapshotRepository repository, EntityManager em,
                                       PlatformTransactionManager transactions, ObjectMapper mapper) {
        this(repository,em,transactions,mapper,Clock.system(StrategySupport.MARKET_ZONE));
    }
    MoverSelectionSnapshotStore(MoverSelectionSnapshotRepository repository, EntityManager em,
                                 PlatformTransactionManager transactions, ObjectMapper mapper, Clock clock) {
        this.repository=repository; this.em=em; this.mapper=mapper; this.clock=clock;
        insertTransaction=new TransactionTemplate(transactions);
        insertTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public synchronized Optional<List<Fno925Candidate>> loadOrCapture(String templateId, LocalDateTime started,
                                                                      Supplier<List<Fno925Candidate>> capture) {
        String id=templateId+"@"+started.toLocalDate();
        var existing=repository.findById(id);
        if(existing.isPresent()) return Optional.of(decode(existing.get()));
        // A restart or late activation cannot substitute current rankings for the opening scan.
        if(!inWindow(started)) return Optional.empty();
        List<Fno925Candidate> candidates=List.copyOf(capture.get());
        LocalDateTime finished=LocalDateTime.now(clock);
        if(!finished.toLocalDate().equals(started.toLocalDate()) || !inWindow(finished)) return Optional.empty();
        validate(candidates);
        var row=MoverSelectionSnapshot.builder().id(id).templateId(templateId)
            .tradingDay(started.toLocalDate()).captureStartedAt(started).capturedAt(finished)
            .schemaVersion(1).candidatesJson(encode(candidates)).build();
        try {
            // INSERT, never merge/upsert: another instance's committed selection must win.
            insertTransaction.executeWithoutResult(status -> { em.persist(row); em.flush(); });
        } catch(RuntimeException conflict) {
            var winner=repository.findById(id);
            if(winner.isEmpty()) throw conflict;
            return Optional.of(decode(winner.get()));
        }
        log.info("MOVER_OPENING_SELECTION_SAVED | template={} day={} started={} captured={} selected={}",
                templateId, started.toLocalDate(), started, finished,
                candidates.stream().map(c -> c.symbol() + ":" + c.optionType()).toList());
        return Optional.of(candidates);
    }
    private boolean inWindow(LocalDateTime time) {
        return !time.toLocalTime().isBefore(OPENING) && time.toLocalTime().isBefore(CAPTURE_DEADLINE);
    }
    private String encode(List<Fno925Candidate> candidates) {
        try { return mapper.writeValueAsString(candidates); }
        catch(Exception e) { throw new IllegalStateException("Cannot persist opening mover selection",e); }
    }
    private List<Fno925Candidate> decode(MoverSelectionSnapshot row) {
        if(row.getSchemaVersion()!=1) throw new IllegalStateException("Unsupported opening mover snapshot version");
        if(row.getCaptureStartedAt()==null || row.getCapturedAt()==null
            || !row.getTradingDay().equals(row.getCaptureStartedAt().toLocalDate())
            || !row.getTradingDay().equals(row.getCapturedAt().toLocalDate())
            || !inWindow(row.getCaptureStartedAt()) || !inWindow(row.getCapturedAt()))
            throw new IllegalStateException("Stored mover snapshot is not an opening selection; refusing to rescan");
        try {
            List<Fno925Candidate> candidates=mapper.readValue(row.getCandidatesJson(),new TypeReference<>(){});
            validate(candidates);
            return List.copyOf(candidates);
        } catch(Exception e) { throw new IllegalStateException("Stored opening mover selection is invalid; refusing to rescan",e); }
    }
    private void validate(List<Fno925Candidate> candidates) {
        if(candidates==null || candidates.size()>5) throw new IllegalArgumentException("Invalid opening selection size");
        Set<String> seen=new HashSet<>();
        for(var c:candidates) {
            if(c==null || c.symbol()==null || c.symbol().isBlank() || !seen.add(c.symbol())
                || !Set.of("CE","PE").contains(c.optionType()) || c.spot()==null
                || c.spot().getScripCode()==null || c.spot().getTradingSymbol()==null
                || !"NC".equalsIgnoreCase(c.spot().getExchange()))
                throw new IllegalArgumentException("Invalid opening candidate identity");
        }
    }
}
