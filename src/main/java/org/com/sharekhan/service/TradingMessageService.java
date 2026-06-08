package org.com.sharekhan.service;

import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.dto.StockAtrTradeRequest;
import org.com.sharekhan.dto.CloseTradesRequest;
import org.com.sharekhan.dto.CloseTradesResponse;
import org.com.sharekhan.parser.*;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class TradingMessageService {

    @Autowired
    private TradeExecutionService tradingExecutorService;

    @Autowired
    private BrokerCredentialsService brokerCredentialsService;

    @Autowired
    private UserConfigService userConfigService;

    @Autowired(required = false)
    private TelegramNotificationService telegramNotificationService;

    @Autowired
    private TriggerTradeRequestRepository triggerTradeRequestRepository;

    @Autowired
    private TriggeredTradeSetupRepository triggeredTradeSetupRepository;

    @Autowired
    private TradeCloseService tradeCloseService;

    @Autowired
    private StockAtrTradeService stockAtrTradeService;

    private final TradingSignalParser parserChain = new ParserChain(
            new StockAtrSignalParser(),
            new SpotTelegramSignalParser(),
            new TelegramSignalParser(),
            new WhatsappSignalParser(),
            new AartiSignalParser(),
            new SharekhanSignalParser()
    );

    // Simple in-memory dedupe store to prevent duplicate processing of the same Telegram message
    // key: uniqueId (e.g., "telegram:<chatId>:<messageId>") -> timestamp millis when processed
    private final ConcurrentMap<String, Long> processedMessageIds = new ConcurrentHashMap<>();
    private static final long DEDUPE_TTL_MS = 5 * 60 * 1000L; // keep dedupe keys for 5 minutes

    // Scheduled cleanup to prune old entries
    @Scheduled(fixedDelay = 60_000)
    public void cleanupProcessedMessageIds() {
        long cutoff = System.currentTimeMillis() - DEDUPE_TTL_MS;
        List<String> toRemove = processedMessageIds.entrySet().stream()
                .filter(e -> e.getValue() < cutoff)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        toRemove.forEach(processedMessageIds::remove);
    }

    /**
     * Backwards-compatible handler (no unique id). This will always attempt parsing.
     */
    public void handleRawMessage(String message, String source) {
        handleRawMessage(message, source, null);
    }

    /**
     * Primary handler that supports optional uniqueId for idempotency.
     * uniqueId should be of form: "telegram:<chatId>:<messageId>". If provided,
     * duplicate messages within a short TTL will be ignored.
     */
    public void handleRawMessage(String message, String source, String uniqueId) {
        if (uniqueId != null) {
            long now = System.currentTimeMillis();
            Long prev = processedMessageIds.putIfAbsent(uniqueId, now);
            if (prev != null) {
                // already processed recently
                long age = now - prev;
                if (age <= DEDUPE_TTL_MS) {
                    System.out.println("⏭️ Duplicate message ignored for uniqueId=" + uniqueId + " ageMs=" + age);
                    return;
                } else {
                    // stale entry, replace
                    processedMessageIds.replace(uniqueId, prev, now);
                }
            }
        }

        if (handleSharekhanUpdateClose(message, uniqueId)) {
            return;
        }

        Map<String, Object> parsed = parserChain.parse(message);
        if (parsed != null) {
            System.out.println("✅ Parsed message: " + parsed);
            if (Boolean.TRUE.equals(parsed.get("stockAtrTrade"))) {
                boolean handled = handleStockAtrMessage(parsed);
                System.out.println((handled ? "✅ ATR stock message handled" : "⚠️ ATR stock message skipped")
                        + (uniqueId != null ? " (uid=" + uniqueId + ")" : ""));
                return;
            }
            TriggerRequest base = mapToTriggerRequest(parsed);
            if (source != null) {
                base.setSource(source);
            }

            // Place order for all Sharekhan customers: create a separate request per broker credentials row
            try {
                placeForAllSharekhanCustomers(base);
            } catch (Exception e) {
                System.err.println("Failed to place order for all Sharekhan customers: " + e.getMessage());
            }

            // Note: Do NOT place a generic trade here without appUser context.
            // All telegram-triggered placements are handled per-user in placeForAllSharekhanCustomers()
            System.out.println("✅ Parsed message handled" + (uniqueId != null ? " (uid=" + uniqueId + ")" : ""));
        } else {
            System.out.println("⚠️ No parser matched message" + (uniqueId != null ? " (uid=" + uniqueId + ")" : ""));
        }
    }

    private boolean handleStockAtrMessage(Map<String, Object> parsed) {
        StockAtrTradeRequest request = new StockAtrTradeRequest();
        request.setStock((String) parsed.get("stock"));
        request.setEntryPrice(parseDouble(parsed.get("entry")));
        Object direction = parsed.get("direction");
        if (direction != null) {
            request.setDirection(direction.toString());
        }
        Object quantity = parsed.get("quantity");
        if (quantity instanceof Number num) {
            request.setLots(num.intValue());
        } else if (quantity != null) {
            try {
                request.setLots(Integer.parseInt(quantity.toString()));
            } catch (NumberFormatException ignored) {
            }
        }
        Object expiryMonth = parsed.get("expiryMonth");
        if (expiryMonth instanceof Number num) {
            request.setExpiryMonth(num.intValue());
        } else if (expiryMonth != null) {
            try {
                request.setExpiryMonth(Integer.parseInt(expiryMonth.toString()));
            } catch (NumberFormatException ignored) {
            }
        }
        request.setIntraday(true);
        Object parsedSource = parsed.get("source");
        request.setSource(parsedSource != null ? parsedSource.toString() : "atr-signal");

        try {
            TriggerRequest triggerRequest = stockAtrTradeService.buildTriggerRequest(request);
            placeForAllSharekhanCustomers(triggerRequest);
            return true;
        } catch (IllegalArgumentException e) {
            System.err.println("ATR stock signal skipped: " + e.getMessage());
            notifyStockAtrFailure(request, e.getMessage());
        } catch (Exception e) {
            System.err.println("ATR stock signal failed: " + e.getMessage());
            notifyStockAtrFailure(request, e.getMessage());
        }
        return false;
    }

    private void notifyStockAtrFailure(StockAtrTradeRequest request, String reason) {
        if (telegramNotificationService == null) {
            return;
        }
        try {
            telegramNotificationService.sendTradeMessage(
                    "ATR Stock Signal Skipped",
                    "Stock: " + (request != null ? request.getStock() : null)
                            + "\nDirection: " + (request != null ? request.getDirection() : null)
                            + "\nEntry: " + (request != null ? request.getEntryPrice() : null)
                            + "\nReason: " + reason
            );
        } catch (Exception ignored) {}
    }

    private boolean handleSharekhanUpdateClose(String message, String uniqueId) {
        Optional<CloseTradesRequest> closeRequestOpt = extractUpdateCloseRequest(message);
        if (closeRequestOpt.isEmpty()) {
            return false;
        }

        CloseTradesRequest closeRequest = closeRequestOpt.get();
        try {
            closeRequest.setReason("Sharekhan UPDATE notification");
            CloseTradesResponse response = tradeCloseService.closeAllByContract(closeRequest);
            System.out.println("✅ Sharekhan UPDATE close handled for " + closeRequest.getInstrument() + " response=" + response);
            notifyCloseUpdate(response, uniqueId);
        } catch (Exception e) {
            System.err.println("Failed handling Sharekhan UPDATE close for " + closeRequest.getInstrument() + ": " + e.getMessage());
            if (telegramNotificationService != null) {
                try {
                    telegramNotificationService.sendTradeMessage(
                            "Sharekhan UPDATE Close Failed",
                            "Instrument: " + closeRequest.getInstrument()
                                    + "\nOption: " + closeRequest.getOptionType()
                                    + "\nStrike: " + closeRequest.getStrikePrice()
                                    + "\nExpiry: " + closeRequest.getExpiry()
                                    + "\nReason: " + e.getMessage()
                    );
                } catch (Exception ignored) {}
            }
        }
        return true;
    }

    private Optional<CloseTradesRequest> extractUpdateCloseRequest(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }

        String upper = message.toUpperCase(Locale.ROOT);
        boolean updateIntent = Pattern.compile("\\bACTION\\s*[=:]\\s*UPDATE\\b", Pattern.CASE_INSENSITIVE)
                .matcher(message)
                .find()
                || upper.startsWith("UPDATE")
                || upper.contains("SHAREKHAN UPDATE")
                || upper.contains("UPDATE NOTIFICATION");

        if (!updateIntent) {
            return Optional.empty();
        }

        return extractKeyValueUpdateContract(message)
                .or(() -> extractPlainUpdateContract(message));
    }

    private Optional<CloseTradesRequest> extractKeyValueUpdateContract(String message) {
        Map<String, String> data = new HashMap<>();
        Matcher matcher = Pattern.compile("\\b([A-Za-z][A-Za-z0-9_]*)\\s*[=:]\\s*([^,}\\n]+)")
                .matcher(message);
        while (matcher.find()) {
            data.put(matcher.group(1).trim().toLowerCase(Locale.ROOT), matcher.group(2).trim());
        }

        String symbol = firstPresent(data, "symbol", "instrument", "tradingsymbol");
        String optionType = firstPresent(data, "optiontype", "option", "type");
        String strike = firstPresent(data, "strike", "strikeprice");
        String expiry = firstPresent(data, "expiry", "expirydate");
        if (symbol == null || optionType == null || strike == null || expiry == null) {
            return Optional.empty();
        }
        return buildCloseRequest(symbol, optionType, strike, expiry);
    }

    private Optional<CloseTradesRequest> extractPlainUpdateContract(String message) {
        String normalized = message
                .replaceAll("(?i)\\bSHAREKHAN\\b", " ")
                .replaceAll("(?i)\\bSIGNAL\\b", " ")
                .replaceAll("(?i)\\bNOTIFICATION\\b", " ")
                .replaceAll("(?i)\\bACTION\\b", " ")
                .replaceAll("(?i)\\bUPDATE\\b", " ")
                .replaceAll("[{}=,:]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        Matcher matcher = Pattern.compile("\\b([A-Z][A-Z0-9_\\-]*)\\s+(CE|PE|CALL|PUT)\\s+([0-9]+(?:\\.[0-9]+)?)\\s+(.+)$",
                Pattern.CASE_INSENSITIVE).matcher(normalized);
        if (!matcher.find()) {
            return Optional.empty();
        }

        String expiry = matcher.group(4).trim();
        expiry = expiry.replaceAll("(?i)\\b(close|exit|square|off|now|trade|trades)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return buildCloseRequest(matcher.group(1), matcher.group(2), matcher.group(3), expiry);
    }

    private Optional<CloseTradesRequest> buildCloseRequest(String symbol, String optionType, String strike, String expiry) {
        try {
            CloseTradesRequest request = new CloseTradesRequest();
            request.setInstrument(symbol.trim().toUpperCase(Locale.ROOT));
            String normalizedOption = optionType.trim().toUpperCase(Locale.ROOT);
            if ("CALL".equals(normalizedOption)) {
                normalizedOption = "CE";
            } else if ("PUT".equals(normalizedOption)) {
                normalizedOption = "PE";
            }
            request.setOptionType(normalizedOption);
            request.setStrikePrice(Double.parseDouble(strike.trim()));
            request.setExpiry(expiry.trim());
            return Optional.of(request);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String firstPresent(Map<String, String> data, String... keys) {
        for (String key : keys) {
            String value = data.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void notifyCloseUpdate(CloseTradesResponse response, String uniqueId) {
        if (telegramNotificationService == null || response == null) {
            return;
        }
        try {
            String body = "Instrument: " + response.getInstrument() + "\n"
                    + "Option: " + response.getOptionType() + "\n"
                    + "Strike: " + response.getStrikePrice() + "\n"
                    + "Expiry: " + response.getExpiry() + "\n"
                    + "Pending requests cancelled: " + response.getCancelledRequests() + "\n"
                    + "Executed trades square-off initiated: " + response.getSquareOffInitiated() + "\n"
                    + "Skipped: " + response.getSkipped() + "\n"
                    + "Errors: " + response.getErrors()
                    + (uniqueId != null ? "\nUpdate: " + uniqueId : "");
            telegramNotificationService.sendTradeMessage("Sharekhan UPDATE Close Triggered", body);
        } catch (Exception ignored) {}
    }

    private boolean isDuplicateTrade(TriggerRequest req, Long appUserId) {
        if (!"Sharekhan".equalsIgnoreCase(req.getSource())) {
            return false;
        }

        Double requestedStrike = req.getStrikePrice();
        String requestedOptionType = normalizeOption(req.getOptionType());

        List<TriggerTradeRequestEntity> pendingRequests =
                triggerTradeRequestRepository.findBySymbolAndAppUserIdAndStatus(
                        req.getInstrument(),
                        appUserId,
                        TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION
                );

        if (pendingRequests != null && pendingRequests.stream()
                .anyMatch(p -> matchesContract(requestedStrike, requestedOptionType,
                        p.getStrikePrice(), p.getOptionType()))) {
            return true;
        }

        List<TriggeredTradeSetupEntity> activeTrades = triggeredTradeSetupRepository
                .findBySymbolAndAppUserIdAndStatusIn(
                        req.getInstrument(),
                        appUserId,
                        List.of(
                                TriggeredTradeStatus.EXECUTED,
                                TriggeredTradeStatus.TARGET_ORDER_PLACED,
                                TriggeredTradeStatus.EXIT_ORDER_PLACED
                        )
                );

        return activeTrades != null && activeTrades.stream()
                .anyMatch(t -> matchesContract(requestedStrike, requestedOptionType,
                        t.getStrikePrice(), t.getOptionType()));
    }

    private boolean matchesContract(Double reqStrike, String reqOptionType,
                                    Double entityStrike, String entityOptionType) {
        if (reqStrike == null && reqOptionType == null) {
            // For equities/indices without option legs, symbol match is enough
            return true;
        }

        if (!Objects.equals(reqStrike, entityStrike)) {
            return false;
        }

        return Objects.equals(reqOptionType, normalizeOption(entityOptionType));
    }

    private String normalizeOption(String optionType) {
        if (optionType == null) {
            return null;
        }
        String trimmed = optionType.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    public void placeForAllSharekhanCustomers(TriggerRequest base) {
        if (base != null && "Sharekhan".equalsIgnoreCase(base.getSource())) {
            Double sl = base.getStopLoss();
            if (sl != null && sl < 1.0d) {
                System.out.println("⏭️ Skipping Sharekhan trade due to stop loss < 1.0: SL=" + sl);
                return;
            }
        }
        // Fetch all broker credentials for SHAREKHAN
        List<BrokerCredentialsEntity> creds = brokerCredentialsService.findAllForBroker("Sharekhan");
        creds.addAll(brokerCredentialsService.findAllForBroker("Simulator"));
        if (creds == null || creds.isEmpty()) return;

        // Create an executor that uses virtual threads if available; otherwise fallback to cached thread pool
        ExecutorService executor = null;
        try {
            executor = createVirtualThreadExecutor();
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (BrokerCredentialsEntity c : creds) {
                if (c == null) continue;
                // Only active credentials
                if (c.getActive() != null && !c.getActive()) continue;

                // Clone the base request so we don't share mutable state across threads/users
                TriggerRequest req = cloneRequest(base);
                // Attach broker credential id and app user id so backend uses the right token/customer when placing
                req.setBrokerCredentialsId(c.getId());
                req.setUserId(c.getAppUserId());

                // Per-user configuration: telegram_trade_enabled (default true)
                boolean enabled = true;
                if ("Sharekhan".equalsIgnoreCase(req.getSource())) {
                    try {
                        String v = userConfigService.getConfig(c.getAppUserId(), "allow_sharekhan_research", "true");
                        if (v != null) {
                            String s = v.trim().toLowerCase();
                            enabled = s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on");
                        }
                    } catch (Exception ignore) { enabled = true; }
                } else if (!"admin-ui".equalsIgnoreCase(req.getSource())) {
                    try {
                        String v = userConfigService.getConfig(c.getAppUserId(), "telegram_trade_enabled", "true");
                        if (v != null) {
                            String s = v.trim().toLowerCase();
                            enabled = s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on");
                        }
                    } catch (Exception ignore) { enabled = true; }
                }

                if (!enabled) {
                    // Skip placing for this user; optionally notify/log
                    String skipReason = "Sharekhan".equalsIgnoreCase(req.getSource()) ? "allow_sharekhan_research=false" : "telegram_trade_enabled=false";
                    System.out.println("⏭️ Skipping trade for user #" + c.getAppUserId() + " due to " + skipReason);
                    try {
                        if (telegramNotificationService != null) {
                            String title = "Trade Skipped";
                            String body = skipReason + "; Trade ignored for incoming signal.\n" +
                                    "Instrument: " + (req.getInstrument()) +
                                    (req.getStrikePrice()!=null?(" "+req.getStrikePrice()):"") +
                                    (req.getOptionType()!=null?(" "+req.getOptionType()):"");
                            telegramNotificationService.sendTradeMessageForUser(c.getAppUserId(), title, body);
                        }
                    } catch (Exception ignored) {}
                    continue;
                }

                if (isDuplicateTrade(req, c.getAppUserId())) {
                    System.out.println("⏭️ Skipping duplicate trade for user #" + c.getAppUserId() + " and instrument " + req.getInstrument());
                    try {
                        if (telegramNotificationService != null) {
                            String title = "Duplicate Trade Skipped";
                            String body = "Trade ignored as a similar trade is already active.\n" +
                                    "Instrument: " + (req.getInstrument()) +
                                    (req.getStrikePrice()!=null?(" "+req.getStrikePrice()):"") +
                                    (req.getOptionType()!=null?(" "+req.getOptionType()):"");
                            telegramNotificationService.sendTradeMessageForUser(c.getAppUserId(), title, body);
                        }
                    } catch (Exception ignored) {}
                    continue;
                }

                // Submit placement concurrently
                CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                    try {
                        if (Boolean.TRUE.equals(req.getQuickTrade())) {
                            tradingExecutorService.executeQuickTrade(req);
                        } else {
                            tradingExecutorService.executeTrade(req);
                        }
                    } catch (Exception ex) {
                        System.err.println("Failed to place order for broker credential id " + (c != null ? c.getId() : "null") + ": " + ex.getMessage());
                    }
                }, executor);

                futures.add(f);
            }

            // Wait for all placements to complete (join all futures)
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } catch (CompletionException ce) {
                // log and continue
                System.err.println("One or more order placements failed: " + ce.getMessage());
            }

        } catch (Exception e) {
            // If executor creation fails, fallback to sequential placement
            System.err.println("Executor failure - falling back to sequential placement: " + e.getMessage());
            for (BrokerCredentialsEntity c : creds) {
                try {
                    if (c == null) continue;
                    if (c.getActive() != null && !c.getActive()) continue;
                    TriggerRequest req = cloneRequest(base);
                    req.setBrokerCredentialsId(c.getId());
                    req.setUserId(c.getAppUserId());
                    boolean enabled = true;
                    if ("Sharekhan".equalsIgnoreCase(req.getSource())) {
                        try {
                            String v = userConfigService.getConfig(c.getAppUserId(), "allow_sharekhan_research", "false");
                            if (v != null) {
                                String s = v.trim().toLowerCase();
                                enabled = s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on");
                            }
                        } catch (Exception ignore) { enabled = false; }
                    } else if (!"admin-ui".equalsIgnoreCase(req.getSource())) {
                        try {
                            String v = userConfigService.getConfig(c.getAppUserId(), "telegram_trade_enabled", "false");
                            if (v != null) {
                                String s = v.trim().toLowerCase();
                                enabled = s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on");
                            }
                        } catch (Exception ignore) { enabled = false; }
                    }
                    if (!enabled) {
                        String skipReason = "Sharekhan".equalsIgnoreCase(req.getSource()) ? "allow_sharekhan_research=false" : "telegram_trade_enabled=false";
                        System.out.println("⏭️ Skipping trade for user #" + c.getAppUserId() + " due to " + skipReason);
                        try {
                            if (telegramNotificationService != null) {
                                String title = "Trade Skipped";
                                String body = skipReason + "; Trade ignored for incoming signal.\n" +
                                        "Instrument: " + (req.getInstrument()) +
                                        (req.getStrikePrice()!=null?(" "+req.getStrikePrice()):"") +
                                        (req.getOptionType()!=null?(" "+req.getOptionType()):"");
                                telegramNotificationService.sendTradeMessageForUser(c.getAppUserId(), title, body);
                            }
                        } catch (Exception ignored) {}
                        continue;
                    }

                    if (isDuplicateTrade(req, c.getAppUserId())) {
                        System.out.println("⏭️ Skipping duplicate trade for user #" + c.getAppUserId() + " and instrument " + req.getInstrument());
                        try {
                            if (telegramNotificationService != null) {
                                String title = "Duplicate Trade Skipped";
                                String body = "Trade ignored as a similar trade is already active.\n" +
                                        "Instrument: " + (req.getInstrument()) +
                                        (req.getStrikePrice()!=null?(" "+req.getStrikePrice()):"") +
                                        (req.getOptionType()!=null?(" "+req.getOptionType()):"");
                                telegramNotificationService.sendTradeMessageForUser(c.getAppUserId(), title, body);
                            }
                        } catch (Exception ignored) {}
                        continue;
                    }

                    if (Boolean.TRUE.equals(req.getQuickTrade())) {
                        tradingExecutorService.executeQuickTrade(req);
                    } else {
                        tradingExecutorService.executeTrade(req);
                    }
                } catch (Exception ex) {
                    System.err.println("Failed to place order for broker credential id " + (c != null ? c.getId() : "null") + ": " + ex.getMessage());
                }
            }

        } finally {
            if (executor != null) {
                shutdownExecutor(executor);
            }
        }
    }

    // Gracefully shutdown executor
    private void shutdownExecutor(ExecutorService exec) {
        try {
            exec.shutdown();
            if (!exec.awaitTermination(5, TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException ie) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {}
    }

    /**
     * Try to create a virtual-thread-per-task executor using reflection (so code compiles on older JDKs).
     * If not available, return a cached thread pool.
     */
    private ExecutorService createVirtualThreadExecutor() {
        try {
            Method m = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            Object exec = m.invoke(null);
            if (exec instanceof ExecutorService) return (ExecutorService) exec;
        } catch (Throwable ignored) {
            // fallback
        }
        return Executors.newCachedThreadPool();
    }

    private TriggerRequest cloneRequest(TriggerRequest src) {
        TriggerRequest t = new TriggerRequest();
        t.setInstrument(src.getInstrument());
        t.setStrikePrice(src.getStrikePrice());
        t.setOptionType(src.getOptionType());
        t.setEntryPrice(src.getEntryPrice());
        t.setTarget1(src.getTarget1());
        t.setTarget2(src.getTarget2());
        t.setTarget3(src.getTarget3());
        t.setStopLoss(src.getStopLoss());
        t.setExpiry(src.getExpiry());
        t.setExchange(src.getExchange());
        t.setIntraday(src.getIntraday());
        t.setTrailingSl(src.getTrailingSl());
        t.setQuantity(src.getQuantity());
        t.setSource(src.getSource());
        t.setUseSpotPrice(src.getUseSpotPrice());
        t.setUseSpotForEntry(src.getUseSpotForEntry());
        t.setUseSpotForSl(src.getUseSpotForSl());
        t.setUseSpotForTarget(src.getUseSpotForTarget());
        t.setSpotScripCode(src.getSpotScripCode());
        t.setTslEnabled(src.getTslEnabled());
        t.setLots(src.getLots());
        t.setQuickTrade(src.getQuickTrade());
        t.setAction(src.getAction());
        // userId and brokerCredentialsId intentionally left null here; caller sets them
        return t;
    }


    private TriggerRequest mapToTriggerRequest(Map<String, Object> parsed) {
        TriggerRequest request = new TriggerRequest();
        //request.setAction((String) parsed.get("action"));
        request.setInstrument((String) parsed.get("symbol"));
        request.setStrikePrice(parseDouble(parsed.get("strike")));
        request.setOptionType((String) parsed.get("optionType"));
        request.setEntryPrice(parseDouble(parsed.get("entry")));
        request.setTarget1(parseDouble(parsed.get("target1")));
        request.setTarget2(parseDouble(parsed.get("target2")));
        request.setTarget3(parseDouble(parsed.get("target3")));
        request.setStopLoss(parseDouble(parsed.get("stopLoss")));
        request.setExpiry((String) parsed.get("expiry"));
        request.setExchange((String) parsed.get("exchange"));
        request.setIntraday(true);
        request.setUseSpotPrice(parseBoolean(parsed.get("useSpotPrice")));
        request.setUseSpotForEntry(parseBoolean(parsed.get("useSpotForEntry")));
        request.setUseSpotForSl(parseBoolean(parsed.get("useSpotForSl")));
        request.setUseSpotForTarget(parseBoolean(parsed.get("useSpotForTarget")));
        Object action = parsed.get("action");
        if (action != null) {
            request.setAction(action.toString().trim().toUpperCase(Locale.ROOT));
        }
        Object quick = parsed.get("quickTrade");
        if (quick instanceof Boolean) {
            request.setQuickTrade((Boolean) quick);
        } else if (quick != null) {
            request.setQuickTrade(Boolean.parseBoolean(quick.toString()));
        }
        // Quantity may be present in parsed map as 'quantity' or 'qty'
        Integer q = null;
        try {
            Object qv = parsed.get("quantity");
            if (qv == null) qv = parsed.get("qty");
            if (qv instanceof Number) q = ((Number) qv).intValue();
            else if (qv != null) q = Integer.parseInt(qv.toString());
        } catch (Exception ignored){}
        request.setQuantity(q);
        return request;
    }

    private Double parseDouble(Object val) {
        if (val instanceof Number) return ((Number) val).doubleValue();
        try {
            if (val == null) return null;
            return Double.parseDouble(val.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private Boolean parseBoolean(Object val) {
        if (val instanceof Boolean) return (Boolean) val;
        if (val == null) return null;
        String normalized = val.toString().trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return null;
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("on");
    }

}
