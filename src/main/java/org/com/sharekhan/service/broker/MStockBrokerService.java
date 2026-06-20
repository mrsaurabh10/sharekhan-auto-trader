package org.com.sharekhan.service.broker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.dto.BrokerContext;
import org.com.sharekhan.dto.OrderPlacementResult;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.service.MStockInstrumentResolver;
import org.com.sharekhan.util.CryptoService;
import org.com.sharekhan.util.ShareKhanOrderUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MStockBrokerService implements ModifiableEntryBrokerService, TriggerPriceEntryBrokerService, OrderStatusBrokerService {

    private static final String ORDER_URL = "https://api.mstock.trade/openapi/typea/orders/";
    private static final String ORDER_DETAILS_URL = "https://api.mstock.trade/openapi/typea/order/details";

    private final TokenStoreService tokenStoreService;
    private final CryptoService cryptoService;
    private final MStockInstrumentResolver instrumentResolver;

    @Value("${app.mstock.api-key:}")
    private String configuredApiKey;

    @Override
    public Broker getBroker() {
        return Broker.MSTOCK;
    }

    @Override
    public OrderPlacementResult placeOrder(TriggeredTradeSetupEntity trade, BrokerContext context, double ltp) {
        return placeMStockOrder(trade, context, ltp, "BUY", "LIMIT", null);
    }

    @Override
    public OrderPlacementResult placeExitOrder(TriggeredTradeSetupEntity trade, BrokerContext context, double exitPrice) {
        return placeMStockOrder(trade, context, exitPrice, "SELL", "LIMIT", null);
    }

    @Override
    public OrderPlacementResult placeTriggerPriceEntryOrder(TriggeredTradeSetupEntity trade,
                                                           BrokerContext context,
                                                           double entryPrice) {
        return placeMStockOrder(trade, context, entryPrice, "BUY", "SL", entryPrice);
    }

    @Override
    public OrderPlacementResult modifyEntryOrder(TriggeredTradeSetupEntity trade,
                                                 BrokerContext context,
                                                 String orderId,
                                                 double newPrice) {
        try {
            if (!isUsableOrderId(orderId)) {
                return rejected("MStock order id is required for modify", newPrice);
            }
            if (trade == null) {
                return rejected("Trade is required", newPrice);
            }
            if (trade.getQuantity() == null || trade.getQuantity() <= 0L) {
                return rejected("Quantity must be greater than zero", newPrice);
            }
            if (!Double.isFinite(newPrice) || newPrice <= 0d) {
                return rejected("Modify price must be greater than zero", newPrice);
            }

            InstrumentIdentity instrument = resolveInstrumentIdentity(trade);
            String transactionType = isExitOrder(trade, orderId) ? "SELL" : "BUY";
            Map<String, String> form = buildBaseOrderForm(trade, instrument, transactionType, "LIMIT", newPrice, null);
            form.put("disclosed_quantity", "0");
            form.put("trigger_price", "0");

            RequestCredentials credentials = resolveCredentials(context);
            HttpResult result = doPutForm(buildRegularOrderUrl(orderId), form, credentials);
            if (isTokenError(result)) {
                RequestCredentials refreshed = refreshCredentials(credentials);
                if (refreshed != null) {
                    credentials = refreshed;
                    result = doPutForm(buildRegularOrderUrl(orderId), form, credentials);
                }
            }

            return parseOrderResponse(result, newPrice, "MStock modify", "Pending");
        } catch (Exception e) {
            log.warn("MStock modify failed for trade {} order {}: {}",
                    trade != null ? trade.getId() : null, orderId, e.getMessage());
            log.debug("MStock modify failure", e);
            return rejected("MSTOCK_MODIFY_FAILED: " + e.getMessage(), newPrice);
        }
    }

    @Override
    public void cancelEntryOrder(TriggeredTradeSetupEntity trade, BrokerContext context, String orderId) {
        try {
            if (!isUsableOrderId(orderId)) {
                log.warn("MStock cancel skipped for trade {} because order id is not usable: {}",
                        trade != null ? trade.getId() : null, orderId);
                return;
            }

            RequestCredentials credentials = resolveCredentials(context);
            HttpResult result = doDelete(buildRegularOrderUrl(orderId), credentials);
            if (isTokenError(result)) {
                RequestCredentials refreshed = refreshCredentials(credentials);
                if (refreshed != null) {
                    credentials = refreshed;
                    result = doDelete(buildRegularOrderUrl(orderId), credentials);
                }
            }

            JSONObject root = parseJson(result != null ? result.body() : null);
            if (result != null && result.code() == 200 && root != null && "success".equalsIgnoreCase(root.optString("status", ""))) {
                log.info("🚫 Cancelled MStock order {} for trade {}", orderId, trade != null ? trade.getId() : null);
                return;
            }

            log.warn("MStock cancel failed for order {} http={} body={}",
                    orderId, result != null ? result.code() : null, result != null ? abbreviate(result.body()) : null);
        } catch (Exception e) {
            log.warn("Failed to cancel MStock order {} for trade {}: {}",
                    orderId, trade != null ? trade.getId() : null, e.getMessage());
            log.debug("MStock cancel failure", e);
        }
    }

    @Override
    public JSONObject fetchOrderStatus(TriggeredTradeSetupEntity trade, BrokerContext context, String orderId) {
        if (!StringUtils.hasText(orderId)) {
            return null;
        }

        try {
            RequestCredentials credentials = resolveCredentials(context);
            String url = buildOrderDetailsUrl(trade, orderId);
            HttpResult result = doGet(url, credentials);

            if (isTokenError(result)) {
                RequestCredentials refreshed = refreshCredentials(credentials);
                if (refreshed != null) {
                    credentials = refreshed;
                    result = doGet(url, credentials);
                }
            }

            if (result == null || result.code() != 200 || !StringUtils.hasText(result.body())) {
                log.warn("MStock order status failed for order {} http={} body={}",
                        orderId, result != null ? result.code() : null, result != null ? abbreviate(result.body()) : null);
                return null;
            }

            JSONObject root = new JSONObject(result.body());
            return adaptOrderDetailsResponse(root);
        } catch (Exception e) {
            log.warn("MStock order status fetch failed for trade {} order {}: {}",
                    trade != null ? trade.getId() : null, orderId, e.getMessage());
            log.debug("MStock order status failure", e);
            return null;
        }
    }

    private OrderPlacementResult placeMStockOrder(TriggeredTradeSetupEntity trade,
                                                  BrokerContext context,
                                                  double price,
                                                  String transactionType,
                                                  String orderType,
                                                  Double triggerPrice) {
        try {
            if (trade == null) {
                return rejected("Trade is required", price);
            }
            if (trade.getQuantity() == null || trade.getQuantity() <= 0L) {
                return rejected("Quantity must be greater than zero", price);
            }
            if (!Double.isFinite(price) || price <= 0d) {
                return rejected("Order price must be greater than zero", price);
            }

            InstrumentIdentity instrument = resolveInstrumentIdentity(trade);
            String variety = ShareKhanOrderUtil.isAfterHours() ? "amo" : "regular";

            Map<String, String> form = buildBaseOrderForm(trade, instrument, transactionType, orderType, price, triggerPrice);
            form.put("tag", buildOrderTag(trade));

            RequestCredentials credentials = resolveCredentials(context);
            HttpResult result = doPostForm(ORDER_URL + variety, form, credentials);
            if (isTokenError(result)) {
                RequestCredentials refreshed = refreshCredentials(credentials);
                if (refreshed != null) {
                    credentials = refreshed;
                    result = doPostForm(ORDER_URL + variety, form, credentials);
                }
            }

            return parseOrderResponse(result, price, "MStock order", "Pending");
        } catch (Exception e) {
            log.error("Error executing MStock {} order for trade {}: {}",
                    transactionType, trade != null ? trade.getId() : null, e.getMessage(), e);
            return rejected("Exception: " + e.getMessage(), price);
        }
    }

    private Map<String, String> buildBaseOrderForm(TriggeredTradeSetupEntity trade,
                                                   InstrumentIdentity instrument,
                                                   String transactionType,
                                                   String orderType,
                                                   double price,
                                                   Double triggerPrice) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("tradingsymbol", instrument.tradingSymbol());
        form.put("exchange", instrument.exchange());
        form.put("transaction_type", transactionType);
        form.put("order_type", orderType);
        form.put("quantity", String.valueOf(trade.getQuantity()));
        form.put("product", resolveProduct(trade, instrument.exchange()));
        form.put("validity", "DAY");
        form.put("price", formatPrice(price));
        if (triggerPrice != null) {
            form.put("trigger_price", formatPrice(triggerPrice));
        }
        return form;
    }

    private OrderPlacementResult parseOrderResponse(HttpResult result,
                                                    double attemptedPrice,
                                                    String operation,
                                                    String defaultStatus) {
        if (result == null) {
            return rejected(operation + " returned no response", attemptedPrice);
        }

        JSONObject root = parseJson(result.body());
        String apiStatus = root != null ? root.optString("status", "") : "";
        if (result.code() != 200 || !"success".equalsIgnoreCase(apiStatus)) {
            return OrderPlacementResult.builder()
                    .success(false)
                    .status("Rejected")
                    .attemptedPrice(attemptedPrice)
                    .rejectionReason(resolveErrorMessage(result, root))
                    .build();
        }

        JSONObject data = root.optJSONObject("data");
        String orderId = data != null ? data.optString("order_id", null) : null;
        if (!isUsableOrderId(orderId)) {
            return OrderPlacementResult.builder()
                    .success(false)
                    .status("Rejected")
                    .attemptedPrice(attemptedPrice)
                    .rejectionReason("MStock did not return a usable order_id")
                    .build();
        }

        return OrderPlacementResult.builder()
                .success(true)
                .orderId(orderId)
                .status(defaultStatus)
                .attemptedPrice(attemptedPrice)
                .build();
    }

    private InstrumentIdentity resolveInstrumentIdentity(TriggeredTradeSetupEntity trade) {
        Optional<String> key = trade.getScripCode() != null
                ? instrumentResolver.resolveInstrumentKey(trade.getScripCode())
                : Optional.empty();

        if (key.isPresent()) {
            String instrumentKey = key.get();
            int colon = instrumentKey.indexOf(':');
            if (colon > 0 && colon < instrumentKey.length() - 1) {
                return new InstrumentIdentity(
                        instrumentKey.substring(0, colon).trim().toUpperCase(Locale.ROOT),
                        instrumentKey.substring(colon + 1).trim());
            }
        }

        String exchange = normalizeExchange(trade.getExchange());
        String symbol = trade.getSymbol() != null ? trade.getSymbol().trim().toUpperCase(Locale.ROOT) : null;
        if (isEquityExchange(exchange) && StringUtils.hasText(symbol) && !symbol.contains("-")) {
            symbol = symbol + "-EQ";
        }

        if (!StringUtils.hasText(exchange) || !StringUtils.hasText(symbol)) {
            throw new IllegalStateException("Unable to resolve MStock trading symbol for trade " + trade.getId());
        }
        log.warn("Using fallback MStock instrument identity {}:{} for trade {}. Refresh MStock instrument master if this is unexpected.",
                exchange, symbol, trade.getId());
        return new InstrumentIdentity(exchange, symbol);
    }

    private RequestCredentials resolveCredentials(BrokerContext context) {
        TokenStoreService.TokenInfo tokenInfo = null;
        if (context != null && context.getBrokerCredentialsId() != null) {
            tokenInfo = tokenStoreService.getTokenInfoForBrokerCredentials(Broker.MSTOCK, context.getBrokerCredentialsId());
        }
        if (tokenInfo == null && context != null && context.getCustomerId() != null) {
            tokenInfo = tokenStoreService.getTokenInfo(Broker.MSTOCK, context.getCustomerId());
        }
        if (tokenInfo == null) {
            tokenInfo = tokenStoreService.getFirstNonExpiredTokenInfo(Broker.MSTOCK);
        }

        String accessToken = tokenInfo != null ? tokenInfo.getToken() : null;
        if (!StringUtils.hasText(accessToken)) {
            accessToken = context != null && context.getCustomerId() != null
                    ? tokenStoreService.getAccessToken(Broker.MSTOCK, context.getCustomerId())
                    : tokenStoreService.getAccessToken(Broker.MSTOCK);
        }
        if (!StringUtils.hasText(accessToken)) {
            throw new IllegalStateException("No MStock access token available. Please authenticate first.");
        }

        String apiKey = firstText(
                context != null ? context.getApiKey() : null,
                resolveTokenInfoApiKey(tokenInfo),
                configuredApiKey);
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("MStock API key is not configured");
        }

        if (tokenInfo == null && context != null) {
            tokenInfo = new TokenStoreService.TokenInfo(accessToken, apiKey,
                    context.getCustomerId(), null, context.getBrokerCredentialsId());
        }
        return new RequestCredentials(accessToken, apiKey, tokenInfo);
    }

    private RequestCredentials refreshCredentials(RequestCredentials current) {
        TokenStoreService.TokenInfo refreshed = tokenStoreService.refreshToken(
                Broker.MSTOCK, current != null ? current.tokenInfo() : null);
        if (refreshed == null || !StringUtils.hasText(refreshed.getToken())) {
            return null;
        }
        String apiKey = firstText(resolveTokenInfoApiKey(refreshed), current != null ? current.apiKey() : null, configuredApiKey);
        return new RequestCredentials(refreshed.getToken(), apiKey, refreshed);
    }

    private HttpResult doPostForm(String urlStr, Map<String, String> form, RequestCredentials credentials) throws Exception {
        byte[] body = encodeForm(form).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = openConnection(urlStr, "POST", credentials);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
        return readResponse(conn);
    }

    private HttpResult doPutForm(String urlStr, Map<String, String> form, RequestCredentials credentials) throws Exception {
        byte[] body = encodeForm(form).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = openConnection(urlStr, "PUT", credentials);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
        return readResponse(conn);
    }

    private HttpResult doGet(String urlStr, RequestCredentials credentials) throws Exception {
        HttpURLConnection conn = openConnection(urlStr, "GET", credentials);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        return readResponse(conn);
    }

    private HttpResult doDelete(String urlStr, RequestCredentials credentials) throws Exception {
        HttpURLConnection conn = openConnection(urlStr, "DELETE", credentials);
        return readResponse(conn);
    }

    private HttpURLConnection openConnection(String urlStr, String method, RequestCredentials credentials) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("X-Mirae-Version", "1");
        conn.setRequestProperty("Authorization", "token " + credentials.apiKey() + ":" + credentials.accessToken());
        return conn;
    }

    private HttpResult readResponse(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300
                        ? conn.getInputStream()
                        : (conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream()),
                StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
            return new HttpResult(code, out.toString().trim());
        }
    }

    private String buildOrderDetailsUrl(TriggeredTradeSetupEntity trade, String orderId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("order_no", orderId);
        String segment = resolveSegment(trade != null ? trade.getExchange() : null);
        if (StringUtils.hasText(segment)) {
            params.put("segment", segment);
        }
        return ORDER_DETAILS_URL + "?" + encodeForm(params);
    }

    private String buildRegularOrderUrl(String orderId) {
        return ORDER_URL + "regular/" + URLEncoder.encode(orderId, StandardCharsets.UTF_8);
    }

    private JSONObject adaptOrderDetailsResponse(JSONObject root) {
        if (root == null) {
            return null;
        }
        JSONArray data = root.optJSONArray("data");
        if (data == null) {
            return root;
        }
        for (int i = 0; i < data.length(); i++) {
            JSONObject row = data.optJSONObject(i);
            if (row == null) {
                continue;
            }
            copyIfMissing(row, "status", "orderStatus");
            copyIfMissing(row, "average_price", "avgPrice");
            copyIfMissing(row, "price", "orderPrice");
            copyIfMissing(row, "filled_quantity", "execQty");
            copyIfMissing(row, "pending_quantity", "openQty");
        }
        return root;
    }

    private void copyIfMissing(JSONObject row, String sourceField, String targetField) {
        if (!row.has(targetField) && row.has(sourceField) && !row.isNull(sourceField)) {
            row.put(targetField, row.get(sourceField));
        }
    }

    private String encodeForm(Map<String, String> form) {
        StringBuilder body = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (!StringUtils.hasText(entry.getValue())) {
                continue;
            }
            if (!first) {
                body.append("&");
            }
            first = false;
            body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            body.append("=");
            body.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return body.toString();
    }

    private String resolveProduct(TriggeredTradeSetupEntity trade, String exchange) {
        if (trade != null && Boolean.TRUE.equals(trade.getIntraday())) {
            return "MIS";
        }
        if (isDerivativeExchange(exchange)) {
            return "NRML";
        }
        return "CNC";
    }

    private boolean isExitOrder(TriggeredTradeSetupEntity trade, String orderId) {
        if (trade == null) {
            return false;
        }
        return (StringUtils.hasText(orderId) && orderId.equals(trade.getExitOrderId()))
                || TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(trade.getStatus())
                || TriggeredTradeStatus.TARGET_ORDER_PLACED.equals(trade.getStatus())
                || TriggeredTradeStatus.EXIT_TRIGGERED.equals(trade.getStatus());
    }

    private String normalizeExchange(String exchange) {
        if (!StringUtils.hasText(exchange)) {
            return null;
        }
        String normalized = exchange.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "NC" -> "NSE";
            case "BC" -> "BSE";
            case "NF" -> "NFO";
            case "BF" -> "BFO";
            default -> normalized;
        };
    }

    private String resolveSegment(String exchange) {
        String normalized = normalizeExchange(exchange);
        if (isEquityExchange(normalized)) {
            return "E";
        }
        if (isDerivativeExchange(normalized)) {
            return "D";
        }
        return null;
    }

    private boolean isEquityExchange(String exchange) {
        return "NSE".equalsIgnoreCase(exchange) || "BSE".equalsIgnoreCase(exchange);
    }

    private boolean isDerivativeExchange(String exchange) {
        return "NFO".equalsIgnoreCase(exchange) || "BFO".equalsIgnoreCase(exchange);
    }

    private String buildOrderTag(TriggeredTradeSetupEntity trade) {
        Long id = trade != null ? trade.getId() : null;
        Long requestId = trade != null ? trade.getTriggerRequestId() : null;
        String suffix = id != null ? String.valueOf(id) : requestId != null ? "REQ" + requestId : String.valueOf(System.currentTimeMillis());
        return "SAT-" + suffix;
    }

    private String formatPrice(double price) {
        return BigDecimal.valueOf(price)
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private String resolveTokenInfoApiKey(TokenStoreService.TokenInfo tokenInfo) {
        if (tokenInfo == null || !StringUtils.hasText(tokenInfo.getApiKey())) {
            return null;
        }
        try {
            return cryptoService.decrypt(tokenInfo.getApiKey());
        } catch (Exception e) {
            return tokenInfo.getApiKey();
        }
    }

    private boolean isTokenError(HttpResult result) {
        if (result == null) {
            return false;
        }
        if (result.code() == 401) {
            return true;
        }
        JSONObject root = parseJson(result.body());
        return root != null && "TokenException".equalsIgnoreCase(root.optString("error_type", ""));
    }

    private JSONObject parseJson(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveErrorMessage(HttpResult result, JSONObject root) {
        if (root != null) {
            String message = root.optString("message", "");
            String errorType = root.optString("error_type", "");
            if (StringUtils.hasText(message) && StringUtils.hasText(errorType)) {
                return errorType + ": " + message;
            }
            if (StringUtils.hasText(message)) {
                return message;
            }
        }
        return "MStock order request failed (http " + result.code() + "): " + abbreviate(result.body());
    }

    private OrderPlacementResult rejected(String reason, double attemptedPrice) {
        return OrderPlacementResult.builder()
                .success(false)
                .status("Rejected")
                .attemptedPrice(attemptedPrice)
                .rejectionReason(reason)
                .build();
    }

    private boolean isUsableOrderId(String orderId) {
        if (!StringUtils.hasText(orderId)) {
            return false;
        }
        String normalized = orderId.trim();
        return !"0".equals(normalized)
                && !"NA".equalsIgnoreCase(normalized)
                && !"null".equalsIgnoreCase(normalized);
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String abbreviate(String value) {
        if (value == null) {
            return null;
        }
        String flattened = value.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return flattened.length() <= 300 ? flattened : flattened.substring(0, 300) + "...";
    }

    private record RequestCredentials(String accessToken,
                                      String apiKey,
                                      TokenStoreService.TokenInfo tokenInfo) {
    }

    private record InstrumentIdentity(String exchange, String tradingSymbol) {
    }

    private record HttpResult(int code, String body) {
    }
}
