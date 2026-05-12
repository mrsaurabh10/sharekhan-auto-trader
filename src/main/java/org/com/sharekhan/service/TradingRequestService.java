package org.com.sharekhan.service;

import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.enums.Broker;
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
        return getRecentRequestsForUser(userId, "own");
    }

    public List<TriggerTradeRequestEntity> getRecentRequestsForUser(Long userId, String scope) {
        if (isSimulatorScope(scope)) {
            return repo.findTop10BySimulatorOrderByIdDesc(Broker.SIMULATOR.getDisplayName());
        }
        if (userId == null) return getRecentRequests();
        if (isUserScope(scope)) {
            return repo.findTop10ByAppUserIdOrderByIdDesc(userId);
        }
        if (isAllScope(scope)) {
            return repo.findTop10ByAppUserIdOrSimulatorOrderByIdDesc(userId, Broker.SIMULATOR.getDisplayName());
        }
        return repo.findTop10ByAppUserIdExcludingSimulatorOrderByIdDesc(userId, Broker.SIMULATOR.getDisplayName());
    }

    public Page<TriggerTradeRequestEntity> getRequestsForUser(Long userId, Pageable pageable) {
        return getRequestsForUser(userId, pageable, "own");
    }

    public Page<TriggerTradeRequestEntity> getRequestsForUser(Long userId, Pageable pageable, String scope) {
        if (isSimulatorScope(scope)) {
            return repo.findRequestsOrderedForDashboardBySimulator(Broker.SIMULATOR.getDisplayName(), pageable);
        }
        if (userId == null) {
            return repo.findRequestsOrderedForDashboard(pageable);
        }
        if (isUserScope(scope)) {
            return repo.findRequestsOrderedForDashboardByAppUserId(userId, pageable);
        }
        if (isAllScope(scope)) {
            return repo.findRequestsOrderedForDashboardByAppUserIdOrSimulator(userId, Broker.SIMULATOR.getDisplayName(), pageable);
        }
        return repo.findRequestsOrderedForDashboardByAppUserIdExcludingSimulator(userId, Broker.SIMULATOR.getDisplayName(), pageable);
    }

    private boolean isSimulatorScope(String scope) {
        return scope != null && "simulator".equalsIgnoreCase(scope.trim());
    }

    private boolean isAllScope(String scope) {
        return scope != null && "all".equalsIgnoreCase(scope.trim());
    }

    private boolean isUserScope(String scope) {
        return scope != null && "user".equalsIgnoreCase(scope.trim());
    }
}
