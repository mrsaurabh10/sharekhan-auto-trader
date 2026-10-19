package org.com.sharekhan.strategy.straddle;

import jakarta.persistence.EntityManager;
import org.com.sharekhan.entity.ExpiryStraddleSession;
import org.com.sharekhan.repository.ExpiryStraddleSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ExpiryStraddleSessionStore {
    private final ExpiryStraddleSessionRepository repository;
    private final EntityManager em;
    private final TransactionTemplate transaction;
    public ExpiryStraddleSessionStore(ExpiryStraddleSessionRepository repository,EntityManager em,
                                      PlatformTransactionManager manager) {
        this.repository=repository; this.em=em;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public boolean claim(ExpiryStraddleSession session) {
        if(repository.existsById(session.getId())) return false;
        try {
            transaction.executeWithoutResult(status -> { em.persist(session); em.flush(); });
            return true;
        } catch(RuntimeException conflict) {
            if(repository.existsById(session.getId())) return false;
            throw conflict;
        }
    }
}
