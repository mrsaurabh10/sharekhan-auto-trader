package org.com.sharekhan.strategy;

import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.service.MStockGainerLoserService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
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
            "At 9:25 chooses top 5 F&O gainers when gainers outnumber losers, otherwise top 5 losers. It enters ATM CE/PE only on a qualified morning opening-range breakout or VWAP-reclaim base breakout.",
            "AUTO");
    private static final LocalTime SELECTION_TIME = LocalTime.of(9, 25);
    private static final int TOP_COUNT = 5;
    private static final int DEFAULT_LOTS = 3;

    private final StrategySupport support;
    private final ScriptMasterRepository scriptMasterRepository;
    private final MStockGainerLoserService gainerLoserService;
    private final Fno925EntryQualificationService entryQualificationService;
    private final Map<LocalDate, List<Fno925Candidate>> selectionsByDay = new ConcurrentHashMap<>();
    private final Map<RunKey, Set<String>> submittedSymbolsByRun = new ConcurrentHashMap<>();

    public Fno0925MoverAtrBreakoutStrategy(StrategySupport support,
                                            ScriptMasterRepository scriptMasterRepository,
                                            MStockGainerLoserService gainerLoserService,
                                            Fno925EntryQualificationService entryQualificationService) {
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
        LocalDateTime now = LocalDateTime.now(StrategySupport.MARKET_ZONE);
        String symbol = request.getSymbol() == null ? "FNO_UNIVERSE" : request.getSymbol().trim().toUpperCase(Locale.ROOT);
        if (now.toLocalTime().isBefore(SELECTION_TIME)) {
            return support.waiting(METADATA, symbol, "Waiting for the 09:25 F&O mover snapshot.");
        }

        List<Fno925Candidate> selections = selectionsByDay.computeIfAbsent(now.toLocalDate(), ignored -> snapshotUniverse());
        selectionsByDay.keySet().removeIf(day -> day.isBefore(now.toLocalDate()));
        submittedSymbolsByRun.keySet().removeIf(key -> key.day().isBefore(now.toLocalDate()));
        if (selections.isEmpty()) {
            return support.waiting(METADATA, symbol, "No eligible F&O movers were available at 09:25; no trade will be created today.");
        }

        Set<String> submitted = submittedSymbolsByRun.computeIfAbsent(
                new RunKey(now.toLocalDate(), request.getUserId(), request.getBrokerCredentialsId(), sourceFor(request)),
                ignored -> ConcurrentHashMap.newKeySet());
        List<Triggered> triggered = new ArrayList<>();
        List<String> waiting = new ArrayList<>();
        for (Fno925Candidate selection : selections) {
            if (submitted.contains(selection.symbol())) {
                continue;
            }
            Fno925EntryQualificationService.Qualification qualification = entryQualificationService.qualify(selection, now);
            if (!qualification.qualified()) {
                waiting.add(selection.symbol() + ": " + qualification.reason());
                continue;
            }
            TriggerRequest trigger = buildTrigger(request, selection, qualification.signal());
            TriggerTradeRequestEntity existing = support.findExisting(trigger);
            if (existing != null) {
                triggered.add(new Triggered(selection, trigger, existing, true, qualification.signal().setup()));
                submitted.add(selection.symbol());
                continue;
            }
            triggered.add(new Triggered(selection, trigger, support.executeTriggeredTrade(trigger), false, qualification.signal().setup()));
            submitted.add(selection.symbol());
        }
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
        Set<String> fnoUniverse = new HashSet<>(scriptMasterRepository.findDistinctOptionUnderlyingSymbols());
        List<MStockGainerLoserService.Mover> gainers = gainerLoserService.topGainers().stream()
                .filter(mover -> fnoUniverse.contains(mover.symbol()))
                .toList();
        List<MStockGainerLoserService.Mover> losers = gainerLoserService.topLosers().stream()
                .filter(mover -> fnoUniverse.contains(mover.symbol()))
                .toList();
        boolean chooseGainers = gainers.size() > losers.size();
        List<MStockGainerLoserService.Mover> chosen = (chooseGainers ? gainers : losers).stream()
                .sorted(chooseGainers
                        ? Comparator.comparingDouble(MStockGainerLoserService.Mover::changePercent).reversed()
                        : Comparator.comparingDouble(MStockGainerLoserService.Mover::changePercent))
                .limit(TOP_COUNT)
                .toList();
        String optionType = chooseGainers ? "CE" : "PE";
        List<Fno925Candidate> selections = new ArrayList<>();
        for (MStockGainerLoserService.Mover mover : chosen) {
            try {
                ScriptMasterEntity spot = support.resolveSpotScript(mover.symbol());
                selections.add(new Fno925Candidate(mover.symbol(), spot, optionType));
            } catch (Exception e) {
                log.debug("Skipping F&O mover candidate {}: {}", mover.symbol(), e.getMessage());
            }
        }
        log.info("F&O 09:25 MStock snapshot: fnoGainers={}, fnoLosers={}, selectedSide={}, selected={}",
                gainers.size(), losers.size(), optionType, selections.stream().map(Fno925Candidate::symbol).toList());
        return selections;
    }

    private TriggerRequest buildTrigger(StrategyApplyRequest request,
                                        Fno925Candidate selection,
                                        Fno925EntryQualificationService.Signal signal) {
        boolean ce = "CE".equals(selection.optionType());
        double entry = signal.entryPrice();
        double stop = signal.stopLoss();
        double risk = Math.abs(entry - stop);
        String expiry = support.nearestExpiry(selection.symbol(), selection.optionType());
        TriggerRequest trigger = new TriggerRequest();
        trigger.setInstrument(selection.symbol());
        trigger.setEntryPrice(entry);
        trigger.setStopLoss(stop);
        trigger.setTarget1(support.roundPrice(ce ? entry + risk : entry - risk));
        trigger.setTarget2(support.roundPrice(ce ? entry + (2d * risk) : entry - (2d * risk)));
        trigger.setTarget3(support.roundPrice(ce ? entry + (3d * risk) : entry - (3d * risk)));
        trigger.setOptionType(selection.optionType());
        trigger.setExpiry(expiry);
        trigger.setStrikePrice(support.nearestStrike(selection.symbol(), selection.optionType(), expiry, entry));
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

    private String sourceFor(StrategyApplyRequest request) {
        return request.getSource() == null || request.getSource().isBlank() ? "strategy:" + TEMPLATE_ID : request.getSource().trim();
    }

    private record RunKey(LocalDate day, Long userId, Long brokerCredentialsId, String source) { }
    private record Triggered(Fno925Candidate selection,
                             TriggerRequest trigger,
                             TriggerTradeRequestEntity request,
                             boolean duplicate,
                             String setup) { }
}
