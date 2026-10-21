package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.ExitChaseControl;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.repository.ExitChaseControlRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class ExitChaseControlService {
    private final ExitChaseControlRepository controls;
    private final TriggeredTradeSetupRepository trades;
    private final org.springframework.transaction.PlatformTransactionManager transactions;

    private <T> T independently(java.util.function.Supplier<T> action) {
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status->action.get());
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public ExitChaseControl begin(TriggeredTradeSetupEntity trade,Instant proposedStart) {
        ExitChaseControl control=controls.findById(trade.getId()).orElseGet(ExitChaseControl::new);
        if(Objects.equals(control.getOrderId(),trade.getExitOrderId()) && control.getStartedAt()!=null) {
            boolean protectiveTransition=("STOP_LOSS_HIT".equals(trade.getExitReason()) || "INTRADAY_CLOSE".equals(trade.getExitReason()))
                    && !Objects.equals(control.getIntent(),trade.getExitReason());
            if(!protectiveTransition)return control;
        }
        control.setTradeId(trade.getId());control.setOrderId(trade.getExitOrderId());
        control.setIntent(trade.getExitReason());control.setStartedAt(proposedStart);control.setActive(true);
        control.setAttention(null);control.setNotificationAttempts(0);control.setNotificationDelivered(false);
        control.setNextNotificationAt(null);
        return controls.saveAndFlush(control);
    }
    public void accepted(TriggeredTradeSetupEntity trade) {
        independently(()->{
            controls.findById(trade.getId()).ifPresent(c->{c.setAttention(null);c.setNotificationDelivered(true);controls.save(c);});
            return null;
        });
        // Join any caller transaction when touching its trade row; never wait on a row it already locked.
        trades.clearExitAttention(trade.getId());
        if(java.util.Set.of("EXIT_RECONCILIATION_REQUIRED","EXIT_BROKER_ORDER_INACTIVE").contains(java.util.Objects.toString(trade.getReason(),"")))trade.setReason(null);
    }
    @Transactional(readOnly=true)
    public List<ExitChaseControl> active(){return controls.findByActiveTrue();}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void complete(Long trade,String order) {
        controls.findById(trade).filter(c->Objects.equals(c.getOrderId(),order)).ifPresent(c->{
            c.setActive(false);c.setAttention(null);c.setNotificationDelivered(true);controls.save(c);
        });
    }
    public void attention(TriggeredTradeSetupEntity trade,String detail,Instant now) {
        boolean changed=independently(()->{
            ExitChaseControl c=controls.findById(trade.getId()).orElseGet(ExitChaseControl::new);
            c.setTradeId(trade.getId());c.setOrderId(trade.getExitOrderId());c.setActive(false);
            boolean different=!Objects.equals(c.getAttention(),detail);
            if(different) {
                c.setAttention(detail);c.setNotificationAttempts(0);c.setNotificationDelivered(false);c.setNextNotificationAt(now);
            }
            controls.saveAndFlush(c);return different;
        });
        if(changed)trades.markExitAttention(trade.getId(),detail);
    }
    /** Delivery bookkeeping is committed independently of order execution. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public List<ExitChaseControl> dueAlerts(Instant now) {
        List<ExitChaseControl> due=new ArrayList<>();
        for(var c:controls.findByAttentionIsNotNullAndNotificationDeliveredFalse()) {
            if(c.getNotificationAttempts()>=8 || (c.getNextNotificationAt()!=null && c.getNextNotificationAt().isAfter(now)))continue;
            c.setNotificationAttempts(c.getNotificationAttempts()+1);
            c.setNextNotificationAt(now.plusSeconds(Math.min(3600,30L << Math.min(c.getNotificationAttempts(),6))));
            controls.save(c);due.add(c);
        }
        return due;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void delivered(Long trade,String detail) {
        controls.findById(trade).filter(c->Objects.equals(c.getAttention(),detail)).ifPresent(c->{c.setNotificationDelivered(true);controls.save(c);});
    }
}
