package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.service.broker.SharekhanBrokerService;
import org.com.sharekhan.strategy.SpotAtrPreviousDayBigTradePlusStrategy;
import org.com.sharekhan.util.CryptoService;
import org.com.sharekhan.util.ShareKhanOrderUtil;
import org.json.JSONObject;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Continues monitoring broker exits after the entry poll has finished; recovers on restart. */
@Service @RequiredArgsConstructor @Slf4j
public class BigTradePlusReconciliationService {
    private final TriggeredTradeSetupRepository trades;
    private final BrokerCredentialsRepository credentials;
    private final CryptoService crypto;
    private final SharekhanBrokerService broker;
    private static final ZoneId MARKET = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Scheduled(fixedDelayString = "${app.order.btp-reconcile-ms:10000}")
    public synchronized void reconcile() {
        LocalDateTime now = LocalDateTime.now(MARKET);
        if (now.getDayOfWeek().getValue() > 5 || now.toLocalTime().isBefore(LocalTime.of(9,15))
                || now.toLocalTime().isAfter(LocalTime.of(15,40))) return;
        List<TriggeredTradeSetupEntity> all = trades.findByBrokerProductTypeIgnoreCaseAndTriggeredAtBetween(
                "BIGTRADEPLUS", now.toLocalDate().atStartOfDay(), now.toLocalDate().plusDays(1).atStartOfDay());
        Set<Long> ids = new HashSet<>();
        for (var trade : all) if (trade.getStatus() == TriggeredTradeStatus.EXECUTED && trade.getBrokerCredentialsId() != null)
            ids.add(trade.getBrokerCredentialsId());
        for (Long id : ids) {
            try {
                var credential = credentials.findById(id).orElse(null);
                if (credential == null || !"Sharekhan".equalsIgnoreCase(credential.getBrokerName())) continue;
                BrokerContext context = new BrokerContext(credential.getCustomerId(), decrypt(credential.getApiKey()),
                        decrypt(credential.getClientCode()), credential.getBrokerName(), id);
                var scoped = all.stream().filter(t -> id.equals(t.getBrokerCredentialsId())
                        && Objects.equals(t.getAppUserId(), credential.getAppUserId())).toList();
                reconcileReport(context, scoped, broker.fetchDayOrders(context));
            } catch (Exception e) { log.warn("BTP reconciliation failed for credential {}: {}", id, e.getClass().getSimpleName()); }
        }
    }

    private String decrypt(String value) {
        try { return crypto.decrypt(value); } catch (Exception e) { return value; }
    }

    void reconcileReport(BrokerContext context, List<TriggeredTradeSetupEntity> local, JSONObject report) {
        if (report == null || report.optInt("status") != 200 || report.optJSONArray("data") == null) return;
        Map<String, JSONObject> parents = new HashMap<>(), children = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Object item : report.getJSONArray("data")) {
            if (!(item instanceof JSONObject row) || row.optLong("customerId") != context.getCustomerId()
                    || !"NC".equals(row.optString("exchange")) || !"BKT".equals(row.optString("orderType"))) continue;
            if (row.optBoolean("childOrder")) {
                String parent = row.optString("mpCoverOrderId");
                if (children.putIfAbsent(parent, row) != null) ambiguous.add(parent);
            } else parents.put(row.optString("orderId"), row);
        }
        ambiguous.forEach(children::remove);
        for (var trade : local) {
            JSONObject parent = parents.get(trade.getOrderId()), child = children.get(trade.getOrderId());
            if (!matches(trade, parent, child)) continue;
            if (trade.getStatus() == TriggeredTradeStatus.EXECUTED && filled(child, trade.getQuantity())) {
                double entry = parent.optDouble("execPrice"), exit = child.optDouble("execPrice");
                if (!positive(entry) || !positive(exit)) continue;
                LocalDateTime exited = LocalDateTime.parse(child.getString("lastModTime"), TIME);
                // Preserve average fill precision: average prices need not lie on an order-price tick.
                trade.setActualEntryPrice(entry); trade.setExitPrice(exit);
                trade.setPnl(BigDecimal.valueOf(exit).subtract(BigDecimal.valueOf(entry))
                        .multiply(BigDecimal.valueOf(trade.getQuantity())).setScale(2, RoundingMode.HALF_UP).doubleValue());
                trade.setExitOrderId(child.getString("orderId")); trade.setExitedAt(exited);
                trade.setExitReason("Book Profit Triggered".equals(child.optString("childTriggered")) ? "TARGET_HIT" : "BROKER_BRACKET_EXIT");
                trade.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);
                trades.save(trade);
                log.info("BTP exit reconciled trade={} child={} price={} pnl={}", trade.getId(), trade.getExitOrderId(), exit, trade.getPnl());
            }
        }
        // Evaluate again on every report, including after restart/rejected modifies; only broker-confirmed stops are persisted.
        for (var leg : local) {
            if (leg.getStatus() != TriggeredTradeStatus.EXECUTED
                    || !SpotAtrPreviousDayBigTradePlusStrategy.SOURCE.equals(leg.getSource())) continue;
            JSONObject parent = parents.get(leg.getOrderId()), child = children.get(leg.getOrderId());
            if (!matches(leg, parent, child) || !"Pending".equalsIgnoreCase(child.optString("orderStatus"))
                    || child.optLong("execQty", -1) != 0 || !"TRACK_INPROCESS".equals(child.optString("trailingStatus"))) continue;
            boolean lowerTargetFilled = local.stream().anyMatch(other -> sameSetup(leg, other)
                    && other.getStatus() == TriggeredTradeStatus.EXITED_SUCCESS && "TARGET_HIT".equals(other.getExitReason())
                    && parents.containsKey(other.getOrderId()) && filled(children.get(other.getOrderId()), other.getQuantity())
                    && parents.get(other.getOrderId()).optDouble("bookProfitPrice") < parent.optDouble("bookProfitPrice"));
            if (!lowerTargetFilled) continue;
            double entry = parent.optDouble("execPrice");
            if (!positive(entry)) continue;
            double stop = BigDecimal.valueOf(entry).divide(new BigDecimal("0.05"), 0, RoundingMode.CEILING)
                    .multiply(new BigDecimal("0.05")).doubleValue();
            double currentStop = child.optDouble("triggerPrice");
            if (!positive(currentStop) || stop >= parent.optDouble("bookProfitPrice")) continue;
            if (currentStop >= stop) {
                if (!Objects.equals(leg.getStopLoss(), currentStop)) { leg.setStopLoss(currentStop); trades.save(leg); }
            } else broker.modifyBracketStop(context, parent, child, stop);
        }
    }

    static boolean sameSetup(TriggeredTradeSetupEntity a, TriggeredTradeSetupEntity b) {
        return Objects.equals(a.getBrokerCredentialsId(), b.getBrokerCredentialsId())
                && Objects.equals(a.getAppUserId(), b.getAppUserId()) && Objects.equals(a.getSource(), b.getSource())
                && Objects.equals(a.getScripCode(), b.getScripCode()) && a.getTriggeredAt() != null && b.getTriggeredAt() != null
                && a.getTriggeredAt().toLocalDate().equals(b.getTriggeredAt().toLocalDate());
    }
    static boolean filled(JSONObject row, Long quantity) {
        return row != null && quantity != null && quantity > 0 && ShareKhanOrderUtil.isFullyExecutedStatus(row.optString("orderStatus"))
                && row.optLong("execQty", -1) == quantity && row.optLong("orderQty", -1) == quantity;
    }
    private static boolean matches(TriggeredTradeSetupEntity trade, JSONObject parent, JSONObject child) {
        return parent != null && child != null && Objects.equals(trade.getScripCode(), parent.optInt("scripCode"))
                && parent.optInt("scripCode") == child.optInt("scripCode") && trade.getQuantity() != null
                && trade.getQuantity() == parent.optLong("orderQty") && trade.getQuantity() == child.optLong("orderQty");
    }
    private static boolean positive(double price) { return Double.isFinite(price) && price > 0; }
}
