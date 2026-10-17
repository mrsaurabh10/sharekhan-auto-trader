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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
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

/** Buy-only cash-equity version of the 09:25 mover breakout, sized by rupees per stock. */
@Slf4j
@Component
public class Stock0925MoverAtrBreakoutStrategy implements StrategyEvaluator {

    public static final String TEMPLATE_ID = "STOCK_0925_MOVER_ATR_BREAKOUT";
    private static final StrategyMetadata METADATA = new StrategyMetadata(
            TEMPLATE_ID,
            "Stocks 9:25 Mover Breakout",
            "At 9:25 selects the top 5 gainers from the F&O stock universe. Buys cash shares on a qualified opening-range or VWAP-reclaim breakout with a bullish completed five-minute signal candle, sized by investment per stock.",
            "");
    private static final LocalTime SELECTION_TIME = LocalTime.of(9, 25);
    private static final int TOP_COUNT = 5;
    private static final double STOP_ATR_MULTIPLIER = 1.5d;
    private static final double TARGET1_ATR_MULTIPLIER = 2d;
    private static final double TARGET2_ATR_MULTIPLIER = 3d;
    private static final double TARGET3_ATR_MULTIPLIER = 4d;

    private final Clock clock;
    private final StrategySupport support;
    private final ScriptMasterRepository scriptMasterRepository;
    private final MStockGainerLoserService gainerLoserService;
    private final Fno925EntryQualificationService entryQualificationService;
    private final Map<LocalDate, List<Fno925Candidate>> selectionsByDay = new ConcurrentHashMap<>();
    private final Map<RunKey, Set<String>> submittedSymbolsByRun = new ConcurrentHashMap<>();

    @Autowired
    public Stock0925MoverAtrBreakoutStrategy(StrategySupport support,
                                            ScriptMasterRepository scriptMasterRepository,
                                            MStockGainerLoserService gainerLoserService,
                                            Fno925EntryQualificationService entryQualificationService) {
        this(support, scriptMasterRepository, gainerLoserService, entryQualificationService,
                Clock.system(StrategySupport.MARKET_ZONE));
    }

    Stock0925MoverAtrBreakoutStrategy(StrategySupport support, ScriptMasterRepository scriptMasterRepository,
                                     MStockGainerLoserService gainerLoserService,
                                     Fno925EntryQualificationService entryQualificationService, Clock clock) {
        this.clock = clock;
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
    public synchronized StrategyApplyResponse apply(StrategyApplyRequest request) {
        validateInvestment(request);
        LocalDateTime now = LocalDateTime.now(clock);
        String symbol = request.getSymbol() == null ? "FNO_UNIVERSE" : request.getSymbol().trim().toUpperCase(Locale.ROOT);
        if (!now.toLocalTime().isBefore(LocalTime.of(15, 20))) {
            return support.waiting(METADATA, symbol, "Stock entries are closed for today.");
        }
        if (now.toLocalTime().isBefore(SELECTION_TIME)) {
            return support.waiting(METADATA, symbol, "Waiting for the 09:25 stock mover snapshot.");
        }

        List<Fno925Candidate> selections = selectionsByDay.computeIfAbsent(now.toLocalDate(), ignored -> snapshotUniverse());
        selectionsByDay.keySet().removeIf(day -> day.isBefore(now.toLocalDate()));
        submittedSymbolsByRun.keySet().removeIf(key -> key.day().isBefore(now.toLocalDate()));
        if (selections.isEmpty()) {
            return support.waiting(METADATA, symbol, "No eligible stock movers were available at 09:25; no trade will be created today.");
        }

        Set<String> submitted = submittedSymbolsByRun.computeIfAbsent(
                new RunKey(now.toLocalDate(), request.getUserId(), request.getBrokerCredentialsId(), sourceFor(request)),
                ignored -> ConcurrentHashMap.newKeySet());
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
            if (support.hasStockMoverRequestOn(strategySource, now.toLocalDate(), request.getUserId(), selection.symbol())) {
                waiting.add(selection.symbol() + ": a stock mover entry has already been triggered for this symbol today");
                submitted.add(selection.symbol());
                logQualification(selection, "DAILY_ENTRY_LIMIT_REACHED", null, null);
                continue;
            }
            evaluated++;
            support.warmUpStockFeed(selection.spot());
            Fno925EntryQualificationService.Qualification qualification = entryQualificationService.qualifyWithDirectionalCandle(selection, now);
            if (!qualification.qualified()) {
                support.auditStrategy(request, METADATA, selection.symbol(),
                        "STRATEGY_EVALUATION", "REJECTED", qualification.reason(), null, null);
                logQualification(selection, "WAITING", qualification.reason(), null);
                waiting.add(selection.symbol() + ": " + qualification.reason());
                continue;
            }
            qualified++;
            TriggerRequest trigger = buildTrigger(request, selection, qualification.signal());
            if (trigger.getQuantity() < 1) {
                waiting.add(selection.symbol() + ": investment per stock is below the price of one share");
                continue;
            }
            support.auditStrategy(request, METADATA, selection.symbol(),
                    "STRATEGY_EVALUATION", "QUALIFIED", qualification.signal().setup(), qualification.signal(), trigger);
            TriggerTradeRequestEntity existing = support.findActiveSetup(trigger, strategySource);
            if (existing != null) {
                logQualification(selection, "DUPLICATE", null, qualification.signal());
                triggered.add(new Triggered(selection, trigger, existing, true, qualification.signal().setup()));
                submitted.add(selection.symbol());
                continue;
            }
            TriggerTradeRequestEntity trade = support.executeTriggeredTrade(trigger);
            support.auditTradeRequest(request, METADATA, selection.symbol(), "CREATED", trigger, trade);
            logQualification(selection, "SUBMITTED", null, qualification.signal());
            triggered.add(new Triggered(selection, trigger, trade, false, qualification.signal().setup()));
            submitted.add(selection.symbol());
        }
        log.info("STOCK_QUALIFICATION_SUMMARY | template={} | selected={} | evaluated={} | qualified={} | submitted={} | waiting={}",
                TEMPLATE_ID, selections.size(), evaluated, qualified,
                triggered.stream().filter(item -> !item.duplicate()).count(), waiting.size());
        if (triggered.isEmpty()) {
            String message = waiting.isEmpty()
                    ? "All selected stock mover candidates already have trading requests today."
                    : "Stock 09:25 candidates are waiting for confirmation: " + waiting + ".";
            return support.waiting(METADATA, symbol, message);
        }
        Triggered first = triggered.get(0);
        long newRequests = triggered.stream().filter(item -> !item.duplicate()).count();
        return StrategyApplyResponse.builder()
                .status(newRequests > 0 ? "triggered" : "duplicate")
                .message("Stock 09:25 mover created " + newRequests + " qualified breakout request(s) for "
                        + triggered.stream().map(item -> item.selection().symbol() + " (" + item.setup() + ")").sorted().toList()
                        + "; remaining candidates continue to be monitored.")
                .templateId(TEMPLATE_ID)
                .symbol(first.selection().symbol())
                .direction("BUY")
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
        List<MStockGainerLoserService.Mover> chosen = gainers.stream()
                .filter(mover -> Double.isFinite(mover.changePercent()) && mover.changePercent() > 0)
                .sorted(Comparator.comparingDouble(MStockGainerLoserService.Mover::changePercent).reversed())
                .toList();
        List<Fno925Candidate> selections = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MStockGainerLoserService.Mover mover : chosen) {
            if (selections.size() == TOP_COUNT) break;
            if (!seen.add(mover.symbol())) continue;
            try {
                ScriptMasterEntity spot = support.resolveSpotScript(mover.symbol());
                if ("NC".equalsIgnoreCase(spot.getExchange())) {
                    selections.add(new Fno925Candidate(mover.symbol(), spot, "CE"));
                }
            } catch (Exception e) {
                log.debug("Skipping stock mover candidate {}: {}", mover.symbol(), e.getMessage());
            }
        }
        log.info("Stock 09:25 snapshot: selected={}", selections.stream().map(Fno925Candidate::symbol).toList());
        return selections;
    }

    private TriggerRequest buildTrigger(StrategyApplyRequest request,
                                        Fno925Candidate selection,
                                        Fno925EntryQualificationService.Signal signal) {
        double entry = support.roundPrice(signal.entryPrice());
        double stop = support.roundPrice(Math.min(signal.stopLoss(), entry - STOP_ATR_MULTIPLIER * signal.atr()));
        if (!Double.isFinite(entry) || entry <= 0 || !Double.isFinite(signal.atr()) || signal.atr() <= 0
                || !Double.isFinite(stop) || stop <= 0 || stop >= entry) {
            throw new IllegalArgumentException("Invalid stock entry, ATR or stop loss");
        }
        BigDecimal shares = request.getInvestmentPerStock().divide(BigDecimal.valueOf(entry),
                0, RoundingMode.DOWN);
        if (shares.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("Investment per stock exceeds supported share quantity");
        }
        int quantity = shares.intValueExact();
        TriggerRequest trigger = new TriggerRequest();
        trigger.setInstrument(selection.symbol());
        trigger.setExchange("NC");
        trigger.setAction("BUY");
        trigger.setEntryPrice(entry);
        trigger.setStopLoss(stop);
        trigger.setTarget1(support.roundPrice(entry + TARGET1_ATR_MULTIPLIER * signal.atr()));
        trigger.setTarget2(support.roundPrice(entry + TARGET2_ATR_MULTIPLIER * signal.atr()));
        trigger.setTarget3(support.roundPrice(entry + TARGET3_ATR_MULTIPLIER * signal.atr()));
        trigger.setIntraday(request.getIntraday() == null || request.getIntraday());
        trigger.setUserId(request.getUserId());
        trigger.setBrokerCredentialsId(request.getBrokerCredentialsId());
        trigger.setSource(sourceFor(request));
        trigger.setQuantity(quantity);
        trigger.setTslEnabled(true);
        return trigger;
    }

    public static void validateInvestment(StrategyApplyRequest request) {
        if (request.getUserId() == null || request.getUserId() <= 0) {
            throw new IllegalArgumentException("userId is required for the stock mover strategy");
        }
        BigDecimal amount = request.getInvestmentPerStock();
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2
                || amount.compareTo(new BigDecimal("999999999.99")) > 0) {
            throw new IllegalArgumentException("Investment per stock must be a positive rupee amount with at most two decimal places (maximum 999999999.99)");
        }
    }

    private String sourceFor(StrategyApplyRequest request) {
        return request.getSource() == null || request.getSource().isBlank() ? "strategy:" + TEMPLATE_ID : request.getSource().trim();
    }

    private void logQualification(Fno925Candidate candidate,
                                  String outcome,
                                  String reason,
                                  Fno925EntryQualificationService.Signal signal) {
        if (signal == null) {
            log.info("STOCK_QUALIFICATION | template={} | symbol={} | side=BUY | outcome={} | reason={}",
                    TEMPLATE_ID, candidate.symbol(), outcome,
                    reason == null ? "NA" : reason);
            return;
        }
        log.info("STOCK_QUALIFICATION | template={} | symbol={} | side=BUY | outcome={} | setup={} | "
                        + "breakoutTime={} | entry={} | stop={} | rangeHigh={} | rangeLow={}",
                TEMPLATE_ID, candidate.symbol(), outcome, signal.setup(),
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
