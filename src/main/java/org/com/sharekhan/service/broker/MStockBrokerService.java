package org.com.sharekhan.service.broker;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.mstock.MStockSubmissionStore;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.service.MStockInstrumentResolver;
import org.com.sharekhan.util.*;
import org.json.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Slf4j @Service @RequiredArgsConstructor
public class MStockBrokerService implements ModifiableEntryBrokerService, TriggerPriceEntryBrokerService,
        OrderStatusBrokerService, RefusedExitRecovery {
    private static final String UNKNOWN = "MSTOCK-TAG-";
    private final TokenStoreService tokens;
    private final CryptoService crypto;
    private final MStockInstrumentResolver instruments;
    private final MStockInstrumentRepository master;
    private final BrokerCredentialsRepository accounts;
    private final MStockSubmissionStore submissions;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private String baseUrl = "https://api.mstock.trade/openapi/typea";

    record Session(String token, String apiKey, boolean entryEnabled) {
        @Override public String toString() { return "MStockSession[redacted]"; }
    }
    record Response(int code, JSONObject json) {
        boolean ok() { return code == 200 && json != null && "success".equalsIgnoreCase(json.optString("status")); }
    }
    @Override public Broker getBroker() { return Broker.MSTOCK; }
    @Override public OrderPlacementResult placeOrder(TriggeredTradeSetupEntity trade, BrokerContext context, double price) {
        return place(trade, context, price, null, false);
    }
    @Override public OrderPlacementResult placeExitOrder(TriggeredTradeSetupEntity trade, BrokerContext context, double price) {
        return place(trade, context, price, null, true);
    }
    @Override public OrderPlacementResult placeTriggerPriceEntryOrder(TriggeredTradeSetupEntity trade, BrokerContext context,
                                                                    double trigger, double limit) {
        return place(trade, context, limit, trigger, false);
    }
    @Override public java.util.OptionalDouble availableOptionBuyingFunds(TriggeredTradeSetupEntity trade, BrokerContext context) {
        try {
            var response = exchange("GET", "/user/fundsummary", Map.of(), session(trade, context));
            return response.ok() ? OptionBuyingFunds.mstock(response.json()) : java.util.OptionalDouble.empty();
        } catch (Exception unavailable) { return java.util.OptionalDouble.empty(); }
    }
    private Session session(TriggeredTradeSetupEntity trade, BrokerContext context) {
        if (trade == null || trade.getAppUserId() == null || context == null || context.getBrokerCredentialsId() == null
                || !"MStock".equalsIgnoreCase(context.getBrokerName())
                || !Objects.equals(trade.getBrokerCredentialsId(), context.getBrokerCredentialsId()))
            throw new IllegalArgumentException("A scoped mStock account is required");
        var account = accounts.findById(context.getBrokerCredentialsId()).orElseThrow();
        if (!"MStock".equalsIgnoreCase(account.getBrokerName()) || !trade.getAppUserId().equals(account.getAppUserId()))
            throw new IllegalArgumentException("mStock account ownership mismatch");
        var token = tokens.getSavedTokenInfoForBrokerCredentials(Broker.MSTOCK, account.getId());
        if (token == null || !trade.getAppUserId().equals(token.getAppUserId())
                || !account.getId().equals(token.getBrokerCredentialsId()) || !text(token.getToken()))
            throw new IllegalStateException("Verify this mStock connection first");
        String apiKey = crypto.decrypt(account.getApiKey());
        if (!text(apiKey)) throw new IllegalStateException("Missing mStock API key");
        return new Session(token.getToken(), apiKey,
                Boolean.TRUE.equals(account.getActive()) && Boolean.TRUE.equals(account.getTradingEnabled()));
    }
    private OrderPlacementResult place(TriggeredTradeSetupEntity trade, BrokerContext context, double price, Double trigger, boolean exit) {
        Session session; MStockInstrumentEntity instrument; Map<String,String> form;
        String key; int generation = 1; String tag;
        try {
            rejectManagedProduct(trade);
            session = session(trade, context);
            if (!exit && !session.entryEnabled()) throw new IllegalArgumentException("Enable mStock trading before entry");
            instrument = instrument(trade);
            validateQuantity(trade, instrument);
            validatePrice(price, instrument);
            if (trigger != null) {
                validatePrice(trigger, instrument);
                if (TradeSide.isShort(trade) ? trigger < price : trigger > price)
                    throw new IllegalArgumentException("Stop-limit price is on the wrong side of the trigger");
            }
            if (!exit) {
                if (TradeSide.isShort(trade) && (!Boolean.TRUE.equals(trade.getIntraday()) || derivative(instrument.getExchange())))
                    throw new IllegalArgumentException("Short entries require intraday cash equity");
                trade.setBrokerExecutionProduct(TradeSide.isShort(trade) ? "MIS" : derivative(instrument.getExchange()) ? "NRML" : "CNC");
            }
            trade.setBrokerSecurityId(instrument.getInstrumentKey());
            key = submissionKey(trade, exit);
            var old = submissions.latest(context.getBrokerCredentialsId(), key);
            if (old.isPresent()) {
                var attempt = old.get();
                if (!exit && "REFUSED".equals(attempt.outcome())) {
                    var refusal = rejected("mStock previously refused this entry", price);
                    refusal.setDefinitiveBrokerRejection(true);
                    return refusal;
                }
                if (!exit || !Set.of("REFUSED", "INACTIVE_ZERO_FILL").contains(Objects.toString(attempt.outcome(), "")))
                    return pending(text(attempt.orderId()) ? attempt.orderId() : UNKNOWN + attempt.tag(), price);
                generation = attempt.generation() + 1;
            }
            if (exit) {
                if (text(trade.getExitOrderId())) throw new IllegalArgumentException("Reconcile the existing exit before replacement");
                verifyPosition(trade, instrument, session);
            }
            tag = tag(context.getBrokerCredentialsId(), key, generation);
            form = buildBaseOrderForm(trade, instrument, exit ? TradeSide.exit(trade) : TradeSide.entry(trade),
                    trigger == null ? "LIMIT" : "SL", price, trigger);
            form.put("tag", tag);
            try { submissions.claim(tag, context.getBrokerCredentialsId(), key, generation); }
            catch (DataIntegrityViolationException duplicate) { return pending(UNKNOWN + tag, price); }
        } catch (Exception invalid) {
            return rejected("mStock order not sent: verify account, session, contract, product, quantity and tick-aligned price", price);
        }
        // No automatic authentication or POST retry: delivery can be ambiguous even after a server error.
        try {
            var response = exchange("POST", "/orders/" + orderVariety(), form, session);
            String orderId = response.ok() ? response.json().optJSONObject("data") == null ? null
                    : response.json().getJSONObject("data").optString("order_id", null) : null;
            if (usableId(orderId)) {
                submissions.accepted(tag, orderId);
                return pending(orderId, price);
            }
            if (definitiveRefusal(response)) {
                submissions.inactive(tag, "REFUSED");
                var refusal = rejected("mStock refused order: " + safeError(response), price);
                refusal.setDefinitiveBrokerRejection(true);
                return refusal;
            }
        } catch (Exception unknownDelivery) {
            log.warn("mStock submission needs reconciliation for credential {} tag {}", context.getBrokerCredentialsId(), tag);
        }
        return pending(UNKNOWN + tag, price);
    }
    String orderVariety() { return ShareKhanOrderUtil.isAfterHours() ? "amo" : "regular"; }
    @Override public JSONObject fetchDayOrders(BrokerContext context) {
        try {
            if (context == null) return null;
            var account = accounts.findById(context.getBrokerCredentialsId()).orElseThrow();
            var scopedTrade = TriggeredTradeSetupEntity.builder().appUserId(account.getAppUserId()).brokerCredentialsId(account.getId()).build();
            var response = exchange("GET", "/orders", Map.of(), session(scopedTrade, context));
            return response.ok() && response.json().optJSONArray("data") != null ? response.json() : null;
        } catch (Exception unavailable) { return null; }
    }
    private String submissionKey(TriggeredTradeSetupEntity trade, boolean exit) {
        if (exit && trade.getId() != null && trade.getId() > 0) return "EXIT:" + trade.getId();
        if (!exit && trade.getTriggerRequestId() != null && trade.getTriggerRequestId() > 0)
            return "ENTRY:REQUEST:" + trade.getTriggerRequestId() + ":" + Objects.toString(trade.getGapReentryCount(), "0");
        if (!exit && trade.getId() != null && trade.getId() > 0) return "ENTRY:TRADE:" + trade.getId();
        throw new IllegalArgumentException("Persist the trade request before submission");
    }
    private static String tag(Long credential, String key, int generation) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest((credential + ":" + key + ":" + generation).getBytes(StandardCharsets.UTF_8));
        return "MS" + HexFormat.of().formatHex(hash).substring(0, 18);
    }
    private MStockInstrumentEntity instrument(TriggeredTradeSetupEntity trade) {
        String key = instruments.resolveInstrumentKey(trade.getScripCode()).orElseThrow();
        var row = master.findByInstrumentKey(key).orElseThrow();
        String exchange = normalizeExchange(trade.getExchange());
        if (!Set.of("NSE", "BSE", "NFO", "BFO").contains(exchange) || !exchange.equals(row.getExchange())
                || !text(row.getTradingSymbol()) || !key.equals(exchange + ":" + row.getTradingSymbol())
                || (trade.getBrokerSecurityId() != null && !key.equals(trade.getBrokerSecurityId())))
            throw new IllegalArgumentException("mStock contract mismatch");
        if (row.getInstrumentType() == null || Set.of("INDEX", "INDICES").contains(row.getInstrumentType().toUpperCase(Locale.ROOT)))
            throw new IllegalArgumentException("Instrument is not tradable");
        return row;
    }
    private void validateQuantity(TriggeredTradeSetupEntity trade, MStockInstrumentEntity instrument) {
        if (trade.getQuantity() == null || trade.getQuantity() <= 0 || trade.getQuantity() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid quantity");
        if (derivative(instrument.getExchange()) && (instrument.getLotSize() == null || instrument.getLotSize() <= 0
                || trade.getQuantity() % instrument.getLotSize() != 0)) throw new IllegalArgumentException("Invalid contract lots");
    }
    private void validatePrice(double price, MStockInstrumentEntity instrument) {
        if (!Double.isFinite(price) || price <= 0 || instrument.getTickSize() == null || !Double.isFinite(instrument.getTickSize())
                || instrument.getTickSize() <= 0 || BigDecimal.valueOf(price).remainder(BigDecimal.valueOf(instrument.getTickSize())).signum() != 0)
            throw new IllegalArgumentException("Invalid tick-aligned price");
    }
    private Map<String,String> buildBaseOrderForm(TriggeredTradeSetupEntity trade, MStockInstrumentEntity instrument,
            String side, String type, double price, Double trigger) {
        Map<String,String> form = new LinkedHashMap<>();
        form.put("tradingsymbol", instrument.getTradingSymbol()); form.put("exchange", instrument.getExchange());
        form.put("transaction_type", side); form.put("order_type", type);
        form.put("quantity", trade.getQuantity().toString()); form.put("product", product(trade, instrument));
        form.put("validity", "DAY"); form.put("price", BigDecimal.valueOf(price).toPlainString());
        if (trigger != null) form.put("trigger_price", BigDecimal.valueOf(trigger).toPlainString());
        return form;
    }
    private String product(TriggeredTradeSetupEntity trade, MStockInstrumentEntity instrument) {
        String stored = trade.getBrokerExecutionProduct();
        if (stored == null) return Boolean.TRUE.equals(trade.getIntraday()) ? "MIS" : derivative(instrument.getExchange()) ? "NRML" : "CNC";
        if (!Set.of("MIS", derivative(instrument.getExchange()) ? "NRML" : "CNC").contains(stored))
            throw new IllegalArgumentException("Invalid stored mStock product");
        return stored;
    }
    private void verifyPosition(TriggeredTradeSetupEntity trade, MStockInstrumentEntity instrument, Session session) throws Exception {
        var response = exchange("GET", "/portfolio/positions", Map.of(), session);
        if (!response.ok()) throw new IllegalStateException("Position unavailable");
        var rows = response.json().getJSONObject("data").getJSONArray("net");
        long quantity = 0;
        for (int i = 0; i < rows.length(); i++) {
            var row = rows.getJSONObject(i);
            if (instrument.getTradingSymbol().equals(row.optString("tradingsymbol")) && instrument.getExchange().equals(row.optString("exchange"))
                    && product(trade, instrument).equals(row.optString("product"))) quantity += units(row, "quantity");
        }
        if (TradeSide.isShort(trade) ? quantity > -trade.getQuantity() : quantity < trade.getQuantity())
            throw new IllegalStateException("Insufficient verified position for exit");
    }
    @Override public OrderPlacementResult modifyEntryOrder(TriggeredTradeSetupEntity trade, BrokerContext context, String orderId, double price) {
        try {
            rejectManagedProduct(trade);
            var session = session(trade, context);
            var row = lookup(trade, session, orderId);
            if (row == null || terminal(row)) return rejected("mStock order is unavailable or terminal", price);
            var instrument = instrument(trade); validatePrice(price, instrument);
            // Preserve broker quantity, product, side and stop trigger; never turn an SL into a LIMIT accidentally.
            Map<String,String> form = new LinkedHashMap<>();
            String type = row.getString("order_type");
            if (!Set.of("LIMIT", "SL").contains(type)) return rejected("Unsupported mStock modification", price);
            form.put("order_type", type); form.put("quantity", Long.toString(units(row, "quantity")));
            form.put("price", BigDecimal.valueOf(price).toPlainString()); form.put("validity", row.getString("validity"));
            form.put("disclosed_quantity", Long.toString(row.optLong("disclosed_quantity", 0)));
            form.put("trigger_price", "SL".equals(type) ? row.get("trigger_price").toString() : "0");
            if ("SL".equals(type)) {
                double trigger = row.getDouble("trigger_price");
                if ("BUY".equals(row.getString("transaction_type")) ? trigger > price : trigger < price)
                    return rejected("Modify limit crosses the stop trigger", price);
            }
            var response = exchange("PUT", "/orders/regular/" + encode(row.getString("order_id")), form, session);
            return response.ok() ? pending(row.getString("order_id"), price) : rejected("mStock modification was not acknowledged", price);
        } catch (Exception unavailable) { return rejected("mStock modification unavailable; reconcile order status", price); }
    }
    @Override public void cancelEntryOrder(TriggeredTradeSetupEntity trade, BrokerContext context, String orderId) {
        try {
            rejectManagedProduct(trade);
            var session = session(trade, context);
            var row = lookup(trade, session, orderId);
            if (row == null) throw new IllegalStateException("Order unavailable");
            if (terminal(row)) return;
            var response = exchange("DELETE", "/orders/regular/" + encode(row.getString("order_id")), Map.of(), session);
            if (!response.ok()) throw new IllegalStateException("Cancellation not acknowledged");
        } catch (Exception unavailable) { throw new IllegalStateException("mStock cancellation unconfirmed; reconcile before replacement"); }
    }
    @Override public JSONObject fetchOrderStatus(TriggeredTradeSetupEntity trade, BrokerContext context, String orderId) {
        try {
            var row = lookup(trade, session(trade, context), orderId);
            if (row == null) return null;
            boolean exit = orderId.equals(trade.getExitOrderId());
            long filled = units(row, "filled_quantity"), quantity = units(row, "quantity");
            if (terminal(row) && filled == 0) {
                var old = submissions.latest(context.getBrokerCredentialsId(), submissionKey(trade, exit));
                if (old.isPresent() && (row.getString("order_id").equals(old.get().orderId()) || orderId.equals(UNKNOWN + old.get().tag())))
                    submissions.inactive(old.get().tag(), "INACTIVE_ZERO_FILL");
            }
            if (filled > 0 && (!Double.isFinite(row.optDouble("average_price", 0)) || row.optDouble("average_price", 0) <= 0)) return null;
            String status = "Pending";
            // A terminal partial exit still represents an open position and must not authorize another full sell.
            if (exit && terminal(row) && filled > 0 && filled < quantity) status = "Pending reconciliation";
            else if (terminal(row) && filled == 0) status = "Rejected";
            else if (filled == quantity && filled > 0 && row.optDouble("average_price", 0) > 0) status = "Fully Executed";
            else if (!exit && terminal(row) && filled > 0 && filled < quantity) status = "Rejected";
            row.put("orderStatus", status).put("execQty", filled).put("openQty", units(row, "pending_quantity"))
                .put("avgPrice", row.optDouble("average_price", 0)).put("orderPrice", row.optDouble("price", 0));
            return new JSONObject().put("status", "success").put("data", new JSONArray().put(row))
                .put("requiresExitReconciliation", exit && terminal(row) && filled > 0 && filled < quantity)
                .put("filledQuantity", filled).put("remainingQuantity", quantity - filled);
        } catch (Exception unavailable) { return null; }
    }
    private JSONObject lookup(TriggeredTradeSetupEntity trade, Session session, String handle) throws Exception {
        if (!text(handle)) throw new IllegalArgumentException("Missing order ID");
        // The day book provides one current snapshot per order, avoiding stale history rows winning over cancellation.
        var response = exchange("GET", "/orders", Map.of(), session);
        if (!response.ok()) throw new IllegalStateException("mStock order book unavailable");
        var rows = response.json().getJSONArray("data");
        JSONObject found = null;
        for (int i = 0; i < rows.length(); i++) {
            var row = rows.getJSONObject(i);
            boolean matches = handle.startsWith(UNKNOWN) ? handle.substring(UNKNOWN.length()).equals(row.optString("tag"))
                    : handle.equals(row.optString("order_id"));
            if (matches) {
                if (found != null) throw new IllegalStateException("Ambiguous mStock order identity");
                validateOrder(trade, row, handle.equals(trade.getExitOrderId()));
                found = new JSONObject(row.toString());
            }
        }
        return found;
    }
    private void validateOrder(TriggeredTradeSetupEntity trade, JSONObject row, boolean exit) {
        var instrument = instrument(trade);
        if (!instrument.getTradingSymbol().equals(row.getString("tradingsymbol")) || !instrument.getExchange().equals(row.getString("exchange"))
                || !product(trade, instrument).equals(row.getString("product"))
                || !(exit ? TradeSide.exit(trade) : TradeSide.entry(trade)).equals(row.getString("transaction_type"))
                || !usableId(row.optString("order_id")) || units(row, "quantity") != trade.getQuantity())
            throw new IllegalStateException("mStock order identity mismatch");
        long filled = units(row, "filled_quantity"), pending = units(row, "pending_quantity");
        if (filled < 0 || pending < 0 || filled + pending > trade.getQuantity()) throw new IllegalStateException("Invalid order quantities");
    }
    @Override public boolean hasDefinitivelyRefusedExit(TriggeredTradeSetupEntity trade, BrokerContext context) {
        try {
            session(trade, context);
            return submissions.latest(context.getBrokerCredentialsId(), submissionKey(trade, true))
                .map(a -> "REFUSED".equals(a.outcome())).orElse(false);
        } catch (Exception unavailable) { return false; }
    }
    private static boolean terminal(JSONObject row) {
        String status = row.getString("status").toUpperCase(Locale.ROOT);
        return status.contains("CANCEL") || status.contains("REJECT") || status.contains("EXPIRE")
                || Set.of("COMPLETE", "COMPLETED", "TRADED", "FULLY EXECUTED").contains(status);
    }
    private static long units(JSONObject row, String key) { return new BigDecimal(row.get(key).toString()).longValueExact(); }
    private static boolean definitiveRefusal(Response response) {
        if (response.json() == null || !"error".equalsIgnoreCase(response.json().optString("status"))) return false;
        return (response.code() == 200 || response.code() >= 400 && response.code() < 500)
                && response.code() != 408 && response.code() != 429
                && Set.of("InputException", "TokenException", "APIKeyException", "PermissionException", "OrderException")
                    .contains(response.json().optString("error_type"));
    }
    private static String safeError(Response response) {
        // Broker messages can echo credentials or request fields. Return only the documented error category.
        return response.json().optString("error_type", "BrokerError") + " (HTTP " + response.code() + ")";
    }
    private static void rejectManagedProduct(TriggeredTradeSetupEntity trade) {
        if (trade == null || text(trade.getBrokerProductType())) throw new IllegalArgumentException("Broker-managed products require Sharekhan");
    }
    private static boolean derivative(String exchange) { return Set.of("NFO", "BFO").contains(exchange); }
    private static String normalizeExchange(String value) {
        if (value == null) return "";
        return switch (value.toUpperCase(Locale.ROOT)) { case "NC" -> "NSE"; case "BC" -> "BSE"; case "NF" -> "NFO"; case "BF" -> "BFO"; default -> value.toUpperCase(Locale.ROOT); };
    }
    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static boolean usableId(String value) { return text(value) && value.matches("[0-9]+") && !value.matches("0+"); }
    private static OrderPlacementResult pending(String id, double price) {
        return OrderPlacementResult.builder().success(true).orderId(id).status("Pending").attemptedPrice(price).build();
    }
    private static OrderPlacementResult rejected(String reason, double price) {
        return OrderPlacementResult.builder().success(false).status("Rejected").rejectionReason(reason).attemptedPrice(price).build();
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    Response exchange(String method, String path, Map<String,String> form, Session session) throws Exception {
        String body = form.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(java.util.stream.Collectors.joining("&"));
        var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
                .header("X-Mirae-Version", "1").header("Authorization", "token " + session.apiKey() + ":" + session.token())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JSONObject json;
        try { json = new JSONObject(response.body()); } catch (JSONException invalid) { json = null; }
        return new Response(response.statusCode(), json);
    }
}
