package org.com.sharekhan.service;

import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TradingRequestService {
    @Autowired
    private TriggerTradeRequestRepository repo;

    public List<TriggerTradeRequestEntity> getRecentRequests() {
        return repo.findTop10ByOrderByIdDesc();
    }

    public List<TriggerTradeRequestEntity> getRecentRequestsForUser(Long userId) {
        if (userId == null) return getRecentRequests();
        return repo.findTop10ByCustomerIdOrderByIdDesc(userId);
    }
}