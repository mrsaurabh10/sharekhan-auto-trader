package org.com.sharekhan.service;

import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.parser.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.Method;

@Service
public class TradingMessageService {

    @Autowired
    private  TradeExecutionService tradingExecutorService;

    @Autowired
    private BrokerCredentialsService brokerCredentialsService;

    private final TradingSignalParser parserChain = new ParserChain(
            new TelegramSignalParser(),
            new WhatsappSignalParser(),
            new AartiSignalParser()
    );


    public void handleRawMessage(String message, String source) {
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

        } else {
            System.out.println("⚠️ No parser matched message");
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
