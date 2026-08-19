package org.com.sharekhan.strategy;

import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Selected-symbol F&O ATR strategy based on prior-day high/low or confirmed swing structure. */
abstract class AbstractAtrPreviousDayFnoStrategy implements StrategyEvaluator {

    static final String SOURCE = "atr-pdh-pdl-strategy";
    private static final int DEFAULT_LOTS = 1;
    private static final double STOP_LOSS_ATR_MULTIPLIER = 2d;
    private static final double TARGET1_ATR_MULTIPLIER = 3d;
    private static final double TARGET2_ATR_MULTIPLIER = 5d;
    private static final double TARGET3_ATR_MULTIPLIER = 6d;
    private static final LocalTime REENTRY_CUTOFF = LocalTime.of(14, 30);

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
            // Claim the key before qualification so two concurrent Apply calls cannot create
            // the same directional setup before either has persisted its request.
            if (!submittedSymbols.add(key)) {
                waiting.add(symbol + ": an ATR prior-day setup is already being created or monitored today");
                continue;
            }
            try {
                List<TriggeredTradeSetupEntity> priorEntries = support.atrPreviousDayEntriesOn(
                        now.toLocalDate(), request.getUserId(), symbol, metadata.optionType());
                if (priorEntries.size() >= 2) {
                    waiting.add(symbol + ": the maximum of one ATR prior-day re-entry has already been used today");
                    continue;
                }
                TriggeredTradeSetupEntity priorEntry = priorEntries.isEmpty() ? null : priorEntries.get(0);
                if (priorEntry != null && (priorEntry.getStatus() != TriggeredTradeStatus.EXITED_SUCCESS
                        || !now.toLocalTime().isBefore(REENTRY_CUTOFF))) {
                    waiting.add(symbol + ": prior ATR entry has not exited, or the 14:30 re-entry cutoff has passed");
                    continue;
                }
                ScriptMasterEntity spot = support.resolveSpotScript(symbol);
                support.mstockAvailabilityFailure(spot).ifPresentOrElse(failure -> waiting.add(symbol + ": " + failure), () -> {
                    support.warmUpPreferredFnoFeeds(request, metadata, symbol, spot);
                    Fno925EntryQualificationService.Qualification qualification = priorEntry == null
                            ? qualificationService.qualify(spot, metadata.optionType(), request.getUserId(), now)
                            : qualificationService.qualifyReentry(spot, metadata.optionType(), priorEntry, now);
                    if (!qualification.qualified()) {
                        waiting.add(symbol + ": " + qualification.reason());
                        return;
                    }
                    TriggerRequest trigger = buildTrigger(request, symbol, spot, qualification.signal());
                    TriggerTradeRequestEntity existing = support.findActiveAtrPreviousDaySetup(trigger);
                    // A symbol addition defines a pending setup. Do not submit an order until live market evaluation confirms entry.
                    TriggerTradeRequestEntity trade = existing != null ? existing : support.createPendingTradeRequest(trigger);
                    triggered.add(new Triggered(symbol, trigger, trade, existing != null));
                });
            } catch (Exception e) {
                waiting.add(symbol + ": " + e.getMessage());
            } finally {
                // This set is a short-lived concurrent-Apply lock only. Persisted
                // active requests are the durable duplicate guard. Retaining a
                // key after a request is later cancelled/deleted leaves the
                // subscription permanently stuck until an application restart.
                submittedSymbols.remove(key);
            }
        }
        if (triggered.isEmpty()) {
            return support.waiting(metadata, String.join(",", symbols), "Configured instruments are waiting for ATR confirmation: " + waiting);
        }
        Triggered first = triggered.get(0);
        long created = triggered.stream().filter(item -> !item.duplicate()).count();
        String message = "Created " + created + " ATR prior-day strategy request(s).";
        if (!waiting.isEmpty()) {
            message += " Not created: " + String.join("; ", waiting);
        }
        return StrategyApplyResponse.builder().status(created > 0 ? "triggered" : "duplicate")
                .message(message)
                .templateId(metadata.id()).symbol(first.symbol()).direction(metadata.optionType())
                .breakoutClose(support.roundPrice(first.trigger().getEntryPrice()))
                .triggerRequest(first.trigger()).tradeRequest(first.trade()).build();
    }

    private TriggerRequest buildTrigger(StrategyApplyRequest request, String symbol, ScriptMasterEntity spot,
                                        Fno925EntryQualificationService.Signal signal) {
        boolean ce = "CE".equalsIgnoreCase(metadata.optionType());
        double entry = signal.entryPrice();
        // Keep the stop and all targets tied to the same ATR calculation.  Do not
        // infer ATR from a separately carried stop level: a malformed/stale stop
        // must not turn an otherwise valid CE setup into an inverted stop.
        double atr = signal.atr();
        if (!Double.isFinite(atr) || atr <= 0d) {
            throw new IllegalArgumentException("ATR(75) is unavailable for " + symbol);
        }
        double stopLoss = support.roundPrice(ce ? entry - STOP_LOSS_ATR_MULTIPLIER * atr : entry + STOP_LOSS_ATR_MULTIPLIER * atr);
        String expiry = support.preferredFnoExpiry(symbol, metadata.optionType());
        StrategySupport.FnoOptionContract contract = support.resolveFnoEntryContract(request, metadata, symbol, expiry,
                support.nearestStrike(symbol, metadata.optionType(), expiry, entry));
        int lots = request.getLots() != null && request.getLots() > 0 ? request.getLots() : DEFAULT_LOTS;
        TriggerRequest trigger = new TriggerRequest();
        trigger.setInstrument(symbol); trigger.setEntryPrice(entry); trigger.setStopLoss(stopLoss);
        trigger.setTarget1(support.roundPrice(ce ? entry + TARGET1_ATR_MULTIPLIER * atr : entry - TARGET1_ATR_MULTIPLIER * atr));
        trigger.setTarget2(support.roundPrice(ce ? entry + TARGET2_ATR_MULTIPLIER * atr : entry - TARGET2_ATR_MULTIPLIER * atr));
        trigger.setTarget3(support.roundPrice(ce ? entry + TARGET3_ATR_MULTIPLIER * atr : entry - TARGET3_ATR_MULTIPLIER * atr));
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
