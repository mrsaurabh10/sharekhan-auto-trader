package org.com.sharekhan.strategy;

import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.service.TelegramNotificationService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Shared evaluator for manually curated F&O CE/PE lists. */
abstract class AbstractManualFnoVwapReclaimBaseBreakoutStrategy implements StrategyEvaluator {

    private static final LocalTime SELECTION_TIME = LocalTime.of(9, 25);
    private static final int DEFAULT_LOTS = 3;

    private final StrategyMetadata metadata;
    private final StrategySupport support;
    private final Fno925EntryQualificationService qualificationService;
    private final TelegramNotificationService telegramNotificationService;
    private final Set<RunKey> submittedSymbols = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<RunKey, LocalDateTime> unavailableAlertedAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RunKey, RejectionAudit> rejectionAudits = new ConcurrentHashMap<>();

    protected AbstractManualFnoVwapReclaimBaseBreakoutStrategy(StrategyMetadata metadata,
                                                                 StrategySupport support,
                                                                 Fno925EntryQualificationService qualificationService,
                                                                 TelegramNotificationService telegramNotificationService) {
        this.metadata = metadata;
        this.support = support;
        this.qualificationService = qualificationService;
        this.telegramNotificationService = telegramNotificationService;
    }

    @Override
    public StrategyMetadata metadata() {
        return metadata;
    }

    @Override
    public StrategyApplyResponse apply(StrategyApplyRequest request) {
        LocalDateTime now = LocalDateTime.now(StrategySupport.MARKET_ZONE);
        List<String> symbols = symbols(request.getSymbol());
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("Enter one or more F&O underlying symbols separated by commas, semicolons, or new lines");
        }
        if (now.toLocalTime().isBefore(SELECTION_TIME)) {
            return support.waiting(metadata, String.join(",", symbols), "Waiting for the 09:25 market open.");
        }
        submittedSymbols.removeIf(key -> key.day().isBefore(now.toLocalDate()));
        unavailableAlertedAt.keySet().removeIf(key -> key.day().isBefore(now.toLocalDate()));
        rejectionAudits.keySet().removeIf(key -> key.day().isBefore(now.toLocalDate()));

        List<Triggered> triggered = new ArrayList<>();
        List<String> waiting = new ArrayList<>();
        for (String symbol : symbols) {
            RunKey key = new RunKey(now.toLocalDate(), request.getUserId(), request.getBrokerCredentialsId(), sourceFor(request), symbol);
            if (submittedSymbols.contains(key)) {
                continue;
            }
            try {
                ScriptMasterEntity spot = support.resolveSpotScript(symbol);
                java.util.Optional<String> availabilityFailure = support.mstockAvailabilityFailure(spot);
                if (availabilityFailure.isPresent()) {
                    notifyUnavailable(key, request.getUserId(), symbol, availabilityFailure.get(), now);
                    recordRejection(key, availabilityFailure.get(), now);
                    waiting.add(symbol + ": " + compactRejection(availabilityFailure.get()));
                    continue;
                }
                unavailableAlertedAt.remove(key);
                Fno925EntryQualificationService.Qualification qualification = qualificationService
                        .qualify(new Fno925Candidate(symbol, spot, metadata.optionType()), now);
                if (!qualification.qualified()) {
                    recordRejection(key, qualification.reason(), now);
                    waiting.add(symbol + ": " + displayReason(key, qualification.reason()));
                    continue;
                }
                TriggerRequest trigger = buildTrigger(request, symbol, spot, qualification.signal());
                TriggerTradeRequestEntity existing = support.findExisting(trigger);
                TriggerTradeRequestEntity trade = existing != null ? existing : support.executeTriggeredTrade(trigger);
                triggered.add(new Triggered(symbol, trigger, trade, existing != null, qualification.signal().setup()));
                submittedSymbols.add(key);
                rejectionAudits.remove(key);
            } catch (Exception e) {
                String reason = "instrument cannot be polled: " + e.getMessage();
                notifyUnavailable(key, request.getUserId(), symbol, reason, now);
                recordRejection(key, reason, now);
                waiting.add(symbol + ": " + compactRejection(reason));
            }
        }
        if (triggered.isEmpty()) {
            String message = waiting.isEmpty()
                    ? "All configured instruments already have a request today."
                    : "Configured instruments are waiting for confirmation; a closed window shows the last meaningful rejection: "
                    + waiting + ".";
            return support.waiting(metadata, String.join(",", symbols), message);
        }
        Triggered first = triggered.get(0);
        long newRequests = triggered.stream().filter(item -> !item.duplicate()).count();
        return StrategyApplyResponse.builder()
                .status(newRequests > 0 ? "triggered" : "duplicate")
                .message("Created " + newRequests + " qualified request(s): " + triggered.stream()
                        .map(item -> item.symbol() + " (" + item.setup() + ")").toList()
                        + "; remaining configured instruments continue to be monitored.")
                .templateId(metadata.id())
                .symbol(first.symbol())
                .direction(metadata.optionType())
                .breakoutClose(support.roundPrice(first.trigger().getEntryPrice()))
                .triggerRequest(first.trigger())
                .tradeRequest(first.trade())
                .build();
    }

    private TriggerRequest buildTrigger(StrategyApplyRequest request,
                                        String symbol,
                                        ScriptMasterEntity spot,
                                        Fno925EntryQualificationService.Signal signal) {
        boolean ce = "CE".equalsIgnoreCase(metadata.optionType());
        double entry = signal.entryPrice();
        double stop = signal.stopLoss();
        double risk = Math.abs(entry - stop);
        String expiry = support.nearestExpiry(symbol, metadata.optionType());
        TriggerRequest trigger = new TriggerRequest();
        trigger.setInstrument(symbol);
        trigger.setEntryPrice(entry);
        trigger.setStopLoss(stop);
        trigger.setTarget1(support.roundPrice(ce ? entry + risk : entry - risk));
        trigger.setTarget2(support.roundPrice(ce ? entry + (2d * risk) : entry - (2d * risk)));
        trigger.setTarget3(support.roundPrice(ce ? entry + (3d * risk) : entry - (3d * risk)));
        trigger.setOptionType(metadata.optionType());
        trigger.setExpiry(expiry);
        trigger.setStrikePrice(support.nearestStrike(symbol, metadata.optionType(), expiry, entry));
        trigger.setIntraday(request.getIntraday() == null || request.getIntraday());
        trigger.setUseSpotPrice(true);
        trigger.setUseSpotForEntry(true);
        trigger.setUseSpotForSl(true);
        trigger.setUseSpotForTarget(true);
        trigger.setSpotScripCode(spot.getScripCode());
        trigger.setUserId(request.getUserId());
        trigger.setBrokerCredentialsId(request.getBrokerCredentialsId());
        trigger.setSource(sourceFor(request));
        int lots = request.getLots() != null && request.getLots() > 0 ? request.getLots() : DEFAULT_LOTS;
        trigger.setLots(lots);
        trigger.setQuantity(lots);
        trigger.setTslEnabled(true);
        return trigger;
    }

    private List<String> symbols(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String item : value.split("[,;\\n\\r]+")) {
            String symbol = item.trim().toUpperCase(Locale.ROOT);
            if (!symbol.isBlank()) unique.add(symbol);
        }
        return List.copyOf(unique);
    }

    private String sourceFor(StrategyApplyRequest request) {
        return request.getSource() == null || request.getSource().isBlank()
                ? "strategy:" + metadata.id()
                : request.getSource().trim();
    }

    private void notifyUnavailable(RunKey key, Long userId, String symbol, String reason, LocalDateTime now) {
        LocalDateTime previous = unavailableAlertedAt.get(key);
        if (previous != null && previous.plusMinutes(5).isAfter(now)) {
            return;
        }
        unavailableAlertedAt.put(key, now);
        telegramNotificationService.sendTradeMessageForUser(userId,
                "F&O strategy polling unavailable",
                "Template: " + metadata.id() + "\nSymbol: " + symbol + "\nReason: " + reason
                        + "\nThe strategy will keep checking; this alert repeats at most every five minutes.");
    }

    private void recordRejection(RunKey key, String reason, LocalDateTime now) {
        if (reason == null || allEntryWindowsClosed(reason)) {
            return;
        }
        rejectionAudits.put(key, new RejectionAudit(now.toLocalTime(), compactRejection(reason)));
    }

    private String displayReason(RunKey key, String currentReason) {
        if (!allEntryWindowsClosed(currentReason)) {
            return compactRejection(currentReason);
        }
        RejectionAudit audit = rejectionAudits.get(key);
        return audit == null
                ? "entry windows closed"
                : "last rejection " + audit.time() + " " + audit.reason();
    }

    private boolean allEntryWindowsClosed(String reason) {
        return reason != null
                && reason.contains("morning ORB window closed")
                && reason.contains("VWAP reclaim window closed");
    }

    private String compactRejection(String reason) {
        if (reason == null || reason.isBlank()) {
            return "qualification unavailable";
        }
        String compact = reason.replace("ORB: ", "ORB ")
                .replace("; VWAP reclaim: ", " | VWAP ");
        return compact.length() <= 180 ? compact : compact.substring(0, 177) + "...";
    }

    private record RunKey(LocalDate day, Long userId, Long brokerCredentialsId, String source, String symbol) { }
    private record RejectionAudit(LocalTime time, String reason) { }
    private record Triggered(String symbol, TriggerRequest trigger, TriggerTradeRequestEntity trade, boolean duplicate, String setup) { }
}
