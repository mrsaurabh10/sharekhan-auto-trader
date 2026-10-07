package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.TradeAuditEventEntity;
import org.com.sharekhan.audit.AuditEventStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class TradeAuditService {
    private final AuditEventStore auditEventStore;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(TradeAuditEventEntity event) {
        if (event == null) return;
        try {
            if (event.getOccurredAt() == null) event.setOccurredAt(LocalDateTime.now());
            auditEventStore.save(event);
        } catch (Exception e) {
            // Auditing must never prevent a strategy or protective execution path.
            log.warn("Unable to persist trade audit event type={} symbol={}: {}",
                    event.getEventType(), event.getSymbol(), e.getMessage());
        }
    }
}
