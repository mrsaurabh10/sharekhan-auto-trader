package org.com.sharekhan.strategy;

import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Selected-symbol F&O ATR strategy based on prior-day high/low or confirmed swing structure. */
abstract class AbstractAtrPreviousDayFnoStrategy implements StrategyEvaluator {

    static final String SOURCE = "atr-pdh-pdl-strategy";
    private static final int DEFAULT_LOTS = 3;

    private final StrategyMetadata metadata;
    private final StrategySupport support;
    private final AtrPreviousDayBreakoutQualificationService qualificationService;
    private final Set<RunKey> submittedSymbols = ConcurrentHashMap.newKeySet();

    protected AbstractAtrPreviousDayFnoStrategy(StrategyMetadata metadata,
                                                StrategySupport support,
                                                AtrPreviousDayBreakoutQualificationService qualificationService) {
        this.metadata = metadata;
        this.support = support;
        this.qualificationService = qualificationService;
    }

    @Override
    public StrategyMetadata metadata() {
        return metadata;
    }

    @Override
    public StrategyApplyResponse apply(StrategyApplyRequest request) {
        LocalDateTime now = LocalDateTime.now(StrategySupport.MARKET_ZONE);
        List<String> symbols = symbols(request.getSymbol());
        if (symbols.isEmpty()) throw new IllegalArgumentException("Enter one or more F&O underlying symbols");
        submittedSymbols.removeIf(key -> key.day().isBefore(now.toLocalDate()));
        List<Triggered> triggered = new ArrayList<>();
        List<String> waiting = new ArrayList<>();
        for (String symbol : symbols) {
            RunKey key = new RunKey(now.toLocalDate(), request.getUserId(), request.getBrokerCredentialsId(), symbol);
            if (submittedSymbols.contains(key)) continue;
            try {
                ScriptMasterEntity spot = support.resolveSpotScript(symbol);
                support.mstockAvailabilityFailure(spot).ifPresentOrElse(failure -> waiting.add(symbol + ": " + failure), () -> {
                    support.warmUpPreferredFnoFeeds(request, metadata, symbol, spot);
                    Fno925EntryQualificationService.Qualification qualification = qualificationService
                            .qualify(spot, metadata.optionType(), request.getUserId(), now);
                    if (!qualification.qualified()) {
                        waiting.add(symbol + ": " + qualification.reason());
                        return;
                    }
                    TriggerRequest trigger = buildTrigger(request, symbol, spot, qualification.signal());
                    TriggerTradeRequestEntity existing = support.findExisting(trigger);
                    // A symbol addition defines a pending setup. Do not submit an order until live market evaluation confirms entry.
                    TriggerTradeRequestEntity trade = existing != null ? existing : support.createPendingTradeRequest(trigger);
                    triggered.add(new Triggered(symbol, trigger, trade, existing != null));
                    submittedSymbols.add(key);
                });
            } catch (Exception e) {
                waiting.add(symbol + ": " + e.getMessage());
            }
        }
        if (triggered.isEmpty()) {
            return support.waiting(metadata, String.join(",", symbols), "Configured instruments are waiting for ATR confirmation: " + waiting);
        }
        Triggered first = triggered.get(0);
        long created = triggered.stream().filter(item -> !item.duplicate()).count();
        return StrategyApplyResponse.builder().status(created > 0 ? "triggered" : "duplicate")
                .message("Created " + created + " ATR prior-day strategy request(s).")
                .templateId(metadata.id()).symbol(first.symbol()).direction(metadata.optionType())
                .breakoutClose(support.roundPrice(first.trigger().getEntryPrice()))
                .triggerRequest(first.trigger()).tradeRequest(first.trade()).build();
    }

    private TriggerRequest buildTrigger(StrategyApplyRequest request, String symbol, ScriptMasterEntity spot,
                                        Fno925EntryQualificationService.Signal signal) {
        boolean ce = "CE".equalsIgnoreCase(metadata.optionType());
        double entry = signal.entryPrice();
        double atr = Math.abs(entry - signal.stopLoss()) / 2d;
        String expiry = support.preferredFnoExpiry(symbol, metadata.optionType());
        StrategySupport.FnoOptionContract contract = support.resolveFnoEntryContract(request, metadata, symbol, expiry,
                support.nearestStrike(symbol, metadata.optionType(), expiry, entry));
        int lots = request.getLots() != null && request.getLots() > 0 ? request.getLots() : DEFAULT_LOTS;
        TriggerRequest trigger = new TriggerRequest();
        trigger.setInstrument(symbol); trigger.setEntryPrice(entry); trigger.setStopLoss(signal.stopLoss());
        trigger.setTarget1(support.roundPrice(ce ? entry + 2d * atr : entry - 2d * atr));
        trigger.setTarget2(support.roundPrice(ce ? entry + 3d * atr : entry - 3d * atr));
        trigger.setTarget3(support.roundPrice(ce ? entry + 4d * atr : entry - 4d * atr));
        trigger.setOptionType(metadata.optionType()); trigger.setExpiry(contract.expiry()); trigger.setStrikePrice(contract.strike());
        trigger.setIntraday(request.getIntraday() == null || request.getIntraday());
        trigger.setUseSpotPrice(true); trigger.setUseSpotForEntry(true); trigger.setUseSpotForSl(true); trigger.setUseSpotForTarget(true);
        trigger.setSpotScripCode(spot.getScripCode()); trigger.setUserId(request.getUserId());
        trigger.setBrokerCredentialsId(request.getBrokerCredentialsId()); trigger.setSource(SOURCE);
        trigger.setLots(lots); trigger.setQuantity(lots); trigger.setTslEnabled(lots > 1);
        return trigger;
    }

    private List<String> symbols(String value) {
        if (value == null || value.isBlank()) return List.of();
        Set<String> unique = new LinkedHashSet<>();
        for (String item : value.split("[,;\\n\\r]+")) if (!item.isBlank()) unique.add(item.trim().toUpperCase(Locale.ROOT));
        return List.copyOf(unique);
    }

    private record RunKey(LocalDate day, Long userId, Long credentialsId, String symbol) { }
    private record Triggered(String symbol, TriggerRequest trigger, TriggerTradeRequestEntity trade, boolean duplicate) { }
}
