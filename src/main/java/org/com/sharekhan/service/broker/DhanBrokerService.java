package org.com.sharekhan.service.broker;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.com.sharekhan.auth.AccessTokenPersistenceService;
import org.com.sharekhan.dhan.*;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.util.CryptoService;
import org.json.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class DhanBrokerService implements ModifiableEntryBrokerService, TriggerPriceEntryBrokerService, OrderUpdateBrokerService {
    private static final String CORRELATION_PREFIX = "DHAN-CORR-";
    private final DhanOrderClient client;
    private final DhanInstrumentResolver instruments;
    private final DhanSubmissionClaim claims;
    private final BrokerCredentialsRepository credentials;
    private final AccessTokenPersistenceService tokens;
    private final CryptoService crypto;
    private record Session(String clientId, String token, boolean entryEnabled) {
        @Override public String toString() { return "DhanSession[redacted]"; }
    }
    // One-use metadata from the same worker's preceding status GET, never a stream payload.
    private record ModificationSnapshot(Long credential, Long trade, String order, String client,
                                        String token, long atNanos, String row) {
        @Override public String toString(){return "ModificationSnapshot[redacted]";}
    }
    private final ThreadLocal<ModificationSnapshot> modificationSnapshot=new ThreadLocal<>();
    private java.util.function.LongSupplier snapshotTicker=System::nanoTime;
    private JSONObject modificationOrder(Session session,TriggeredTradeSetupEntity trade,String orderId) throws Exception {
        var snapshot=modificationSnapshot.get();modificationSnapshot.remove();
        if(snapshot!=null && Objects.equals(snapshot.credential(),trade.getBrokerCredentialsId())
            && Objects.equals(snapshot.trade(),trade.getId()) && Objects.equals(snapshot.order(),orderId)
            && snapshot.client().equals(session.clientId()) && snapshot.token().equals(session.token())
            && snapshotTicker.getAsLong()-snapshot.atNanos()>=0
            && snapshotTicker.getAsLong()-snapshot.atNanos()<java.util.concurrent.TimeUnit.SECONDS.toNanos(2))
            return new JSONObject(snapshot.row());
        return lookup(session,orderId);
    }
    @Override public Broker getBroker() { return Broker.DHAN; }
    @Override public OrderPlacementResult placeOrder(TriggeredTradeSetupEntity trade, BrokerContext context, double price) {
        return place(trade,context,price,null,"BUY");
    }
    @Override public OrderPlacementResult placeExitOrder(TriggeredTradeSetupEntity trade, BrokerContext context, double price) {
        if (trade != null && trade.getExitOrderId() != null && !trade.getExitOrderId().isBlank())
            return rejected("Existing Dhan exit must be reconciled before another sell",price);
        return place(trade,context,price,null,"SELL");
    }
    @Override public OrderPlacementResult placeTriggerPriceEntryOrder(TriggeredTradeSetupEntity trade, BrokerContext context, double trigger, double limit) {
        return place(trade,context,limit,trigger,"BUY");
    }
    private OrderPlacementResult place(TriggeredTradeSetupEntity trade, BrokerContext context, double price, Double trigger, String side) {
        Session session; String correlation; String submissionKey; JSONObject body;
        try {
            session = session(trade,context);
            if ("BUY".equals(side) && !session.entryEnabled()) throw new IllegalArgumentException("Enable trading before entry");
            var instrument = instruments.resolve(trade);
            long quantity = quantity(trade,instrument);
            BigDecimal limit = price(price,instrument);
            if (trigger != null && (price(trigger,instrument).compareTo(limit)>0)) throw new IllegalArgumentException("Trigger above buy limit");
            if ("BUY".equals(side) && trade.getTriggerRequestId() != null && trade.getTriggerRequestId() > 0) {
                submissionKey = "REQUEST:"+trade.getTriggerRequestId()+":REENTRY:"+(trade.getGapReentryCount() == null ? 0 : trade.getGapReentryCount());
            } else if (trade.getId() != null && trade.getId() > 0) {
                submissionKey = "TRADE:"+trade.getId();
            } else throw new IllegalArgumentException("Persist request or trade before sending Dhan order");
            if (trade.getBrokerProductType() != null && !trade.getBrokerProductType().isBlank())
                throw new IllegalArgumentException("Dhan broker-managed products are unsupported");
            correlation = correlation(context.getBrokerCredentialsId(),submissionKey,side);
            body = new JSONObject().put("dhanClientId",session.clientId()).put("correlationId",correlation)
                .put("transactionType",side).put("exchangeSegment",instrument.segment())
                .put("productType",Boolean.TRUE.equals(trade.getIntraday()) ? "INTRADAY" : instrument.segment().endsWith("FNO") ? "MARGIN" : "CNC")
                .put("orderType",trigger == null ? "LIMIT" : "STOP_LOSS").put("validity","DAY")
                .put("securityId",instrument.securityId()).put("quantity",quantity).put("disclosedQuantity",0)
                .put("price",limit).put("triggerPrice",trigger == null ? BigDecimal.ZERO : price(trigger,instrument)).put("afterMarketOrder",false);
        } catch (Exception ignored) { return rejected("Dhan order not sent: verify scoped session, instrument, product, quantity and tick-aligned price",price); }
        boolean claimed;
        try { claims.claim(correlation,context.getBrokerCredentialsId(),submissionKey,side); claimed=true; }
        catch (DataIntegrityViolationException duplicate) { claimed=false; }
        catch (Exception ignored) { return rejected("Dhan submission journal unavailable; order not sent",price); }
        // Correlation lookups reconcile an ambiguous submission; they never authorize another POST.
        if (claimed) {
            try {
                var response = client.exchange("POST","/orders",session.token(),body);
                if (response.ok()) {
                    var result = accepted(response.json(),null,price);
                    if (result != null) return placementOutcome(result, session, side);
                } else if (response.code() >= 400 && response.code() < 500 && response.code() != 408 && response.code() != 429) {
                    var refusal = rejected("Dhan rejected order (HTTP " + response.code() + "); check broker permissions, static IP and order parameters",price);
                    refusal.setDefinitiveBrokerRejection(true);
                    return refusal;
                }
            } catch (Exception ignored) { /* Delivery may have occurred. Never resubmit. */ }
        }
        try {
            JSONObject found = lookup(session,CORRELATION_PREFIX+correlation);
            var result = accepted(found,null,price);
            if (result != null) return placementOutcome(result, session, side);
        } catch (Exception ignored) {}
        // Durable correlation handle lets the normal poller recover the real order after a lost response.
        return OrderPlacementResult.builder().success(true).orderId(CORRELATION_PREFIX+correlation).status("Pending")
            .attemptedPrice(price).rejectionReason("Dhan submission unconfirmed; reconciling correlation ID without resubmitting").build();
    }
    @Override public OrderPlacementResult modifyEntryOrder(TriggeredTradeSetupEntity trade, BrokerContext context, String orderId, double newPrice) {
        try {
            Session session = session(trade,context);
            JSONObject current = modificationOrder(session,trade,orderId);
            var instrument = instruments.resolve(trade);
            validateOrder(current,trade,instrument,orderId);
            String status = current.getString("orderStatus");
            if (!Set.of("PENDING","TRANSIT","PART_TRADED").contains(status)) return rejected("Dhan order is not pending",newPrice);
            String realId = current.getString("orderId");
            String orderType = current.getString("orderType");
            if (!Set.of("LIMIT","STOP_LOSS").contains(orderType)) return rejected("Unsupported Dhan modification order type",newPrice);
            BigDecimal limit = price(newPrice,instrument);
            BigDecimal trigger = "STOP_LOSS".equals(orderType) ? new BigDecimal(current.get("triggerPrice").toString()) : BigDecimal.ZERO;
            if (trigger.signum() > 0 && "BUY".equals(current.getString("transactionType")) && trigger.compareTo(limit)>0)
                return rejected("Dhan stop trigger must not exceed buy limit",newPrice);
            var body = new JSONObject().put("dhanClientId",session.clientId()).put("orderId",realId)
                .put("orderType",orderType).put("quantity",current.getLong("quantity"))
                .put("price",limit).put("triggerPrice",trigger).put("disclosedQuantity",0).put("validity","DAY");
            var response = client.exchange("PUT","/orders/"+safeId(realId),session.token(),body);
            if (!response.ok()) return rejected("Dhan modification was not acknowledged",newPrice);
            var result = accepted(response.json(),realId,newPrice);
            if (result == null) return rejected("Dhan modification was not acknowledged",newPrice);
            result.setOrderId(orderId);return result; // Preserve a correlation handle already stored by the engine.
        } catch (Exception ignored) { return rejected("Dhan modification unconfirmed; reconcile the existing order",newPrice); }
    }
    @Override public void cancelEntryOrder(TriggeredTradeSetupEntity trade, BrokerContext context, String orderId) {
        modificationSnapshot.remove();
        try {
            Session session = session(trade,context);
            JSONObject current = lookup(session,orderId);
            validateOrder(current,trade,instruments.resolve(trade),orderId);
            var response = client.exchange("DELETE","/orders/"+safeId(current.getString("orderId")),session.token(),null);
            if (!response.ok()) throw new IllegalStateException();
            // HTTP 202 acknowledges the request only. The normal poller must confirm cancellation/fills.
        } catch (Exception ignored) { throw new IllegalStateException("Dhan cancellation unconfirmed; reconcile existing order before replacement"); }
    }
    @Override public JSONObject fetchOrderStatus(TriggeredTradeSetupEntity trade, BrokerContext context, String orderId) {
        modificationSnapshot.remove();
        try {
            Session session = session(trade,context);
            JSONObject row = lookup(session,orderId);
            // Status polling remains available even if today's master download is unavailable.
            if (!session.clientId().equals(row.getString("dhanClientId"))) return null;
            if (!DhanInstrumentResolver.segment(trade.getExchange()).equals(row.getString("exchangeSegment"))
                    || !(orderId.equals(trade.getExitOrderId()) ? "SELL" : "BUY").equals(row.getString("transactionType"))) return null;
            JSONObject response=adapt(row,orderId,orderId.equals(trade.getExitOrderId()));
            if(response!=null)modificationSnapshot.set(new ModificationSnapshot(trade.getBrokerCredentialsId(),trade.getId(),
                orderId,session.clientId(),session.token(),snapshotTicker.getAsLong(),row.toString()));
            return response;
        } catch (Exception ignored) { return null; }
    }
    @Override public JSONObject normalizeOrderUpdate(TriggeredTradeSetupEntity trade, BrokerContext context, String orderId, JSONObject row) {
        try {
            Session session=session(trade,context);
            boolean correlation=orderId.startsWith(CORRELATION_PREFIX);
            if(!session.clientId().equals(row.getString("dhanClientId"))
                || (correlation ? !orderId.substring(CORRELATION_PREFIX.length()).equals(row.getString("correlationId")) : !orderId.equals(row.getString("orderId")))
                || !DhanInstrumentResolver.segment(trade.getExchange()).equals(row.getString("exchangeSegment"))
                || !(orderId.equals(trade.getExitOrderId()) ? "SELL" : "BUY").equals(row.getString("transactionType"))) return null;
            return adapt(row,orderId,orderId.equals(trade.getExitOrderId()));
        } catch(Exception ignored) {return null;}
    }
    private Session session(TriggeredTradeSetupEntity trade, BrokerContext context) {
        if (trade == null || context == null || context.getBrokerCredentialsId() == null || !"Dhan".equalsIgnoreCase(context.getBrokerName())
                || !Objects.equals(trade.getBrokerCredentialsId(),context.getBrokerCredentialsId())) throw new IllegalArgumentException();
        var broker = credentials.findById(context.getBrokerCredentialsId()).orElseThrow();
        if (!"Dhan".equalsIgnoreCase(broker.getBrokerName()) || broker.getAppUserId() == null || !broker.getAppUserId().equals(trade.getAppUserId())) throw new IllegalArgumentException();
        String clientId = crypto.decrypt(broker.getClientCode()).trim();
        if (!clientId.matches("[0-9]{1,20}") || !clientId.equals(context.getClientCode())) throw new IllegalArgumentException();
        var token = tokens.findLatestByBrokerAndBrokerCredentialsId("Dhan",broker.getId()).orElseThrow();
        if (token.getToken() == null || token.getToken().isBlank() || token.getExpiry() == null || !token.getExpiry().isAfter(Instant.now())) throw new IllegalArgumentException();
        return new Session(clientId,token.getToken(),Boolean.TRUE.equals(broker.getActive()) && Boolean.TRUE.equals(broker.getTradingEnabled()));
    }
    private JSONObject lookup(Session session,String orderId) throws Exception {
        boolean correlation = orderId != null && orderId.startsWith(CORRELATION_PREFIX);
        String id = safeId(correlation ? orderId.substring(CORRELATION_PREFIX.length()) : orderId);
        var response = client.exchange("GET","/orders/"+(correlation ? "external/" : "")+id,session.token(),null);
        if (!response.ok()) throw new IllegalArgumentException();
        JSONObject row = response.json();
        if (!session.clientId().equals(row.getString("dhanClientId"))
                || (correlation ? !id.equals(row.getString("correlationId")) : !id.equals(row.getString("orderId")))) throw new IllegalArgumentException();
        safeId(row.getString("orderId"));return row;
    }
    private void validateOrder(JSONObject row,TriggeredTradeSetupEntity trade,DhanInstrumentResolver.Instrument instrument,String orderId) {
        boolean exit = orderId.equals(trade.getExitOrderId());
        if (!instrument.securityId().equals(row.getString("securityId")) || !instrument.segment().equals(row.getString("exchangeSegment"))
                || !(exit ? "SELL" : "BUY").equals(row.getString("transactionType"))) throw new IllegalArgumentException();
    }
    private static long quantity(TriggeredTradeSetupEntity trade,DhanInstrumentResolver.Instrument instrument) {
        Long quantity = trade.getQuantity();
        if (quantity == null || quantity <= 0 || quantity > Integer.MAX_VALUE || quantity > instrument.freezeQuantity()
                || (instrument.segment().endsWith("FNO") && quantity % instrument.lotSize() != 0)) throw new IllegalArgumentException();
        return quantity; // Already units/shares in the engine, never multiply by lot size again.
    }
    private static BigDecimal price(double price,DhanInstrumentResolver.Instrument instrument) {
        if (!Double.isFinite(price) || price <= 0) throw new IllegalArgumentException();
        BigDecimal value = BigDecimal.valueOf(price);
        // Reject incompatible ticks rather than silently moving strategy limits.
        if (value.remainder(instrument.tickSize()).signum() != 0) throw new IllegalArgumentException();
        return value.stripTrailingZeros();
    }
    private static String safeId(String id) {
        if (id == null || id.length()>64 || !id.matches("[A-Za-z0-9_-]+") || Set.of("0","null","NA").contains(id)) throw new IllegalArgumentException();
        return id;
    }
    private static String correlation(Long credential,String submissionKey,String side) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest((credential+":"+submissionKey+":"+side).getBytes(StandardCharsets.UTF_8));
        return "sat-"+HexFormat.of().formatHex(hash).substring(0,24);
    }
    private static OrderPlacementResult pendingPlacement(OrderPlacementResult result) {
        // Even an inactive acknowledgement can have fills. Persist its ID and let polling reconcile it.
        result.setSuccess(true);result.setStatus("Pending");return result;
    }
    private OrderPlacementResult placementOutcome(OrderPlacementResult result, Session session, String side) {
        if (!result.isSuccess() && "BUY".equals(side)) {
            try {
                // Confirm absence of fills before treating an inactive acknowledgement as a failed entry.
                JSONObject row = lookup(session, result.getOrderId());
                long quantity = row.getLong("quantity");
                if ("REJECTED".equals(row.getString("orderStatus")) && "BUY".equals(row.getString("transactionType"))
                        && quantity > 0 && row.getLong("filledQty") == 0 && row.getLong("remainingQuantity") == quantity) {
                    result.setDefinitiveBrokerRejection(true);
                    return result;
                }
            } catch (Exception ignored) { /* Keep the order ID for normal fill reconciliation. */ }
        }
        return pendingPlacement(result);
    }
    private static OrderPlacementResult accepted(JSONObject row,String expectedId,double price) {
        String id = safeId(row.getString("orderId"));
        if (expectedId != null && !expectedId.equals(id)) return null;
        String status = row.getString("orderStatus");
        if (Set.of("REJECTED","CANCELLED","EXPIRED").contains(status)) return OrderPlacementResult.builder().success(false).orderId(id).status("Rejected").attemptedPrice(price).rejectionReason("Dhan order "+status.toLowerCase(Locale.ROOT)).build();
        if (!Set.of("TRANSIT","PENDING","PART_TRADED","TRADED").contains(status)) return null;
        // A placement/modify acknowledgement has no fill evidence: always poll before confirming execution.
        return OrderPlacementResult.builder().success(true).orderId(id).status("Pending").attemptedPrice(price).build();
    }
    private static JSONObject adapt(JSONObject row,String requestedId,boolean exit) {
        String status = row.getString("orderStatus");
        long quantity = row.getLong("quantity"), filled = row.getLong("filledQty"), remaining = row.getLong("remainingQuantity");
        double average = row.optDouble("averageTradedPrice",0);
        if (quantity <= 0 || filled < 0 || remaining < 0 || filled > quantity || remaining != quantity-filled) throw new IllegalArgumentException();
        boolean full = "TRADED".equals(status) && filled == quantity && remaining==0 && Double.isFinite(average) && average>0;
        if (!Set.of("TRANSIT","PENDING","PART_TRADED","TRADED","REJECTED","CANCELLED","EXPIRED").contains(status)) throw new IllegalArgumentException();
        boolean inactive = Set.of("REJECTED","CANCELLED","EXPIRED").contains(status);
        // A partly filled, inactive exit needs manual remaining-quantity reconciliation. Keep its ID and exposure.
        boolean partialInactiveExit=exit && inactive && filled>0 && remaining>0;
        if (partialInactiveExit) inactive=false;
        // Don't let generic filledQty fallbacks confirm malformed or contradictory responses.
        if (filled==quantity && !full) throw new IllegalArgumentException();
        var normalized = new JSONObject().put("orderId",requestedId).put("orderStatus",full ? "Fully Executed" : inactive ? "Rejected" : filled>0 ? "Partly Executed" : "Pending")
            .put("quantity",quantity).put("execQty",filled).put("openQty",remaining);
        if (filled>0) {
            if (!Double.isFinite(average) || average<=0) throw new IllegalArgumentException();
            normalized.put("avgPrice",average);
        }
        return new JSONObject().put("data",new JSONArray().put(normalized))
            .put("requiresExitReconciliation",partialInactiveExit).put("filledQuantity",filled).put("remainingQuantity",remaining);
    }
    private static OrderPlacementResult rejected(String reason,double price) {
        return OrderPlacementResult.builder().success(false).status("Rejected").attemptedPrice(price).rejectionReason(reason).build();
    }
}
