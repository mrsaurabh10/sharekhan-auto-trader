package org.com.sharekhan.service;
import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.BrokerExitSubmission;
import org.com.sharekhan.repository.BrokerExitSubmissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Instant;
@Service @RequiredArgsConstructor
public class BrokerExitSubmissionStore {
    private final BrokerExitSubmissionRepository repository;
    public record Claim(boolean send,String handle) {}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Claim claim(Long trade,Long credential) {
        var old=repository.findTopByTradeIdAndCredentialIdOrderByGenerationDesc(trade,credential);
        if(old.isPresent() && !old.get().isConfirmedInactive())return new Claim(false,
                old.get().getOrderId()!=null?old.get().getOrderId():old.get().getId());
        int generation=old.map(c->c.getGeneration()+1).orElse(1);
        var row=new BrokerExitSubmission();row.setTradeId(trade);row.setCredentialId(credential);
        row.setGeneration(generation);row.setCreatedAt(Instant.now());
        row.setId("SK-UNKNOWN-"+credential+"-"+trade+"-"+generation);
        // An explicit INSERT makes a concurrent claim fail instead of merging over another sender.
        repository.insertClaim(row.getId(),trade,credential,generation,row.getCreatedAt());
        return new Claim(true,row.getId());
    }
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
    public java.util.Optional<Claim> existing(Long trade,Long credential) {
        return repository.findTopByTradeIdAndCredentialIdOrderByGenerationDesc(trade,credential)
            .map(c->new Claim(false,c.getOrderId()!=null?c.getOrderId():c.getId()));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void accepted(String handle,String order) {
        var row=repository.findById(handle).orElseThrow();row.setOrderId(order);repository.saveAndFlush(row);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void inactive(Long trade,Long credential,String order) {
        repository.findTopByTradeIdAndCredentialIdOrderByGenerationDesc(trade,credential)
            .filter(c->order.equals(c.getOrderId())).ifPresent(c->{c.setConfirmedInactive(true);repository.saveAndFlush(c);});
    }
}
