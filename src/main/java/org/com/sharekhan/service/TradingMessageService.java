package org.com.sharekhan.service;

import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.parser.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Service
public class TradingMessageService {

    @Autowired
    private  TradeExecutionService tradingExecutorService;

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
            System.out.println("✅ Parsed message: " + parsed + (uniqueId != null ? " (uid=" + uniqueId + ")" : ""));
            TriggerRequest req = mapToTriggerRequest(parsed);
            tradingExecutorService.executeTrade(req);
            // trigger trading logic
        } else {
            System.out.println("⚠️ No parser matched message" + (uniqueId != null ? " (uid=" + uniqueId + ")" : ""));
        }
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
