package org.com.sharekhan.strategy;

import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.service.MStockGainerLoserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * At 09:25 IST this template snapshots the F&O universe.  It chooses the five largest gainers
 * when advancing names outnumber declining names; otherwise it chooses the five largest losers.
 * The scan only creates candidates. A candidate gets an ATM CE/PE trading request only after a
 * five-minute opening-range breakout passes the volume, VWAP and candle-quality checks.
 */
@Slf4j
@Component
public class Fno0925MoverAtrBreakoutStrategy implements StrategyEvaluator {

    public static final String TEMPLATE_ID = "FNO_0925_MOVER_ATR_BREAKOUT";
    private static final StrategyMetadata METADATA = new StrategyMetadata(
            TEMPLATE_ID,
            "F&O 9:25 Mover Breakout",
            "At 9:25 chooses top 5 F&O gainers when gainers outnumber losers, otherwise top 5 losers. It enters ATM CE/PE only on a qualified morning opening-range breakout or VWAP-reclaim base breakout. CE requires a bullish completed five-minute signal candle; PE requires a bearish one.",
            "AUTO");
    private static final LocalTime SELECTION_TIME = LocalTime.of(9, 25);
    private static final int DEFAULT_LOTS = 3;
    private static final double STOP_ATR_MULTIPLIER = 1.5d;
    private static final double TARGET1_ATR_MULTIPLIER = 2d;
    private static final double TARGET2_ATR_MULTIPLIER = 3d;
    private static final double TARGET3_ATR_MULTIPLIER = 4d;

    private final Clock clock;
    private final StrategySupport support;
    private final ScriptMasterRepository scriptMasterRepository;
    private final MStockGainerLoserService gainerLoserService;
    private final Fno925EntryQualificationService entryQualificationService;
    private final MoverSelectionSnapshotStore snapshotStore;
    private final Map<RunKey, Set<String>> submittedSymbolsByRun = new ConcurrentHashMap<>();

    @Autowired
    public Fno0925MoverAtrBreakoutStrategy(StrategySupport support,
                                            ScriptMasterRepository scriptMasterRepository,
                                            MStockGainerLoserService gainerLoserService,
                                            Fno925EntryQualificationService entryQualificationService,
                                            MoverSelectionSnapshotStore snapshotStore) {
        this(support, scriptMasterRepository, gainerLoserService, entryQualificationService, snapshotStore,
                Clock.system(StrategySupport.MARKET_ZONE));
    }

    Fno0925MoverAtrBreakoutStrategy(StrategySupport support, ScriptMasterRepository scriptMasterRepository,
                                  MStockGainerLoserService gainerLoserService,
                                  Fno925EntryQualificationService entryQualificationService, MoverSelectionSnapshotStore snapshotStore, Clock clock) {
        this.clock = clock;
        this.snapshotStore = snapshotStore;
        this.support = support;
        this.scriptMasterRepository = scriptMasterRepository;
        this.gainerLoserService = gainerLoserService;
        this.entryQualificationService = entryQualificationService;
    }

    @Override
    public StrategyMetadata metadata() {
        return METADATA;
    }

    @Override
    public StrategyApplyResponse apply(StrategyApplyRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        String symbol = request.getSymbol() == null ? "FNO_UNIVERSE" : request.getSymbol().trim().toUpperCase(Locale.ROOT);
        if (now.toLocalTime().isBefore(SELECTION_TIME)) {
            return support.waiting(METADATA, symbol, "Waiting for the 09:25 F&O mover snapshot.");
        }

        var snapshot = snapshotStore.loadOrCapture(TEMPLATE_ID, now, this::snapshotUniverse);
        if (snapshot.isEmpty()) {
            return support.waiting(METADATA, symbol,
                    "Missing original 09:25 F&O mover snapshot; refusing to select later movers today.");
        }
        List<Fno925Candidate> selections = snapshot.get();
        submittedSymbolsByRun.keySet().removeIf(key -> key.day().isBefore(now.toLocalDate()));
        if (selections.isEmpty()) {
            return support.waiting(METADATA, symbol, "No eligible F&O movers were available at 09:25; no trade will be created today.");
        }

        Set<String> submitted = submittedSymbolsByRun.computeIfAbsent(
                new RunKey(now.toLocalDate(), request.getUserId(), request.getBrokerCredentialsId(), sourceFor(request)),
                ignored -> ConcurrentHashMap.newKeySet());
        // Broker waits for one account must never hold the singleton strategy lock.
        synchronized (submitted) {
            return evaluateSelections(request, selections, submitted, now, symbol);
        }
    }

    private StrategyApplyResponse evaluateSelections(StrategyApplyRequest request, List<Fno925Candidate> selections,
                                                     Set<String> submitted, LocalDateTime now, String symbol) {
        List<Triggered> triggered = new ArrayList<>();
        List<String> waiting = new ArrayList<>();
        int evaluated = 0;
        int qualified = 0;
        String strategySource = sourceFor(request);
        for (Fno925Candidate selection : selections) {
            if (submitted.contains(selection.symbol())) {
                logQualification(selection, "ALREADY_SUBMITTED", null, null);
                continue;
            }
            if (support.hasEntryForSymbolOn(strategySource, now.toLocalDate(), request.getUserId(), selection.symbol())) {
                waiting.add(selection.symbol() + ": an F&O mover entry has already been triggered for this symbol today");
                submitted.add(selection.symbol());
                logQualification(selection, "DAILY_ENTRY_LIMIT_REACHED", null, null);
                continue;
            }
            if (entryQualificationService.isCycleExpired()) {
                waiting.add(selection.symbol() + ": mover evaluation expired; waiting for a fresh cycle");
                continue;
            }
            try {
                evaluated++;
                support.warmUpPreferredFnoFeeds(request, metadataFor(selection.optionType()), selection.symbol(), selection.spot());
                Fno925EntryQualificationService.Qualification qualification = entryQualificationService.qualifyWithDirectionalCandle(selection, now);
                if (!qualification.qualified()) {
                    support.auditStrategy(request, metadataFor(selection.optionType()), selection.symbol(),
                            "STRATEGY_EVALUATION", "REJECTED", qualification.reason(), null, null);
                    logQualification(selection, "WAITING", qualification.reason(), null);
                    waiting.add(selection.symbol() + ": " + qualification.reason());
                    continue;
                }
                qualified++;
                TriggerRequest trigger = buildTrigger(request, selection, qualification.signal());
                support.auditStrategy(request, metadataFor(selection.optionType()), selection.symbol(),
                        "STRATEGY_EVALUATION", "QUALIFIED", qualification.signal().setup(), qualification.signal(), trigger);
                TriggerTradeRequestEntity existing = support.findExisting(trigger);
                if (existing != null) {
                    logQualification(selection, "DUPLICATE", null, qualification.signal());
                    triggered.add(new Triggered(selection, trigger, existing, true, qualification.signal().setup()));
                    submitted.add(selection.symbol());
                    continue;
                }
                if (entryQualificationService.isCycleExpired()) {
                    waiting.add(selection.symbol() + ": mover evaluation expired; waiting for a fresh cycle");
                    continue;
                }
                log.info("MOVER_ENTRY_CHECK | template={} | userId={} | brokerCredentialsId={} | symbol={} | signalCandleAt={} | {}",
                        TEMPLATE_ID, request.getUserId(), request.getBrokerCredentialsId(), selection.symbol(),
                        qualification.signal().breakoutCandle().date().atTime(qualification.signal().breakoutCandle().time()),
                        entryQualificationService.cycleTiming());
                TriggerTradeRequestEntity trade;
                try {
                    trade = org.com.sharekhan.shadow.MoverShadowSignalContext.withSignal(
                            qualification.signal().breakoutCandle().date().atTime(qualification.signal().breakoutCandle().time()),
                            () -> support.executeTriggeredTrade(trigger));
                } catch (org.com.sharekhan.exception.EntryNotReadyException notReady) {
                    support.auditStrategy(request, metadataFor(selection.optionType()), selection.symbol(),
                            "STRATEGY_EVALUATION", "WAITING", notReady.getMessage(), qualification.signal(), trigger);
                    logQualification(selection, "WAITING", notReady.getMessage(), qualification.signal());
                    waiting.add(selection.symbol() + ": " + notReady.getMessage());
                    continue;
                }
                support.auditTradeRequest(request, metadataFor(selection.optionType()), selection.symbol(), "CREATED", trigger, trade);
                logQualification(selection, "SUBMITTED", null, qualification.signal());
                triggered.add(new Triggered(selection, trigger, trade, false, qualification.signal().setup()));
                submitted.add(selection.symbol());
            } catch (SharedMarketDecisions.ExpiredDecisionException expired) {
                waiting.add(selection.symbol() + ": " + expired.getMessage());
                logQualification(selection, "WAITING", expired.getMessage(), null);
            }
        }
        log.info("FNO_QUALIFICATION_SUMMARY | template={} | selected={} | evaluated={} | qualified={} | submitted={} | waiting={}",
                TEMPLATE_ID, selections.size(), evaluated, qualified,
                triggered.stream().filter(item -> !item.duplicate()).count(), waiting.size());
        if (triggered.isEmpty()) {
            String message = waiting.isEmpty()
                    ? "All selected F&O mover candidates already have trading requests today."
                    : "F&O 09:25 candidates are waiting for confirmation: " + waiting + ".";
            return support.waiting(METADATA, symbol, message);
        }
        Triggered first = triggered.get(0);
        long newRequests = triggered.stream().filter(item -> !item.duplicate()).count();
        return StrategyApplyResponse.builder()
                .status(newRequests > 0 ? "triggered" : "duplicate")
                .message("F&O 09:25 mover created " + newRequests + " qualified breakout request(s) for "
                        + triggered.stream().map(item -> item.selection().symbol() + " (" + item.setup() + ")").sorted().toList()
                        + "; remaining candidates continue to be monitored.")
                .templateId(TEMPLATE_ID)
                .symbol(first.selection().symbol())
                .direction(first.selection().optionType())
                .breakoutClose(support.roundPrice(first.trigger().getEntryPrice()))
                .triggerRequest(first.trigger())
                .tradeRequest(first.request())
                .build();
    }

    private List<Fno925Candidate> snapshotUniverse() {
        return MoverOpeningSelection.capture(scriptMasterRepository, gainerLoserService, support);
    }

    private TriggerRequest buildTrigger(StrategyApplyRequest request,
                                        Fno925Candidate selection,
                                        Fno925EntryQualificationService.Signal signal) {
        boolean ce = "CE".equals(selection.optionType());
        double entry = signal.entryPrice();
        double atrStop = ce
                ? entry - (STOP_ATR_MULTIPLIER * signal.atr())
                : entry + (STOP_ATR_MULTIPLIER * signal.atr());
        // A structural swing invalidates the thesis. Keep the farther of it and the
        // 1.5 ATR stop so normal five-minute noise cannot force a premature exit.
        double stop = ce ? Math.min(signal.stopLoss(), atrStop) : Math.max(signal.stopLoss(), atrStop);
        String expiry = support.preferredFnoExpiry(selection.symbol(), selection.optionType());
        StrategySupport.FnoOptionContract optionContract = support.resolveFnoEntryContract(
                request, metadataFor(selection.optionType()), selection.symbol(), expiry,
                support.nearestStrike(selection.symbol(), selection.optionType(), expiry, entry));
        TriggerRequest trigger = new TriggerRequest();
        trigger.setInstrument(selection.symbol());
        trigger.setEntryPrice(entry);
        trigger.setStopLoss(support.roundPrice(stop));
        trigger.setTarget1(support.roundPrice(ce ? entry + (TARGET1_ATR_MULTIPLIER * signal.atr())
                : entry - (TARGET1_ATR_MULTIPLIER * signal.atr())));
        trigger.setTarget2(support.roundPrice(ce ? entry + (TARGET2_ATR_MULTIPLIER * signal.atr())
                : entry - (TARGET2_ATR_MULTIPLIER * signal.atr())));
        trigger.setTarget3(support.roundPrice(ce ? entry + (TARGET3_ATR_MULTIPLIER * signal.atr())
                : entry - (TARGET3_ATR_MULTIPLIER * signal.atr())));
        trigger.setOptionType(selection.optionType());
        trigger.setExpiry(optionContract.expiry());
        trigger.setStrikePrice(optionContract.strike());
        trigger.setIntraday(request.getIntraday() == null || request.getIntraday());
        trigger.setUseSpotPrice(true);
        trigger.setUseSpotForEntry(true);
        trigger.setUseSpotForSl(true);
        trigger.setUseSpotForTarget(true);
        trigger.setSpotScripCode(selection.spot().getScripCode());
        trigger.setUserId(request.getUserId());
        trigger.setBrokerCredentialsId(request.getBrokerCredentialsId());
        trigger.setSource(sourceFor(request));
        int lots = request.getLots() != null && request.getLots() > 0 ? request.getLots() : DEFAULT_LOTS;
        trigger.setLots(lots);
        trigger.setQuantity(lots);
        // Existing target handling books partial lots and advances the stop when TSL is enabled.
        trigger.setTslEnabled(true);
        return trigger;
    }

    private StrategyMetadata metadataFor(String optionType) {
        return new StrategyMetadata(TEMPLATE_ID, METADATA.name(), METADATA.description(), optionType);
    }

    private String sourceFor(StrategyApplyRequest request) {
        return request.getSource() == null || request.getSource().isBlank() ? "strategy:" + TEMPLATE_ID : request.getSource().trim();
    }

    private void logQualification(Fno925Candidate candidate,
                                  String outcome,
                                  String reason,
                                  Fno925EntryQualificationService.Signal signal) {
        if (signal == null) {
            log.info("FNO_QUALIFICATION | template={} | symbol={} | optionType={} | outcome={} | reason={}",
                    TEMPLATE_ID, candidate.symbol(), candidate.optionType(), outcome,
                    reason == null ? "NA" : reason);
            return;
        }
        log.info("FNO_QUALIFICATION | template={} | symbol={} | optionType={} | outcome={} | setup={} | "
                        + "breakoutTime={} | entry={} | stop={} | rangeHigh={} | rangeLow={}",
                TEMPLATE_ID, candidate.symbol(), candidate.optionType(), outcome, signal.setup(),
                signal.breakoutCandle().time(), support.roundPrice(signal.entryPrice()), support.roundPrice(signal.stopLoss()),
                support.roundPrice(signal.openingRangeHigh()), support.roundPrice(signal.openingRangeLow()));
    }

    private record RunKey(LocalDate day, Long userId, Long brokerCredentialsId, String source) { }
    private record Triggered(Fno925Candidate selection,
                             TriggerRequest trigger,
                             TriggerTradeRequestEntity request,
                             boolean duplicate,
                             String setup) { }
}
