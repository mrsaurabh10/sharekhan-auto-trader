package org.com.sharekhan.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.service.CurrentUserService;
import org.com.sharekhan.service.TradeExecutionService;
import org.com.sharekhan.service.TradeCostCalculator;
import org.com.sharekhan.service.TradingRequestService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderViewController {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Autowired
    private TradingRequestService tradingRequestService;
    @Autowired private TradeExecutionService tradeExecutionService;
    @Autowired private CurrentUserService currentUserService;
    @Autowired private BrokerCredentialsRepository brokerCredentialsRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TradeCostCalculator tradeCostCalculator;

    @GetMapping("/requests")
    public ResponseEntity<?> getRequestedOrders(@RequestParam(name = "userId", required = false) Long userId,
                                                @RequestParam(name = "page", required = false) Integer page,
                                                @RequestParam(name = "size", required = false) Integer size,
                                                @RequestParam(name = "scope", defaultValue = "own") String scope) {
        Long scopedUserId = currentUserService.scopedUserId(userId);
        String orderScope = normalizeOrderScopeForSession(scope);
        if (page == null && size == null) {
            return ResponseEntity.ok(tradingRequestService.getRecentRequestsForUser(scopedUserId, orderScope).stream()
                    .map(request -> enrichRequest(request, scopedUserId))
                    .toList());
        }
        int pageNumber = Math.max(page == null ? 0 : page, 0);
        int pageSize = Math.min(Math.max(size == null ? 10 : size, 1), 100);
        Pageable pageable = PageRequest.of(pageNumber, pageSize);
        Page<TriggerTradeRequestEntity> requests = tradingRequestService.getRequestsForUser(scopedUserId, pageable, orderScope);
        return ResponseEntity.ok(requests.map(request -> enrichRequest(request, scopedUserId)));
     }

    @GetMapping("/executed")
    public ResponseEntity<?> getExecuted(@RequestParam(name = "userId", required = false) Long userId,
                                         @RequestParam(name = "status", required = false) List<String> statuses,
                                         @RequestParam(name = "scope", defaultValue = "own") String scope,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(
                Sort.Order.desc("entryAt").nullsLast(),
                Sort.Order.desc("id")));
        Long scopedUserId = currentUserService.scopedUserId(userId);
        String orderScope = normalizeOrderScopeForSession(scope);
        Page<TriggeredTradeSetupEntity> executions = tradeExecutionService.getRecentExecutionsForUser(scopedUserId, statuses, pageable, orderScope);
        return ResponseEntity.ok(executions.map(trade -> enrichExecution(trade, scopedUserId)));
    }

    private Map<String, Object> enrichRequest(TriggerTradeRequestEntity request, Long scopedUserId) {
        Map<String, Object> row = objectMapper.convertValue(request, MAP_TYPE);
        addTradeScope(row, request.getBrokerCredentialsId(), request.getAppUserId(), scopedUserId);
        return row;
    }

    private Map<String, Object> enrichExecution(TriggeredTradeSetupEntity trade, Long scopedUserId) {
        Map<String, Object> row = objectMapper.convertValue(trade, MAP_TYPE);
        TradeCostCalculator.TradeCharges charges = tradeCostCalculator.calculate(trade);
        if (charges != null) {
            row.put("brokerage", charges.brokerage());
            row.put("stt", charges.stt());
            row.put("exchangeTransactionCharges", charges.exchangeTransactionCharges());
            row.put("stampCharges", charges.stampCharges());
            row.put("sebiTransactionFees", charges.sebiTransactionFees());
            row.put("gst", charges.gst());
            row.put("totalTradeCost", charges.totalTradeCost());
            row.put("effectivePnl", charges.effectivePnl());
            row.put("exercised", charges.exercised());
        }
        addTradeScope(row, trade.getBrokerCredentialsId(), trade.getAppUserId(), scopedUserId);
        return row;
    }

    private void addTradeScope(Map<String, Object> row, Long brokerCredentialsId, Long appUserId, Long scopedUserId) {
        Optional<BrokerCredentialsEntity> brokerCredentials = brokerCredentialsId == null
                ? Optional.empty()
                : brokerCredentialsRepository.findById(brokerCredentialsId);
        String brokerName = brokerCredentials.map(BrokerCredentialsEntity::getBrokerName).orElse(null);
        boolean simulator = brokerName != null && Broker.SIMULATOR.getDisplayName().equalsIgnoreCase(brokerName.trim());
        boolean ownTrade = scopedUserId != null && scopedUserId.equals(appUserId);
        String tradeScope = simulator ? "SIMULATOR" : (ownTrade ? "OWN" : "USER");

        row.put("brokerName", brokerName);
        row.put("simulator", simulator);
        row.put("tradeScope", tradeScope);
        row.put("tradeScopeLabel", simulator ? "Simulator" : "Own");
    }

    private String normalizeOrderScopeForSession(String scope) {
        String normalized = scope == null || scope.isBlank() ? "own" : scope.trim().toLowerCase();
        if (currentUserService.isAdmin() && "own".equals(normalized)) {
            return "user";
        }
        return normalized;
    }
}
