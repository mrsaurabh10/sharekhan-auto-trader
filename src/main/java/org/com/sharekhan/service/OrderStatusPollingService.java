package org.com.sharekhan.service;

import com.sharekhan.http.exceptions.SharekhanAPIException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.cache.LtpCacheService;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.service.broker.BrokerService;
import org.com.sharekhan.service.broker.BrokerServiceFactory;
import org.com.sharekhan.service.broker.OrderStatusBrokerService;
import org.com.sharekhan.ws.WebSocketClientService;
import org.com.sharekhan.ws.WebSocketSubscriptionHelper;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.util.function.Consumer;
import org.com.sharekhan.dhan.DhanOrderUpdateEvent;
import org.com.sharekhan.service.broker.OrderUpdateBrokerService;
import org.springframework.context.event.EventListener;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

import static org.com.sharekhan.service.TradeExecutionService.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderStatusPollingService {

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    private final TriggeredTradeSetupRepository tradeRepo;
    private final TriggerTradeRequestRepository triggerRequestRepo;
    private final Map<String, ScheduledFuture<?>> activePolls = new ConcurrentHashMap<>();

    private final WebSocketClientService webSocketClientService;
    private final TradeExecutionService tradeExecutionService;
    private final WebSocketSubscriptionHelper webSocketSubscriptionHelper;
    private final BrokerCredentialsRepository brokerCredentialsRepository;
    private final BrokerServiceFactory brokerServiceFactory;
    @Autowired
    private org.com.sharekhan.util.CryptoService cryptoService;
    @Autowired
    private LtpCacheService ltpCacheService;

    @Autowired
    private TelegramNotificationService telegramNotificationService;

    @Value("${app.order.poll-delay-ms:2000}")
    private long orderPollDelayMs;

    @Value("${app.dhan.order-updates.unavailable-backoff-ms:300000}") private long dhanBackoffMs=300000;
    @Value("${app.dhan.order-updates.unavailable-limit:5}") private int dhanUnavailableLimit=5;
    private Clock monitorClock=Clock.systemUTC();
    private final Map<String,Consumer<JSONObject>> pushedReconciliations=new ConcurrentHashMap<>();
    private final Object[] reconciliationLocks=java.util.stream.IntStream.range(0,256).mapToObj(i->new Object()).toArray();
    private final Map<String,Long> dhanCredentials=new ConcurrentHashMap<>();
    private record Registration(String broker, Long credential, String orderId, TriggeredTradeStatus status) {}
    private record Route(Long credential, String orderId) {}
    private record BrokerRoute(String broker, String orderId) {}
    private final Map<BrokerRoute,java.util.Set<String>> brokerRoutes=new ConcurrentHashMap<>();
    private final Map<String,Registration> registrations=new ConcurrentHashMap<>();
    private final Map<Route,java.util.Set<String>> dhanRoutes=new ConcurrentHashMap<>();
    private final java.util.Set<String> queuedHints=ConcurrentHashMap.newKeySet();
    private static class DhanBudget {int failures; Instant nextRest=Instant.EPOCH; boolean finalAttempted;}

    @jakarta.annotation.PreDestroy public void shutdown() {
        executor.shutdownNow();activePolls.clear();pushedReconciliations.clear();dhanCredentials.clear();registrations.clear();dhanRoutes.clear();brokerRoutes.clear();queuedHints.clear();
    }
    @EventListener public void onDhanOrderUpdate(DhanOrderUpdateEvent event) {
        var keys=new java.util.HashSet<String>();
        keys.addAll(dhanRoutes.getOrDefault(new Route(event.credentialId(),event.orderId()),java.util.Set.of()));
        if(event.correlationId()!=null && !event.correlationId().isBlank())
            keys.addAll(dhanRoutes.getOrDefault(new Route(event.credentialId(),"DHAN-CORR-"+event.correlationId()),java.util.Set.of()));
        for(String key:keys) {
            var handler=pushedReconciliations.get(key);
            if(handler!=null)try{handler.accept(new JSONObject(event.payload()));}catch(Exception ignored){}
        }
    }
    @EventListener public void onSnapshot(org.com.sharekhan.monitoring.BrokerOrderSnapshotEvent event) {
        String key=String.valueOf(event.tradeId());
        var registration=registrations.get(key);
        if(registration==null || !Objects.equals(registration.credential(),event.credentialId())
            || !Objects.equals(registration.orderId(),event.orderId()))return;
        var handler=pushedReconciliations.get(key);
        if(handler!=null)handler.accept(new JSONObject().put("_snapshot",new JSONObject(event.payload())));
    }
    @EventListener public void onBrokerHint(org.com.sharekhan.monitoring.BrokerOrderHintEvent event) {
        var keys=event.orderId()==null ? registrations.keySet()
            : brokerRoutes.getOrDefault(new BrokerRoute(event.broker().toLowerCase(java.util.Locale.ROOT),event.orderId()),java.util.Set.of());
        for(String key:keys) {
            var registration=registrations.get(key);
            if(registration==null)continue;
            if(!registration.broker().equalsIgnoreCase(event.broker())
                || (event.orderId()!=null && !event.orderId().equals(registration.orderId())) || !queuedHints.add(key))continue;
            try {executor.execute(()->{
                try {if(registrations.get(key)==registration
                    && !tradeExecutionService.isEntryOrderChaseActive(Long.valueOf(key))
                    && !tradeExecutionService.isExitOrderChaseActive(Long.valueOf(key))) {
                    var handler=pushedReconciliations.get(key);
                    if(handler!=null)handler.accept(new JSONObject().put("_forceDhanRest",true));
                }} finally {queuedHints.remove(key);}
            });}catch(RejectedExecutionException ignored){queuedHints.remove(key);}
        }
    }
    @EventListener public void onDhanStreamConnected(org.com.sharekhan.dhan.DhanOrderStreamConnectedEvent event) {
        for(var entry:dhanCredentials.entrySet()) if(event.credentialId().equals(entry.getValue())) {
            var handler=pushedReconciliations.get(entry.getKey());
            if(handler!=null)handler.accept(new JSONObject().put("_forceDhanRest",true));
        }
    }
    private void stopTimer(String key) {var future=activePolls.remove(key);if(future!=null)future.cancel(false);}
    private void stopMonitoring(String key) {
        stopTimer(key);pushedReconciliations.remove(key);dhanCredentials.remove(key);
        var old=registrations.remove(key);
        if(old!=null)brokerRoutes.computeIfPresent(new BrokerRoute(old.broker().toLowerCase(java.util.Locale.ROOT),old.orderId()),(route,keys)->{keys.remove(key);return keys.isEmpty()?null:keys;});
        if(old!=null && "Dhan".equalsIgnoreCase(old.broker()))dhanRoutes.computeIfPresent(new Route(old.credential(),old.orderId()),(route,keys)->{keys.remove(key);return keys.isEmpty()?null:keys;});
    }
    private boolean needsMonitoring(TriggeredTradeSetupEntity trade) {
        return trade!=null && (isExitOrder(trade) || trade.getStatus()==TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
    }
    private Instant dhanDeadline(TriggeredTradeSetupEntity trade) {
        LocalDate date=(isExitOrder(trade) && trade.getExitOrderPlacedAt()!=null ? trade.getExitOrderPlacedAt()
            : trade.getTriggeredAt()!=null ? trade.getTriggeredAt() : LocalDateTime.now(monitorClock.withZone(ZoneId.of("Asia/Kolkata")))).toLocalDate();
        return date.atTime(15,45).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
    }
    private boolean dhanSessionOpen(String exchange) {
        var now=ZonedDateTime.now(monitorClock.withZone(ZoneId.of("Asia/Kolkata")));
        return now.getDayOfWeek()!=DayOfWeek.SATURDAY && now.getDayOfWeek()!=DayOfWeek.SUNDAY
            && !now.toLocalTime().isBefore(LocalTime.of(9,15)) && !now.toLocalTime().isAfter(LocalTime.of(15,45));
    }
    @org.springframework.beans.factory.annotation.Autowired
    private ExitChaseControlService exitControls;
    private void requireDhanAttention(TriggeredTradeSetupEntity trade,String detail,boolean failedExit,boolean finalSession) {
        String reason=failedExit ? "DHAN_PARTIAL_EXIT_RECONCILIATION_REQUIRED" : finalSession ? "DHAN_SESSION_RECONCILIATION_REQUIRED" : "DHAN_ORDER_RECONCILIATION_REQUIRED";
        boolean first=!reason.equals(trade.getReason());
        if(failedExit)trade.setStatus(TriggeredTradeStatus.EXIT_FAILED);
        if(first) {
            if(exitControls!=null && isExitOrder(trade))exitControls.attention(trade,detail,monitorClock.instant());
            String comment=(trade.getComment()==null ? "" : trade.getComment()+"; ")+detail;
            trade.setComment(comment.substring(0,Math.min(2000,comment.length())));
            trade.setReason(reason);tradeRepo.save(trade);
            try {telegramNotificationService.sendTradeMessageForUser(trade.getAppUserId(),"Dhan order needs reconciliation",detail+"; trade="+trade.getId()+". Position remains open; no replacement order submitted.");}catch(Exception ignored){}
        }
    }

    // ---- Helper types and resolution for broker context (local copy) ----
    @lombok.Data
    private static class BrokerCtx {
        private final Long customerId;
        private final String apiKey;
        private final String clientCode;
        private final String brokerName;
        private final Long brokerCredentialsId;
    }

    @PostConstruct
    public void init() {
        // On restart, resume polling for any trades that are in a pending state
        // This ensures we don't lose track of orders placed before the restart
        log.info("Initializing OrderStatusPollingService - checking for pending orders to resume monitoring");
        resumePollingForPendingOrders();
        

    }

    private void resumePollingForPendingOrders() {
        try {
            // Find trades in states where we expect an order to be open/pending
            List<TriggeredTradeSetupEntity> pendingExitOrders = tradeRepo.findByStatus(TriggeredTradeStatus.EXIT_ORDER_PLACED);
            List<TriggeredTradeSetupEntity> pendingTargetOrders = tradeRepo.findByStatus(TriggeredTradeStatus.TARGET_ORDER_PLACED);
            // A target hit can claim a staged leg as EXIT_TRIGGERED while its
            // already-placed broker target order is still awaiting its fill.
            // Those orders must continue to be reconciled after a restart (and
            // after an in-memory poll is lost), otherwise later legs never get
            // their stepped stop loss.
            List<TriggeredTradeSetupEntity> exitTriggeredOrders = tradeRepo.findByStatus(TriggeredTradeStatus.EXIT_TRIGGERED);
            for(var failed:tradeRepo.findByStatus(TriggeredTradeStatus.EXIT_FAILED))
                if(failed.getReason()!=null && failed.getReason().startsWith("DHAN_") && failed.getExitOrderId()!=null) monitorOrderStatus(failed);
            List<TriggeredTradeSetupEntity> pendingEntryOrders = tradeRepo.findByStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);
            
            int resumedCount = 0;
            
            for (TriggeredTradeSetupEntity trade : pendingExitOrders) {
                if (trade.getExitOrderId() != null && !trade.getExitOrderId().isEmpty()) {
                    monitorOrderStatus(trade);
                    resumedCount++;
                }
            }

            for (TriggeredTradeSetupEntity trade : pendingTargetOrders) {
                if (trade.getExitOrderId() != null && !trade.getExitOrderId().isEmpty()) {
                    monitorOrderStatus(trade);
                    var registration=registrations.get(String.valueOf(trade.getId()));
                    if(registration!=null)onBrokerHint(new org.com.sharekhan.monitoring.BrokerOrderHintEvent(registration.broker(),trade.getExitOrderId()));
                    resumedCount++;
                }
            }

            for (TriggeredTradeSetupEntity trade : exitTriggeredOrders) {
                if (trade.getExitOrderId() != null && !trade.getExitOrderId().isEmpty()) {
                    monitorOrderStatus(trade);
                    resumedCount++;
                }
            }
            
            for (TriggeredTradeSetupEntity trade : pendingEntryOrders) {
                if (trade.getOrderId() != null && !trade.getOrderId().isEmpty()) {
                    monitorOrderStatus(trade);
                    resumedCount++;
                }
            }
            
            if (resumedCount > 0) {
                log.info("Resumed polling for {} pending orders", resumedCount);
            }
        } catch (Exception e) {
            log.error("Error resuming polling for pending orders: {}", e.getMessage(), e);
        }
    }

    // Resolve context based on brokerCredentialsId/appUserId
    private BrokerCtx resolveBrokerContext(Long brokerCredentialsId, Long appUserId) {
        try {
            org.com.sharekhan.entity.BrokerCredentialsEntity chosen = null;
            if (brokerCredentialsId != null) {
                chosen = brokerCredentialsRepository.findById(brokerCredentialsId).orElse(null);
            }
            if (chosen == null && appUserId != null) {
                java.util.List<org.com.sharekhan.entity.BrokerCredentialsEntity> list = brokerCredentialsRepository.findByAppUserId(appUserId);
                chosen = choosePreferredBrokerCredential(list);
            }
            if (chosen == null) {
                java.util.List<org.com.sharekhan.entity.BrokerCredentialsEntity> all = brokerCredentialsRepository.findAll();
                chosen = choosePreferredBrokerCredential(all);
            }
            if (chosen == null) return null;

            Long customerId = chosen.getCustomerId();
            String apiKey = null;
            String clientCode = null;
            try { apiKey = cryptoService.decrypt(chosen.getApiKey()); } catch (Exception e) { apiKey = chosen.getApiKey(); }
            try { clientCode = cryptoService.decrypt(chosen.getClientCode()); } catch (Exception e) { clientCode = chosen.getClientCode(); }
            return new BrokerCtx(customerId, apiKey, clientCode, chosen.getBrokerName(), chosen.getId());
        } catch (Exception e) {
            log.warn("Broker context resolve failed: {}", e.toString());
            return null;
        }
    }

    private BrokerCredentialsEntity choosePreferredBrokerCredential(List<BrokerCredentialsEntity> credentials) {
        if (credentials == null || credentials.isEmpty()) {
            return null;
        }
        BrokerCredentialsEntity defaultOrderBroker = credentials.stream()
                .filter(this::isActiveCredential)
                .filter(this::isOrderRoutingEnabled)
                .filter(credential -> Boolean.TRUE.equals(credential.getDefaultForOrders()))
                .findFirst()
                .orElse(null);
        if (defaultOrderBroker != null) {
            return defaultOrderBroker;
        }
        BrokerCredentialsEntity activeSharekhan = firstCredential(credentials, Broker.SHAREKHAN, true);
        if (activeSharekhan != null) {
            return activeSharekhan;
        }
        BrokerCredentialsEntity anySharekhan = firstCredential(credentials, Broker.SHAREKHAN, false);
        if (anySharekhan != null) {
            return anySharekhan;
        }
        for (BrokerCredentialsEntity credential : credentials) {
            if (credential != null && Boolean.TRUE.equals(credential.getActive())) {
                return credential;
            }
        }
        return credentials.stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    private BrokerCredentialsEntity firstCredential(List<BrokerCredentialsEntity> credentials,
                                                    Broker broker,
                                                    boolean activeOnly) {
        for (BrokerCredentialsEntity credential : credentials) {
            if (credential == null || credential.getBrokerName() == null) {
                continue;
            }
            if (credential.getBrokerName().equalsIgnoreCase(broker.getDisplayName())
                    && (!activeOnly || Boolean.TRUE.equals(credential.getActive()))) {
                return credential;
            }
        }
        return null;
    }

    private boolean isActiveCredential(BrokerCredentialsEntity credential) {
        return credential != null && !Boolean.FALSE.equals(credential.getActive());
    }

    private boolean isOrderRoutingEnabled(BrokerCredentialsEntity credential) {
        if (!isActiveCredential(credential)) {
            return false;
        }
        if (credential.getTradingEnabled() != null) {
            return credential.getTradingEnabled();
        }
        try {
            Broker broker = Broker.fromDisplayName(credential.getBrokerName());
            return broker == Broker.SHAREKHAN || broker == Broker.SIMULATOR;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isMarketOpen(String exchange) {
        if (exchange == null) return false;
        ZoneId zoneId = ZoneId.of("Asia/Kolkata");
        ZonedDateTime now = ZonedDateTime.now(monitorClock.withZone(zoneId));
        DayOfWeek day = now.getDayOfWeek();

        // Check if it's a weekday (Monday to Friday)
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }

        LocalTime time = now.toLocalTime();

        if ("MX".equalsIgnoreCase(exchange)) {
            // Commodity: 9:00 AM - 11:30 PM
            LocalTime start = LocalTime.of(9, 0);
            LocalTime end = LocalTime.of(23, 30);
            return !time.isBefore(start) && !time.isAfter(end);
        } else {
            // Keep reconciliation alive through the intraday closer's 15:35 exit-order
            // modifications and the broker's final fill acknowledgement. This is status
            // polling only; it does not permit new order placement after market close.
            LocalTime start = LocalTime.of(9, 15);
            LocalTime end = LocalTime.of(15, 40);
            return !time.isBefore(start) && !time.isAfter(end);
        }
    }

    public synchronized void monitorOrderStatus(TriggeredTradeSetupEntity trade) {
        // Use DB id as key so we don't schedule multiple polls for same trade
        final String tradeKey = String.valueOf(trade.getId());
        var initialContext=resolveBrokerContext(trade.getBrokerCredentialsId(),trade.getAppUserId());
        if(initialContext==null || initialContext.getBrokerName()==null)return;
        Registration registration=new Registration(initialContext.getBrokerName(),trade.getBrokerCredentialsId(),
            isExitOrder(trade)?trade.getExitOrderId():trade.getOrderId(),trade.getStatus());
        if(registration.equals(registrations.get(tradeKey)))return;
        stopMonitoring(tradeKey);
        registrations.put(tradeKey,registration);
        brokerRoutes.computeIfAbsent(new BrokerRoute(registration.broker().toLowerCase(java.util.Locale.ROOT),registration.orderId()),ignored->ConcurrentHashMap.newKeySet()).add(tradeKey);

        DhanBudget budget=new DhanBudget();
        if("DHAN_ORDER_RECONCILIATION_REQUIRED".equals(trade.getReason()))budget.failures=Math.max(1,dhanUnavailableLimit);
        Object reconciliationLock=reconciliationLocks[Math.floorMod(tradeKey.hashCode(),reconciliationLocks.length)];
        Consumer<JSONObject> reconciliation = pushed -> {
            if(registrations.get(tradeKey)!=registration)return;
            boolean snapshot=pushed!=null && pushed.has("_snapshot");
            boolean forceRest=pushed!=null && pushed.optBoolean("_forceDhanRest",false);
            if(forceRest)pushed=null;
            if(pushed==null && !forceRest && (budget.finalAttempted
                || (monitorClock.instant().isBefore(budget.nextRest) && monitorClock.instant().isBefore(dhanDeadline(trade)))))return;
            String orderIdToMonitor = null;
            boolean dhan=false;
            boolean finalDhanAttempt=false;
            try {
                // Reload before resolving both broker and order id. The original
                // scheduled object can be stale after an entry/exit is persisted.
                TriggeredTradeSetupEntity currentTrade = tradeRepo.findById(trade.getId()).orElse(null);
                if(!needsMonitoring(currentTrade)){stopMonitoring(tradeKey);return;}
                orderIdToMonitor = isExitOrder(currentTrade)
                        ? currentTrade.getExitOrderId()
                        : currentTrade.getOrderId();
                if (orderIdToMonitor == null || orderIdToMonitor.isBlank()) {
                    log.warn("Skipping status poll for trade {} because its broker order id is missing", currentTrade.getId());
                    return;
                }
                if(!Objects.equals(orderIdToMonitor,registration.orderId())
                    || !Objects.equals(currentTrade.getBrokerCredentialsId(),registration.credential()))return;
                BrokerCtx ctx = resolveBrokerContext(currentTrade.getBrokerCredentialsId(), currentTrade.getAppUserId());
                if (!isBrokerContextUsable(ctx)) {
                    throw new IllegalStateException("No active broker configured for this trade");
                }
                dhan="Dhan".equalsIgnoreCase(ctx.getBrokerName());
                if(pushed==null) {
                    if(!dhan && !isMarketOpen(currentTrade.getExchange()))return;
                    if(dhan) {
                        if(budget.finalAttempted)return;
                        finalDhanAttempt=!monitorClock.instant().isBefore(dhanDeadline(currentTrade));
                        if(!finalDhanAttempt && (!dhanSessionOpen(currentTrade.getExchange()) || (!forceRest && monitorClock.instant().isBefore(budget.nextRest))))return;
                    }
                }
                JSONObject response;
                if(snapshot)response=pushed.getJSONObject("_snapshot");
                else if(pushed!=null) {
                    var broker=brokerServiceFactory.getService(ctx.getBrokerName());
                    if(!(broker instanceof OrderUpdateBrokerService updates))return;
                    response=updates.normalizeOrderUpdate(currentTrade,toBrokerContext(ctx),orderIdToMonitor,pushed);
                    if(response==null)return;
                } else response=fetchBrokerOrderStatus(currentTrade,ctx,orderIdToMonitor);
                if(dhan && response!=null && response.optBoolean("requiresExitReconciliation",false)) {
                    requireDhanAttention(currentTrade,"Inactive Dhan exit has filled="+response.optLong("filledQuantity")+", remaining="+response.optLong("remainingQuantity")+"; reconcile actual position before another sell",true,false);
                    budget.finalAttempted=true;stopTimer(tradeKey);tradeExecutionService.stopExitOrderChase(currentTrade.getId());return;
                }
                TradeStatus tradeStatus = tradeExecutionService.evaluateOrderFinalStatus(currentTrade, response);
                if(dhan && tradeStatus!=TradeStatus.NO_RECORDS && (response!=null)) {
                    if(pushed!=null)budget.failures=0;
                    if("DHAN_ORDER_RECONCILIATION_REQUIRED".equals(currentTrade.getReason())
                        || (tradeStatus==TradeStatus.FULLY_EXECUTED && currentTrade.getReason()!=null && currentTrade.getReason().startsWith("DHAN_") && currentTrade.getReason().endsWith("RECONCILIATION_REQUIRED"))) {
                        currentTrade.setReason(null);tradeRepo.save(currentTrade);
                    }
                }
                if(dhan && pushed==null) {
                    if(tradeStatus==TradeStatus.NO_RECORDS)budget.failures++;else budget.failures=0;
                    budget.nextRest=monitorClock.instant().plusMillis(budget.failures>=Math.max(1,dhanUnavailableLimit) ? Math.max(1000,dhanBackoffMs) : Math.max(200,orderPollDelayMs));
                    if(budget.failures>=Math.max(1,dhanUnavailableLimit))requireDhanAttention(currentTrade,"Dhan order status repeatedly unavailable; switched to slower REST reconciliation",false,false);
                }
                if(dhan && finalDhanAttempt && tradeStatus!=TradeStatus.FULLY_EXECUTED && tradeStatus!=TradeStatus.REJECTED) {
                    requireDhanAttention(currentTrade,"End-of-session Dhan reconciliation is unresolved; frequent polling suspended",false,true);
                    budget.finalAttempted=true;stopTimer(tradeKey);return;
                }
                if (TradeStatus.FULLY_EXECUTED.equals(tradeStatus)) {
                    budget.finalAttempted=true;
                    // Determine if this was an exit order or entry order based on which orderId we monitored
                    boolean wasExitOrder = TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(currentTrade.getStatus())
                            || TriggeredTradeStatus.TARGET_ORDER_PLACED.equals(currentTrade.getStatus())
                            || (orderIdToMonitor != null && orderIdToMonitor.equals(currentTrade.getExitOrderId()));
                    if (wasExitOrder) {
                        tradeExecutionService.stopExitOrderChase(currentTrade.getId());
                        currentTrade.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);
                    } else if (TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION.equals(currentTrade.getStatus())) {
                        tradeExecutionService.stopEntryOrderChase(currentTrade.getId());
                        currentTrade.setStatus(TriggeredTradeStatus.EXECUTED);
                    }

                    // Diagnostic: log currentTrade fields before save
                    log.info("Saving tradeId={} before save: status={} orderId={} exitOrderId={} entryPrice={} exitPrice={} pnl={}",
                            currentTrade.getId(), currentTrade.getStatus(), currentTrade.getOrderId(), currentTrade.getExitOrderId(), currentTrade.getEntryPrice(), currentTrade.getExitPrice(), currentTrade.getPnl());

                    TriggeredTradeSetupEntity reloaded = null;
                    try {
                        // If this was an exit order, prefer an atomic repository update to avoid lost-update concurrency
                        if (wasExitOrder && currentTrade.getExitPrice() != null) {
                            TradeCostCalculator.TradeCharges charges = TradeCostCalculator.calculateCharges(currentTrade);
                            int updated = tradeRepo.markExitedWithPNL(currentTrade.getId(), TriggeredTradeStatus.EXITED_SUCCESS,
                                    currentTrade.getExitPrice(), currentTrade.getExitedAt(), currentTrade.getPnl(),
                                    charges != null ? charges.totalTradeCost() : null,
                                    charges != null ? charges.effectivePnl() : null);
                            if (updated == 1) {
                                log.info("✅ markExited updated trade {} to EXITED_SUCCESS", currentTrade.getId());
                            } else {
                                log.warn("⚠️ markExited returned {} for trade {} - falling back to save", updated, currentTrade.getId());
                                tradeRepo.save(currentTrade);
                            }
                        } else {
                            tradeRepo.save(currentTrade);
                        }
                        // Read back from DB to confirm persistence
                        reloaded = tradeRepo.findById(currentTrade.getId()).orElse(null);
                        if (reloaded == null) {
                            log.error("❌ Trade {} not found after save!", currentTrade.getId());
                        } else {
                            log.info("Reloaded tradeId={} after save: status={} orderId={} exitOrderId={} entryPrice={} exitPrice={} pnl={}",
                                    reloaded.getId(), reloaded.getStatus(), reloaded.getOrderId(), reloaded.getExitOrderId(), reloaded.getEntryPrice(), reloaded.getExitPrice(), reloaded.getPnl());
                        }
                    } catch (Exception e) {
                        log.error("❌ Failed saving trade {} after marking final status {}: {}", currentTrade.getId(), currentTrade.getStatus(), e.getMessage(), e);
                        // rethrow to bubble up so scheduled task doesn't silently ignore the failure
                        throw e;
                    }

                    stopMonitoring(tradeKey);
                    // After persisting, trigger post-execution hooks for entry orders
                    if (!wasExitOrder) {
                        TriggeredTradeSetupEntity executedTrade = reloaded != null ? reloaded : currentTrade;
                        tradeExecutionService.handleEntryOrderExecution(executedTrade);
                    } else {
                        tradeExecutionService.brokerFundsMayBeReleased(reloaded != null ? reloaded : currentTrade);
                        tradeExecutionService.advanceStagedTargetStopsAfterTargetExit(reloaded != null ? reloaded : currentTrade);
                        rearmGapFillRequest(reloaded != null ? reloaded : currentTrade);
                    }

                    // Send telegram notification for executed/exit
                    try {
                        String title = wasExitOrder ? "Trade Exited ✅" : "Order Executed ✅";
                        StringBuilder body = new StringBuilder();
                        body.append("Instrument: ").append(currentTrade.getSymbol());
                        if (currentTrade.getStrikePrice() != null) body.append(" ").append(currentTrade.getStrikePrice());
                        if (currentTrade.getOptionType() != null) body.append(" ").append(currentTrade.getOptionType());
                        body.append("\nOrderId: ").append(orderIdToMonitor);
                        body.append("\nStatus: ").append(currentTrade.getStatus());
                        if (currentTrade.getEntryPrice() != null) body.append("\nEntry: ").append(currentTrade.getEntryPrice());
                        if (currentTrade.getExitPrice() != null) body.append("\nExit: ").append(currentTrade.getExitPrice());
                        if (currentTrade.getPnl() != null) body.append("\nPnL: ").append(currentTrade.getPnl());
                        TriggeredTradeSetupEntity completedTrade = reloaded != null ? reloaded : currentTrade;
                        boolean profitableStockBazaariExit = wasExitOrder
                                && "StockBazaari".equalsIgnoreCase(completedTrade.getSource())
                                && completedTrade.getPnl() != null
                                && completedTrade.getPnl() > 0;
                        if (profitableStockBazaariExit) {
                            title = "StockBazaari Profit Hit ✅";
                            body.append("\n\nDisable this source for the user before the next signal if you want to stop further StockBazaari trades.");
                            telegramNotificationService.sendTradeMessageForUser(
                                    completedTrade.getAppUserId(), title, body.toString(),
                                    "disable-stockbazaari:" + completedTrade.getAppUserId());
                        } else {
                            telegramNotificationService.sendTradeMessageForUser(currentTrade.getAppUserId(), title, body.toString());
                        }
                    } catch (Exception e) {
                        log.warn("Failed sending telegram notification for execution: {}", e.getMessage());
                    }

                    if (wasExitOrder) {
                        tradeExecutionService.releaseOptionFeedIfUnused(currentTrade.getExchange(), currentTrade.getScripCode());
                    }
                    return;
                } else if (TradeStatus.REJECTED.equals(tradeStatus)) {
                    budget.finalAttempted=true;
                    tradeExecutionService.stopExitOrderChase(currentTrade.getId());

                    boolean rejectedExit=isExitOrder(currentTrade);
                    if (rejectedExit) {
                        currentTrade.setStatus(TriggeredTradeStatus.EXIT_FAILED);
                        currentTrade.setReason("EXIT_BROKER_ORDER_INACTIVE");
                        String diagnostic=response!=null?response.optString("brokerDiagnostic","Broker order inactive"):"Broker order inactive";
                        currentTrade.setComment("Exit order is cancelled, rejected or expired. Position remains open; reconcile before replacing order " + orderIdToMonitor+"; "+diagnostic);
                        if(exitControls!=null)exitControls.attention(currentTrade,currentTrade.getComment(),monitorClock.instant());
                    } else {
                        tradeExecutionService.stopEntryOrderChase(currentTrade.getId());
                        currentTrade.setStatus(TriggeredTradeStatus.REJECTED);
                        if (currentTrade.getReason() == null || currentTrade.getReason().isBlank()) {
                            currentTrade.setReason("ENTRY_BROKER_REJECTED");
                        }
                        if (currentTrade.getComment() == null || currentTrade.getComment().isBlank()) {
                            currentTrade.setComment("Broker polling reported the entry order as rejected; orderId="
                                    + orderIdToMonitor + ".");
                        }
                        if (currentTrade.getExitReason() == null || currentTrade.getExitReason().isBlank()) {
                            currentTrade.setExitReason(currentTrade.getReason());
                        }
                    }
                    tradeRepo.save(currentTrade);
                    if (TriggeredTradeStatus.REJECTED.equals(currentTrade.getStatus())) {
                        tradeExecutionService.syncLinkedEntryRequestStatus(currentTrade, TriggeredTradeStatus.REJECTED);
                    }
                    // Send telegram for rejection
                    try {
                        boolean cancelledForManualAction = "ENTRY_MAX_SLIPPAGE_EXCEEDED_CANCEL_REQUESTED"
                                .equals(currentTrade.getReason());
                        String title = rejectedExit ? "Exit Order Inactive — Position Still Open" : "ENTRY_USER_CANCEL_REQUESTED".equals(currentTrade.getReason())
                                ? "Entry Cancellation Confirmed ✅"
                                : cancelledForManualAction ? "Entry Cancelled — Manual Action Needed ⚠️" : "Order Rejected ❌";
                        StringBuilder body = new StringBuilder();
                        body.append("Instrument: ").append(currentTrade.getSymbol());
                        if (currentTrade.getStrikePrice() != null) body.append(" ").append(currentTrade.getStrikePrice());
                        if (currentTrade.getOptionType() != null) body.append(" ").append(currentTrade.getOptionType());
                        body.append("\nOrderId: ").append(orderIdToMonitor);
                        body.append("\nStatus: ").append(currentTrade.getStatus());
                        if (currentTrade.getExitReason() != null) body.append("\nReason: ").append(currentTrade.getExitReason());
                        if (cancelledForManualAction) {
                            body.append("\nThe broker-triggered entry did not fill within the configured safe slippage limit and was cancelled.");
                            body.append("\nPlease place it manually only if the current price is still acceptable.");
                        }
                        telegramNotificationService.sendTradeMessageForUser(currentTrade.getAppUserId(), title, body.toString());
                    } catch (Exception e) {
                        log.warn("Failed sending telegram notification for rejection: {}", e.getMessage());
                    }

                    stopMonitoring(tradeKey);
                    if(!rejectedExit)tradeExecutionService.releaseOptionFeedIfUnused(currentTrade.getExchange(), currentTrade.getScripCode());
                    return;
                } else if (TradeStatus.PENDING.equals(tradeStatus)) {
                    log.debug("Order {} pending on exchange; auto modification disabled", orderIdToMonitor);
                }
            }  catch (NullPointerException npe) {
                // Defensive guard: upstream SDK sometimes assumes a non-null Content-Type header and NPEs
                // Make this non-fatal so our scheduled task keeps running until the endpoint stabilizes
                log.warn("⚠️ Transient NPE from SDK while polling order {} — likely missing Content-Type on response; will retry", orderIdToMonitor, npe);
            } catch (Exception e) {
                // Do NOT rethrow — uncaught exceptions cancel ScheduledFuture. Log and continue so we keep polling.
                String sanitized = sanitizeThrowable(e);
                log.warn("⚠️ Unexpected error while polling order {}: {} — continuing", orderIdToMonitor, sanitized);
                if (log.isDebugEnabled()) {
                    log.debug("Unexpected error while polling order {}", orderIdToMonitor, e);
                }
            } finally {
                if(dhan && finalDhanAttempt && !budget.finalAttempted) {
                    budget.finalAttempted=true;stopTimer(tradeKey);
                    try {
                        var latest=tradeRepo.findById(trade.getId()).orElse(null);
                        if(needsMonitoring(latest))requireDhanAttention(latest,"Final Dhan reconciliation did not establish a terminal order outcome; polling suspended",false,true);
                    }catch(Exception ignored){}
                }
            }
        };
        Consumer<JSONObject> guarded=pushed->{
            Runnable work=()->{synchronized(reconciliationLock){reconciliation.accept(pushed);}};
            tradeExecutionService.reconcileEntryUnderLock(trade.getId(),work);
        };
        boolean dhan="Dhan".equalsIgnoreCase(initialContext.getBrokerName());
        boolean socket=dhan || "Sharekhan".equalsIgnoreCase(initialContext.getBrokerName());
        pushedReconciliations.put(tradeKey,guarded);
        if(dhan) {
            dhanCredentials.put(tradeKey,trade.getBrokerCredentialsId());
            dhanRoutes.computeIfAbsent(new Route(registration.credential(),registration.orderId()),ignored->ConcurrentHashMap.newKeySet()).add(tradeKey);
            boolean suspended="DHAN_PARTIAL_EXIT_RECONCILIATION_REQUIRED".equals(trade.getReason()) || "DHAN_SESSION_RECONCILIATION_REQUIRED".equals(trade.getReason());
            if(suspended){budget.finalAttempted=true;return;}
        }
        // Resting broker targets are owned by the order stream. Reconnect/startup catch-up is event driven.
        if(socket && trade.getStatus()==TriggeredTradeStatus.TARGET_ORDER_PLACED)return;
        long delay=trade.getStatus()==TriggeredTradeStatus.TARGET_ORDER_PLACED ? Math.max(60000,orderPollDelayMs) : Math.max(orderPollDelayMs,200L);
        activePolls.put(tradeKey,executor.scheduleWithFixedDelay(()->{
            if(registrations.get(tradeKey)!=registration)return;
            // The chase already obtains an authoritative snapshot on every iteration.
            if(tradeExecutionService.isEntryOrderChaseActive(trade.getId()) || tradeExecutionService.isExitOrderChaseActive(trade.getId()))return;
            guarded.accept(null);
            // An inactive exit chase gets one final reconciliation; never poll it indefinitely.
            if(socket && registrations.get(tradeKey)==registration && registration.status()!=TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)stopTimer(tradeKey);
        },250L,delay,TimeUnit.MILLISECONDS));
    }

    static boolean isExitOrder(TriggeredTradeSetupEntity trade) {
        return trade != null && (TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(trade.getStatus())
                || TriggeredTradeStatus.TARGET_ORDER_PLACED.equals(trade.getStatus())
                // PriceTriggerService changes a filled-or-crossed broker target
                // order to EXIT_TRIGGERED. Keep polling its exitOrderId rather
                // than accidentally falling back to the already-filled entry
                // orderId.
                || ((TriggeredTradeStatus.EXIT_TRIGGERED.equals(trade.getStatus()) || TriggeredTradeStatus.EXIT_FAILED.equals(trade.getStatus()))
                    && trade.getExitOrderId() != null && !trade.getExitOrderId().isBlank()));
    }

    void rearmGapFillRequest(TriggeredTradeSetupEntity exitedTrade) {
        if (exitedTrade == null || !"GAP_FILL_STOP".equals(exitedTrade.getExitReason())
                || exitedTrade.getTriggerRequestId() == null) {
            return;
        }
        if ("atr-pdh-pdl-strategy".equalsIgnoreCase(exitedTrade.getSource())) {
            log.info("ATR prior-day request {} will not be rearmed after exit; one entry per symbol is enforced",
                    exitedTrade.getTriggerRequestId());
            return;
        }
        int completedReentries = exitedTrade.getGapReentryCount() != null
                ? exitedTrade.getGapReentryCount()
                : 0;
        if (completedReentries >= 1) {
            log.info("Gap-fill request {} will not be rearmed because the one-retrigger limit is exhausted",
                    exitedTrade.getTriggerRequestId());
            return;
        }

        int rearmed = triggerRequestRepo.rearmGapFillOnce(exitedTrade.getTriggerRequestId());
        if (rearmed == 1) {
            log.info("Rearmed gap-fill request {} for one retrigger", exitedTrade.getTriggerRequestId());
        } else {
            log.info("Gap-fill request {} was already rearmed or is no longer eligible",
                    exitedTrade.getTriggerRequestId());
        }
    }

    private JSONObject fetchBrokerOrderStatus(TriggeredTradeSetupEntity trade,
                                              BrokerCtx ctx,
                                              String orderIdToMonitor) {
        if (ctx == null || ctx.getBrokerName() == null) {
            return null;
        }
        BrokerService brokerService = brokerServiceFactory.getService(ctx.getBrokerName());
        if (!(brokerService instanceof OrderStatusBrokerService orderStatusBrokerService)) {
            log.debug("Broker {} does not support status polling for order {}",
                    ctx.getBrokerName(), orderIdToMonitor);
            return null;
        }
        return orderStatusBrokerService.fetchOrderStatus(trade, toBrokerContext(ctx), orderIdToMonitor);
    }

    private BrokerContext toBrokerContext(BrokerCtx ctx) {
        return new BrokerContext(ctx.getCustomerId(),
                ctx.getApiKey(),
                ctx.getClientCode(),
                ctx.getBrokerName(),
                ctx.getBrokerCredentialsId());
    }

    private boolean isBrokerContextUsable(BrokerCtx ctx) {
        if (ctx == null || ctx.getBrokerName() == null) {
            return false;
        }
        try {
            Broker broker = Broker.fromDisplayName(ctx.getBrokerName());
            if (broker == Broker.SIMULATOR) {
                return true;
            }
            if (broker == Broker.DHAN) {
                return ctx.getBrokerCredentialsId() != null && ctx.getClientCode() != null && !ctx.getClientCode().isBlank();
            }
            if (ctx.getApiKey() == null) {
                return false;
            }
            return broker != Broker.SHAREKHAN || ctx.getCustomerId() != null;
        } catch (Exception e) {
            return false;
        }
    }



    private String sanitizeThrowable(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        String flattened = message.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (flattened.length() > 300) {
            return flattened.substring(0, 300) + "...";
        }
        return flattened;
    }

    /*
    Example Response from Sharekhan

    https://api.sharekhan.com/skapi/services/reports/NF/73196/136757437
{
    "data": [
        {
            "orderType": "Limit",
            "orsExchangeMarketCode": "N",
            "orderId": "136757437",
            "fnoSquareOff": "",
            "goodTillDate": "",
            "fohExitDateTime": "",
            "traderId": "111111111111100",
            "childTrailingPrice": "0",
            "optionType": "CE",
            "lastModTime": "",
            "ohEntryDateTime": "",
            "coverOrderId": "0",
            "orderQty": 1200,
            "scripCode": "59004",
            "orderTrailByPrice": "0",
            "webResponseTime": "0",
            "childTrailByPrice": "0",
            "brokerage": "0",
            "clientGroup": "",
            "contract": "NIFTY 28Oct2025",
            "disclosedQty": 0,
            "orderMifQty": 0,
            "childSlPrice": "0",
            "openOrClose": "O",
            "pvtOrderInd": "O",
            "caLevel": 0,
            "advOrderType": "NOR",
            "orderTargetPrice": "0",
            "trailingStatus": "",
            "clientAcc": "",
            "openQty": 1200,
            "channelUser": "SGUPTA78",
            "exchange": "NF",
            "execQty": 0,
            "strikePrice": "26000.00",
            "fnoOrderType": "NOR",
            "orderTrailingPrice": "0",
            "updateDate": "2025-10-28 12:44:40.0",
            "avgPrice": "0",
            "orsOrderId": 0,
            "orderStatus": "In-Process",
            "goodtillDate": "2025-10-28 12:44:40.0",
            "expiryDate": "28/10/2025",
            "dpClientId": 0,
            "orderTriggerPrice": "0",
            "customerId": 73196,
            "orderPrice": "24.35",
            "afterHour": "N",
            "tradingSymbol": "NIFTY",
            "participantCode": 0,
            "channelCode": "SKAPI",
            "exchangeOrderId": "",
            "orderDateTime": "2025-10-28 12:44:40.0",
            "buySell": "B",
            "instrumentType": "OI",
            "fnoSquareoff": "N",
            "dpId": 1,
            "updateUser": "BOG",
            "exchAckDateTime": "",
            "segmentCode": "",
            "userId": "SKNSEFO4",
            "productCode": "0",
            "allOrNone": "N",
            "limitLossTo": "0",
            "bookProfitPrice": "0",
            "goodtill": "GFD",
            "requestStatus": "NEW"
        },
        {
            "orderType": "Limit",
            "orsExchangeMarketCode": "N",
            "orderId": "136757437",
            "fnoSquareOff": "",
            "goodTillDate": "",
            "fohExitDateTime": "",
            "traderId": "111111111111100",
            "childTrailingPrice": "0",
            "optionType": "CE",
            "lastModTime": "",
            "ohEntryDateTime": "",
            "coverOrderId": "0",
            "orderQty": 1200,
            "scripCode": "59004",
            "orderTrailByPrice": "0",
            "webResponseTime": "0",
            "childTrailByPrice": "0",
            "brokerage": "-2951621",
            "clientGroup": "",
            "contract": "NIFTY 28Oct2025",
            "disclosedQty": 0,
            "orderMifQty": 0,
            "childSlPrice": "0",
            "openOrClose": "O",
            "pvtOrderInd": "0",
            "caLevel": 0,
            "advOrderType": "NOR",
            "orderTargetPrice": "0",
            "trailingStatus": "",
            "clientAcc": "",
            "openQty": 1200,
            "channelUser": "SGUPTA78",
            "exchange": "NF",
            "execQty": 0,
            "strikePrice": "26000.00",
            "fnoOrderType": "NOR",
            "orderTrailingPrice": "0",
            "updateDate": "2025-10-28 12:44:40.0",
            "avgPrice": "0",
            "orsOrderId": 136757437,
            "orderStatus": "In-Process",
            "goodtillDate": "2025-10-28 12:44:40.0",
            "expiryDate": "28/10/2025",
            "dpClientId": 0,
            "orderTriggerPrice": "0",
            "customerId": 73196,
            "orderPrice": "24.35",
            "afterHour": "N",
            "tradingSymbol": "NIFTY",
            "participantCode": 0,
            "channelCode": "SKAPI",
            "exchangeOrderId": "",
            "orderDateTime": "2025-10-28 12:44:40.0",
            "buySell": "B",
            "instrumentType": "OI",
            "fnoSquareoff": "N",
            "dpId": 1,
            "updateUser": "FO_AH_NOR",
            "exchAckDateTime": "2025-10-28 12:44:41.0",
            "segmentCode": "",
            "userId": "SKNSEFO4",
            "productCode": "0",
            "allOrNone": "N",
            "limitLossTo": "0",
            "bookProfitPrice": "0",
            "goodtill": "GFD",
            "requestStatus": "NEW"
        },
        {
            "orderType": "Limit",
            "orsExchangeMarketCode": "N",
            "orderId": "136757437",
            "fnoSquareOff": "",
            "goodTillDate": "",
            "fohExitDateTime": "",
            "traderId": "111111111111100",
            "childTrailingPrice": "0",
            "optionType": "CE",
            "lastModTime": "2025-10-28 12:44:41.0",
            "ohEntryDateTime": "",
            "coverOrderId": "0",
            "orderQty": 1200,
            "scripCode": "59004",
            "orderTrailByPrice": "0",
            "webResponseTime": "0",
            "childTrailByPrice": "0",
            "brokerage": "-2951621",
            "clientGroup": "",
            "contract": "NIFTY 28Oct2025",
            "disclosedQty": 0,
            "orderMifQty": 0,
            "childSlPrice": "0",
            "openOrClose": "O",
            "pvtOrderInd": "0",
            "caLevel": 0,
            "advOrderType": "NOR",
            "orderTargetPrice": "0",
            "trailingStatus": "",
            "clientAcc": "",
            "openQty": 1200,
            "channelUser": "SGUPTA78",
            "exchange": "NF",
            "execQty": 0,
            "strikePrice": "26000.00",
            "fnoOrderType": "NOR",
            "orderTrailingPrice": "0",
            "updateDate": "2025-10-28 12:44:41.0",
            "avgPrice": "0",
            "orsOrderId": 1446122681227650728,
            "orderStatus": "Pending",
            "goodtillDate": "2025-10-28 12:44:40.0",
            "expiryDate": "28/10/2025",
            "dpClientId": 0,
            "orderTriggerPrice": "0",
            "customerId": 73196,
            "orderPrice": "24.35",
            "afterHour": "N",
            "tradingSymbol": "NIFTY",
            "participantCode": 0,
            "channelCode": "SKAPI",
            "exchangeOrderId": "1200000183352471",
            "orderDateTime": "2025-10-28 12:44:40.0",
            "buySell": "B",
            "instrumentType": "OI",
            "fnoSquareoff": "N",
            "dpId": 1,
            "updateUser": "FO_AH_NOR",
            "exchAckDateTime": "2025-10-28 12:44:41.0",
            "segmentCode": "",
            "userId": "SKNSEFO4",
            "productCode": "0",
            "allOrNone": "N",
            "limitLossTo": "0",
            "bookProfitPrice": "0",
            "goodtill": "GFD",
            "requestStatus": "NEW"
        },
        {
            "orderType": "Limit",
            "orsExchangeMarketCode": "N",
            "orderId": "136757437",
            "fnoSquareOff": "",
            "goodTillDate": "",
            "fohExitDateTime": "",
            "traderId": "111111111111100",
            "childTrailingPrice": "0",
            "optionType": "CE",
            "lastModTime": "2025-10-28 12:44:41.0",
            "ohEntryDateTime": "",
            "coverOrderId": "0",
            "orderQty": 1200,
            "scripCode": "59004",
            "orderTrailByPrice": "0",
            "webResponseTime": "0",
            "childTrailByPrice": "0",
            "brokerage": "-2857079",
            "clientGroup": "",
            "contract": "NIFTY 28Oct2025",
            "disclosedQty": 0,
            "orderMifQty": 0,
            "childSlPrice": "0",
            "openOrClose": "O",
            "pvtOrderInd": "0",
            "caLevel": 0,
            "advOrderType": "NOR",
            "orderTargetPrice": "0",
            "trailingStatus": "",
            "clientAcc": "",
            "openQty": 150,
            "channelUser": "SGUPTA78",
            "exchange": "NF",
            "execQty": 1050,
            "strikePrice": "26000.00",
            "fnoOrderType": "NOR",
            "orderTrailingPrice": "0",
            "updateDate": "2025-10-28 12:44:41.0",
            "avgPrice": "23.45",
            "orsOrderId": 1446122681227656516,
            "orderStatus": "Partly Executed",
            "goodtillDate": "2025-10-28 12:44:40.0",
            "expiryDate": "28/10/2025",
            "dpClientId": 0,
            "orderTriggerPrice": "0",
            "customerId": 73196,
            "orderPrice": "24.35",
            "afterHour": "N",
            "tradingSymbol": "NIFTY",
            "participantCode": 0,
            "channelCode": "SKAPI",
            "exchangeOrderId": "1200000183352471",
            "orderDateTime": "2025-10-28 12:44:40.0",
            "buySell": "B",
            "instrumentType": "OI",
            "fnoSquareoff": "N",
            "dpId": 1,
            "updateUser": "FO_AH_TC",
            "exchAckDateTime": "2025-10-28 12:44:41.0",
            "segmentCode": "",
            "userId": "SKNSEFO4",
            "productCode": "0",
            "allOrNone": "N",
            "limitLossTo": "0",
            "bookProfitPrice": "0",
            "goodtill": "GFD",
            "requestStatus": "NEW"
        },
        {
            "orderType": "Limit",
            "orsExchangeMarketCode": "N",
            "orderId": "136757437",
            "fnoSquareOff": "",
            "goodTillDate": "",
            "fohExitDateTime": "",
            "traderId": "111111111111100",
            "childTrailingPrice": "0",
            "optionType": "CE",
            "lastModTime": "2025-10-28 12:44:41.0",
            "ohEntryDateTime": "",
            "coverOrderId": "0",
            "orderQty": 1200,
            "scripCode": "59004",
            "orderTrailByPrice": "0",
            "webResponseTime": "0",
            "childTrailByPrice": "0",
            "brokerage": "-2843573",
            "clientGroup": "",
            "contract": "NIFTY 28Oct2025",
            "disclosedQty": 0,
            "orderMifQty": 0,
            "childSlPrice": "0",
            "openOrClose": "O",
            "pvtOrderInd": "0",
            "caLevel": 0,
            "advOrderType": "NOR",
            "orderTargetPrice": "0",
            "trailingStatus": "",
            "clientAcc": "",
            "openQty": 0,
            "channelUser": "SGUPTA78",
            "exchange": "NF",
            "execQty": 1200,
            "strikePrice": "26000.00",
            "fnoOrderType": "NOR",
            "orderTrailingPrice": "0",
            "updateDate": "2025-10-28 12:44:41.0",
            "avgPrice": "23.45",
            "orsOrderId": 1446122681227661860,
            "orderStatus": "Fully Executed",
            "goodtillDate": "2025-10-28 12:44:40.0",
            "expiryDate": "28/10/2025",
            "dpClientId": 0,
            "orderTriggerPrice": "0",
            "customerId": 73196,
            "orderPrice": "24.35",
            "afterHour": "N",
            "tradingSymbol": "NIFTY",
            "participantCode": 0,
            "channelCode": "SKAPI",
            "exchangeOrderId": "1200000183352471",
            "orderDateTime": "2025-10-28 12:44:40.0",
            "buySell": "B",
            "instrumentType": "OI",
            "fnoSquareoff": "N",
            "dpId": 1,
            "updateUser": "FO_AH_TC",
            "exchAckDateTime": "2025-10-28 12:44:41.0",
            "segmentCode": "",
            "userId": "SKNSEFO4",
            "productCode": "0",
            "allOrNone": "N",
            "limitLossTo": "0",
            "bookProfitPrice": "0",
            "goodtill": "GFD",
            "requestStatus": "NEW"
        }
    ],
    "message": "order_history",
    "status": 200,
    "timestamp": "2025-10-28T14:52:32+05:30"
}

     */
}
