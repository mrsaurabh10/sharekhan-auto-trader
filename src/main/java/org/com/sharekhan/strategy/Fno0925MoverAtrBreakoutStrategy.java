package org.com.sharekhan.strategy;

import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.dto.StrategyApplyResponse;
import org.com.sharekhan.dto.TriggerRequest;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.service.MStockGainerLoserService;
import org.springframework.beans.factory.annotation.Value;
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
 * The selected stocks are inserted into trading requests immediately. Their spot entry is the
 * 09:25 snapshot price plus/minus the configured ATR distance, and the option contract is always
 * ATM CE for gainers and ATM PE for losers.
 */
@Slf4j
@Component
public class Fno0925MoverAtrBreakoutStrategy implements StrategyEvaluator {

    public static final String TEMPLATE_ID = "FNO_0925_MOVER_ATR_BREAKOUT";
    private static final StrategyMetadata METADATA = new StrategyMetadata(
            TEMPLATE_ID,
            "F&O 9:25 Mover ATR Entry",
            "At 9:25 chooses top 5 gainers when gainers outnumber losers, otherwise top 5 losers, and immediately submits ATM CE/PE requests with a configurable ATR(75) spot entry.",
            "AUTO");
    private static final LocalTime SELECTION_TIME = LocalTime.of(9, 25);
    private static final int ATR_PERIOD = 75;
    private static final int TOP_COUNT = 5;
    private static final int DEFAULT_LOTS = 3;
    private static final double STOP_LOSS_ATR_MULTIPLIER = 2d;
    private static final double TARGET1_ATR_MULTIPLIER = 2d;
    private static final double TARGET2_ATR_MULTIPLIER = 3d;
    private static final double TARGET3_ATR_MULTIPLIER = 4d;

    private final StrategySupport support;
    private final ScriptMasterRepository scriptMasterRepository;
    private final MStockGainerLoserService gainerLoserService;
    private final double breakoutAtrMultiplier;
    private final Map<LocalDate, List<Selection>> selectionsByDay = new ConcurrentHashMap<>();

    public Fno0925MoverAtrBreakoutStrategy(StrategySupport support,
                                            ScriptMasterRepository scriptMasterRepository,
                                            MStockGainerLoserService gainerLoserService,
                                            @Value("${app.strategy.fno-0925-mover.breakout-atr-multiplier:0.5}") double breakoutAtrMultiplier) {
        if (!Double.isFinite(breakoutAtrMultiplier) || breakoutAtrMultiplier <= 0d) {
            throw new IllegalArgumentException("app.strategy.fno-0925-mover.breakout-atr-multiplier must be greater than zero");
        }
        this.support = support;
        this.scriptMasterRepository = scriptMasterRepository;
        this.gainerLoserService = gainerLoserService;
        this.breakoutAtrMultiplier = breakoutAtrMultiplier;
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

        List<Selection> selections = selectionsByDay.computeIfAbsent(now.toLocalDate(), ignored -> snapshotUniverse());
        selectionsByDay.keySet().removeIf(day -> day.isBefore(now.toLocalDate()));
        if (selections.isEmpty()) {
            return support.waiting(METADATA, symbol, "No eligible F&O movers were available at 09:25; no trade will be created today.");
        }

        List<Triggered> triggered = new ArrayList<>();
        for (Selection selection : selections) {
            TriggerRequest trigger = buildTrigger(request, selection);
            TriggerTradeRequestEntity existing = support.findExisting(trigger);
            if (existing != null) {
                triggered.add(new Triggered(selection, trigger, existing, true));
                continue;
            }
            triggered.add(new Triggered(selection, trigger, support.executeTriggeredTrade(trigger), false));
        }
        Triggered first = triggered.get(0);
        long newRequests = triggered.stream().filter(item -> !item.duplicate()).count();
        return StrategyApplyResponse.builder()
                .status(newRequests > 0 ? "triggered" : "duplicate")
                .message("F&O 09:25 mover snapshot created " + newRequests + " immediate request(s) for "
                        + triggered.stream().map(item -> item.selection().symbol()).sorted().toList() + ".")
                .templateId(TEMPLATE_ID)
                .symbol(first.selection().symbol())
                .direction(first.selection().optionType())
                .breakoutClose(support.roundPrice(first.trigger().getEntryPrice()))
                .triggerRequest(first.trigger())
                .tradeRequest(first.request())
                .build();
    }

    private List<Selection> snapshotUniverse() {
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
        List<Selection> selections = new ArrayList<>();
        for (MStockGainerLoserService.Mover mover : chosen) {
            try {
                ScriptMasterEntity spot = support.resolveSpotScript(mover.symbol());
                CandleLoad load = support.loadCandlesWithHistoricalFallback(spot, ATR_PERIOD + 1);
                List<StrategyCandle> candles = load.candles().stream()
                        .sorted(Comparator.comparing(StrategyCandle::date).thenComparing(StrategyCandle::time))
                        .toList();
                double atr = atr(candles);
                if (atr > 0d) selections.add(new Selection(mover.symbol(), spot, mover.ltp(), atr, optionType));
            } catch (Exception e) {
                log.debug("Skipping F&O mover candidate {}: {}", mover.symbol(), e.getMessage());
            }
        }
        log.info("F&O 09:25 MStock snapshot: fnoGainers={}, fnoLosers={}, selectedSide={}, selected={}",
                gainers.size(), losers.size(), optionType, selections.stream().map(Selection::symbol).toList());
        return selections;
    }

    private double atr(List<StrategyCandle> candles) {
        if (candles.size() < ATR_PERIOD + 1) return 0d;
        List<StrategyCandle> tail = candles.subList(candles.size() - (ATR_PERIOD + 1), candles.size());
        double total = 0d;
        for (int index = 1; index < tail.size(); index++) {
            StrategyCandle previous = tail.get(index - 1);
            StrategyCandle current = tail.get(index);
            total += Math.max(current.high() - current.low(), Math.max(Math.abs(current.high() - previous.close()), Math.abs(current.low() - previous.close())));
        }
        return total / ATR_PERIOD;
    }

    private TriggerRequest buildTrigger(StrategyApplyRequest request, Selection selection) {
        boolean ce = "CE".equals(selection.optionType());
        double distance = selection.atr() * breakoutAtrMultiplier;
        double entry = support.roundPrice(ce ? selection.referencePrice() + distance : selection.referencePrice() - distance);
        double stop = support.roundPrice(ce
                ? entry - (STOP_LOSS_ATR_MULTIPLIER * selection.atr())
                : entry + (STOP_LOSS_ATR_MULTIPLIER * selection.atr()));
        String expiry = support.nearestExpiry(selection.symbol(), selection.optionType());
        TriggerRequest trigger = new TriggerRequest();
        trigger.setInstrument(selection.symbol());
        trigger.setEntryPrice(entry);
        trigger.setStopLoss(stop);
        trigger.setTarget1(support.roundPrice(ce
                ? entry + (TARGET1_ATR_MULTIPLIER * selection.atr())
                : entry - (TARGET1_ATR_MULTIPLIER * selection.atr())));
        trigger.setTarget2(support.roundPrice(ce
                ? entry + (TARGET2_ATR_MULTIPLIER * selection.atr())
                : entry - (TARGET2_ATR_MULTIPLIER * selection.atr())));
        trigger.setTarget3(support.roundPrice(ce
                ? entry + (TARGET3_ATR_MULTIPLIER * selection.atr())
                : entry - (TARGET3_ATR_MULTIPLIER * selection.atr())));
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
        trigger.setSource(request.getSource() == null || request.getSource().isBlank() ? "strategy:" + TEMPLATE_ID : request.getSource().trim());
        int lots = request.getLots() != null && request.getLots() > 0 ? request.getLots() : DEFAULT_LOTS;
        trigger.setLots(lots);
        trigger.setQuantity(lots);
        // Existing target handling books partial lots and advances the stop when TSL is enabled.
        trigger.setTslEnabled(true);
        return trigger;
    }

    private record Selection(String symbol, ScriptMasterEntity spot, double referencePrice, double atr, String optionType) { }
    private record Triggered(Selection selection, TriggerRequest trigger, TriggerTradeRequestEntity request, boolean duplicate) { }
}
