package org.com.sharekhan.strategy.straddle;
import jakarta.persistence.EntityManager;
import org.com.sharekhan.entity.SharedStraddleSignal;
import org.com.sharekhan.repository.SharedStraddleSignalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.function.Supplier;

@Service
public class SharedStraddleSignalStore {
    private final SharedStraddleSignalRepository repository;
    private final EntityManager em;
    private final TransactionTemplate insert;
    public SharedStraddleSignalStore(SharedStraddleSignalRepository repository, EntityManager em, PlatformTransactionManager manager) {
        this.repository=repository; this.em=em;
        insert=new TransactionTemplate(manager); insert.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    // Serializes market preparation only. No premium HTTP calls or account execution inside this lock.
    public synchronized SharedStraddleSignal loadOrCreate(String id, Supplier<SharedStraddleSignal> prepare) {
        var existing=repository.findById(id); if(existing.isPresent()) return existing.get();
        var signal=prepare.get();
        if(!id.equals(signal.getId())) throw new IllegalArgumentException("Shared straddle signal identity mismatch");
        try { insert.executeWithoutResult(tx -> {em.persist(signal);em.flush();}); }
        catch(RuntimeException conflict) {return repository.findById(id).orElseThrow(() -> conflict);}
        return repository.findById(id).orElseThrow();
    }
}
