package org.com.sharekhan.service;

import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
       return repo.findTop10ByAppUserIdOrderByIdDesc(userId);
    }

    public Page<TriggerTradeRequestEntity> getRequestsForUser(Long userId, Pageable pageable) {
        if (userId == null) {
            return repo.findRequestsOrderedForDashboard(pageable);
        }
        return repo.findRequestsOrderedForDashboardByAppUserId(userId, pageable);
    }
}
