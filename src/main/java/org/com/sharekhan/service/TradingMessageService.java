package org.com.sharekhan.service;

import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.parser.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Service
public class TradingMessageService {

    @Autowired
    private  TradeExecutionService tradingExecutorService;

    @Autowired
    private BrokerCredentialsService brokerCredentialsService;

    @Autowired
    private UserConfigService userConfigService;

    @Autowired(required = false)
    private TelegramNotificationService telegramNotificationService;

    private final TradingSignalParser parserChain = new ParserChain(
            new TelegramSignalParser(),
            new WhatsappSignalParser(),
            new AartiSignalParser()
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

        Map<String, Object> parsed = parserChain.parse(message);
        if (parsed != null) {
            System.out.println("✅ Parsed message: " + parsed);
            TriggerRequest base = mapToTriggerRequest(parsed);

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

    private void placeForAllSharekhanCustomers(TriggerRequest base) {
        // Fetch all broker credentials for SHAREKHAN
        var creds = brokerCredentialsService.findAllForBroker("Sharekhan");
        if (creds == null || creds.isEmpty()) return;

        // Create an executor that uses virtual threads if available; otherwise fallback to cached thread pool
        ExecutorService executor = null;
        try {
            executor = createVirtualThreadExecutor();
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (var c : creds) {
                if (c == null) continue;
                // Only active credentials
                if (c.getActive() != null && !c.getActive()) continue;

                TriggerRequest req = cloneRequest(base);
                // Attach broker credential id and app user id so backend uses the right token/customer when placing
                req.setBrokerCredentialsId(c.getId());
                req.setUserId(c.getAppUserId());

                // Per-user configuration: telegram_trade_enabled (default true)
                boolean enabled = true;
                try {
                    String v = userConfigService.getConfig(c.getAppUserId(), "telegram_trade_enabled", "true");
                    if (v != null) {
                        String s = v.trim().toLowerCase();
                        enabled = s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on");
                    }
                } catch (Exception ignore) { enabled = true; }

                if (!enabled) {
                    // Skip placing for this user; optionally notify/log
                    System.out.println("⏭️ Skipping telegram trade for user #" + c.getAppUserId() + " due to telegram_trade_enabled=false");
                    try {
                        if (telegramNotificationService != null) {
                            String title = "Telegram Trade Skipped";
                            String body = "telegram_trade_enabled=false; Trade ignored for incoming signal.\n" +
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
                        tradingExecutorService.executeTrade(req);
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
            for (var c : creds) {
                try {
                    if (c == null) continue;
                    if (c.getActive() != null && !c.getActive()) continue;
                    TriggerRequest req = cloneRequest(base);
                    req.setBrokerCredentialsId(c.getId());
                    req.setUserId(c.getAppUserId());
                    boolean enabled = true;
                    try {
                        String v = userConfigService.getConfig(c.getAppUserId(), "telegram_trade_enabled", "true");
                        if (v != null) {
                            String s = v.trim().toLowerCase();
                            enabled = s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on");
                        }
                    } catch (Exception ignore) { enabled = true; }
                    if (!enabled) {
                        System.out.println("⏭️ Skipping telegram trade for user #" + c.getAppUserId() + " due to telegram_trade_enabled=false");
                        try {
                            if (telegramNotificationService != null) {
                                String title = "Telegram Trade Skipped";
                                String body = "telegram_trade_enabled=false; Trade ignored for incoming signal.\n" +
                                        "Instrument: " + (req.getInstrument()) +
                                        (req.getStrikePrice()!=null?(" "+req.getStrikePrice()):"") +
                                        (req.getOptionType()!=null?(" "+req.getOptionType()):"");
                                telegramNotificationService.sendTradeMessageForUser(c.getAppUserId(), title, body);
                            }
                        } catch (Exception ignored) {}
                        continue;
                    }
                    tradingExecutorService.executeTrade(req);
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
        // userId and brokerCredentialsId intentionally left null here; caller sets them
        return t;
    }


    private TriggerRequest mapToTriggerRequest(Map<String, Object> parsed) {
        TriggerRequest request = new TriggerRequest();
        //request.setAction((String) parsed.get("action"));
        request.setInstrument((String) parsed.get("symbol"));
        request.setStrikePrice(parseDouble(parsed.get("strike")) );
        request.setOptionType((String) parsed.get("optionType"));
        request.setEntryPrice(parseDouble(parsed.get("entry")));
        request.setTarget1(parseDouble(parsed.get("target1")));
        request.setTarget2(parseDouble(parsed.get("target2")));
        request.setStopLoss(parseDouble(parsed.get("stopLoss")));
        request.setExpiry((String) parsed.get("expiry"));
        request.setExchange((String) parsed.get("exchange"));
        request.setIntraday(true);
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
            return Double.parseDouble(val.toString());
        } catch (Exception e) {
            return null;
        }
    }

}
