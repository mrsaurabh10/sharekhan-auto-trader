package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.dto.backtest.BacktestReplayRequest;
import org.com.sharekhan.dto.backtest.BacktestReplayResponse;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestReplayService {

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final String DEFAULT_INTERVAL = "5minute";
    private static final LocalTime DEFAULT_SQUARE_OFF_TIME = LocalTime.of(15, 20);
    private static final int DEFAULT_ATR_PERIOD = 75;

    private final TriggeredTradeSetupRepository tradeRepository;
    private final SharekhanHistoricalService historicalService;
    private final ScriptMasterRepository scriptMasterRepository;
    private final MStockHistoricalService mStockHistoricalService;

    public BacktestReplayResponse replayTrade(Long tradeSetupId, BacktestReplayRequest request) {
        if (tradeSetupId == null) {
            throw new IllegalArgumentException("tradeSetupId is required");
        }
        TriggeredTradeSetupEntity trade = tradeRepository.findById(tradeSetupId)
                .orElseThrow(() -> new IllegalArgumentException("Trade setup not found: " + tradeSetupId));

        BacktestReplayRequest safeRequest = request != null ? request : new BacktestReplayRequest();
        boolean intradayOnly = safeRequest.getIntradayOnly() == null || Boolean.TRUE.equals(safeRequest.getIntradayOnly());
        if (!intradayOnly) {
            throw new IllegalArgumentException("Only intraday replay is supported in the first version.");
        }

        String interval = textOrDefault(safeRequest.getInterval(), DEFAULT_INTERVAL);
        int intervalMinutes = intervalMinutes(interval);
        LocalTime squareOffTime = parseTime(safeRequest.getSquareOffTime(), DEFAULT_SQUARE_OFF_TIME);
        String sameCandlePolicy = normalizeEnum(safeRequest.getSameCandlePolicy(), "PESSIMISTIC");
        String triggerPricePolicy = normalizeTriggerPricePolicy(safeRequest.getTriggerPricePolicy());
        String executionPricePolicy = normalizeEnum(safeRequest.getExecutionPricePolicy(), "CANDLE_CLOSE");
        boolean reEntryOnStopLoss = Boolean.TRUE.equals(safeRequest.getReEntryOnStopLoss());
        int maxReEntries = reEntryOnStopLoss
                ? Math.max(1, safeRequest.getMaxReEntries() != null ? safeRequest.getMaxReEntries() : 1)
                : 0;

        LocalDateTime entryAt = resolveEntryAt(trade);
        LocalDate tradeDate = entryAt.toLocalDate();
        LocalDate historyFrom = tradeDate.minusDays(15);
        LocalDate historyTo = tradeDate;

        List<Candle> optionHistory = loadCandles(trade.getScripCode(), interval, historyFrom, historyTo);
        if (optionHistory.isEmpty()) {
            throw new IllegalArgumentException("No historical option candles found for scripCode " + trade.getScripCode());
        }

        List<Candle> spotHistory = trade.getSpotScripCode() != null
                ? loadCandles(trade.getSpotScripCode(), interval, historyFrom, historyTo)
                : List.of();

        Double optionEntryPrice = resolveOptionEntryPrice(trade, optionHistory, entryAt);
        if (optionEntryPrice == null || optionEntryPrice <= 0d) {
            throw new IllegalArgumentException("Unable to resolve option entry price for PnL. Trade actualEntryPrice is missing and no entry candle was found.");
        }
        Double spotEntryPrice = resolveSpotEntryPrice(trade, spotHistory, entryAt);

        LevelResolutionContext levelContext = new LevelResolutionContext(
                trade,
                optionHistory,
                spotHistory,
                entryAt,
                optionEntryPrice,
                spotEntryPrice,
                new ArrayList<>()
        );

        BacktestReplayRequest.Overrides overrides = safeRequest.getOverrides();
        LevelState stopLoss = resolveLevel(
                originalStopLossRule(overrides),
                trade.getStopLoss(),
                originalStopSource(trade),
                LevelPurpose.STOP_LOSS,
                levelContext);
        LevelState target1 = resolveLevel(
                overrides != null ? overrides.getTarget1() : null,
                trade.getTarget1(),
                originalTargetSource(trade),
                LevelPurpose.TARGET,
                levelContext);
        LevelState target2 = resolveLevel(
                overrides != null ? overrides.getTarget2() : null,
                trade.getTarget2(),
                originalTargetSource(trade),
                LevelPurpose.TARGET,
                levelContext);
        LevelState target3 = resolveLevel(
                overrides != null ? overrides.getTarget3() : null,
                trade.getTarget3(),
                originalTargetSource(trade),
                LevelPurpose.TARGET,
                levelContext);
        if (requiresSpotCandles(stopLoss, target1, target2, target3) && spotHistory.isEmpty()) {
            throw new IllegalArgumentException("Replay requires spot candles, but no spot history was loaded for spotScripCode "
                    + trade.getSpotScripCode());
        }

        boolean tslEnabled = resolveTslEnabled(trade, overrides);
        Double trailingSl = resolveTrailingSl(trade, overrides);

        Simulation simulation = simulate(
                trade,
                optionHistory,
                spotHistory,
                entryAt,
                optionEntryPrice,
                stopLoss,
                nullableTargets(target1, target2, target3),
                tslEnabled,
                trailingSl,
                squareOffTime,
                sameCandlePolicy,
                triggerPricePolicy,
                executionPricePolicy,
                reEntryOnStopLoss,
                maxReEntries,
                intervalMinutes
        );

        BacktestReplayResponse.ResolvedConfig resolved = BacktestReplayResponse.ResolvedConfig.builder()
                .interval(interval)
                .intradayOnly(true)
                .squareOffTime(squareOffTime.toString())
                .sameCandlePolicy(sameCandlePolicy)
                .triggerPricePolicy(triggerPricePolicy)
                .executionPricePolicy(executionPricePolicy)
                .reEntryOnStopLoss(reEntryOnStopLoss)
                .maxReEntries(maxReEntries)
                .entryPriceForPnl(round(optionEntryPrice))
                .stopLoss(levelValue(stopLoss))
                .target1(levelValue(target1))
                .target2(levelValue(target2))
                .target3(levelValue(target3))
                .trailingSl(roundNullable(trailingSl))
                .tslEnabled(tslEnabled)
                .stopLossPriceSource(stopLoss != null ? stopLoss.source().name() : null)
                .targetPriceSource(firstSource(target1, target2, target3))
                .build();

        List<String> warnings = new ArrayList<>(levelContext.warnings());
        warnings.addAll(simulation.warnings());

        return BacktestReplayResponse.builder()
                .status("success")
                .message("Replay completed")
                .tradeSetupId(tradeSetupId)
                .trade(snapshot(trade))
                .resolved(resolved)
                .actual(actualResult(trade))
                .backtest(simulation.result())
                .events(simulation.events())
                .warnings(warnings)
                .build();
    }

    private Simulation simulate(TriggeredTradeSetupEntity trade,
                                List<Candle> optionHistory,
                                List<Candle> spotHistory,
                                LocalDateTime entryAt,
                                double optionEntryPrice,
                                LevelState initialStopLoss,
                                List<LevelState> targets,
                                boolean tslEnabled,
                                Double trailingSl,
                                LocalTime squareOffTime,
                                String sameCandlePolicy,
                                String triggerPricePolicy,
                                String executionPricePolicy,
                                boolean reEntryOnStopLoss,
                                int maxReEntries,
                                int intervalMinutes) {
        List<BacktestReplayResponse.Event> events = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        LocalDate tradeDate = entryAt.toLocalDate();
        ScriptMasterEntity script = trade.getScripCode() != null ? scriptMasterRepository.findByScripCode(trade.getScripCode()) : null;
        int lotSize = script != null && script.getLotSize() != null && script.getLotSize() > 0 ? script.getLotSize() : 1;
        long totalQuantity = resolveQuantity(trade, lotSize);
        int currentLots = resolveLots(trade, totalQuantity, lotSize);
        int originalLots = trade.getOriginalLots() != null && trade.getOriginalLots() > 0
                ? Math.max(trade.getOriginalLots(), currentLots)
                : Math.max(currentLots, 1);

        Position position = new Position(totalQuantity, currentLots, originalLots, initialStopLoss, false, optionEntryPrice);
        double pnl = 0d;
        LocalDateTime lastExitAt = null;
        Double lastExitPrice = null;
        String lastExitReason = null;
        int exitCount = 0;
        int reEntriesUsed = 0;
        boolean waitingForReEntry = false;

        events.add(BacktestReplayResponse.Event.builder()
                .at(entryAt)
                .type("ENTRY")
                .reason("ORIGINAL_ENTRY")
                .priceSource("OPTION")
                .optionPrice(round(optionEntryPrice))
                .quantity(totalQuantity)
                .lots(currentLots)
                .build());

        List<Candle> replayCandles = optionHistory.stream()
                .filter(c -> tradeDate.equals(c.dateTime().toLocalDate()))
                .filter(c -> !c.dateTime().isBefore(entryAt))
                .filter(c -> !c.dateTime().toLocalTime().isAfter(squareOffTime))
                .sorted(Comparator.comparing(Candle::dateTime))
                .toList();

        Candle lastSeen = null;
        for (Candle optionCandle : replayCandles) {
            lastSeen = optionCandle;
            Candle spotCandle = matchingSpotCandle(spotHistory, optionCandle, entryAt, intervalMinutes);

            if (position.quantity() <= 0L) {
                if (waitingForReEntry
                        && reEntriesUsed < maxReEntries
                        && isReEntryTriggered(optionCandle, optionEntryPrice, triggerPricePolicy)) {
                    double reEntryPrice = optionCandle.close();
                    position = new Position(totalQuantity, currentLots, originalLots, initialStopLoss, false, reEntryPrice);
                    reEntriesUsed++;
                    waitingForReEntry = false;
                    events.add(BacktestReplayResponse.Event.builder()
                            .at(optionCandle.dateTime())
                            .type("ENTRY")
                            .reason("RE_ENTRY_AFTER_STOP_LOSS")
                            .priceSource("OPTION")
                            .optionPrice(round(reEntryPrice))
                            .quantity(totalQuantity)
                            .lots(currentLots)
                            .build());
                }
                continue;
            }

            boolean stopHit = isStopHit(trade, position.stopLoss(), optionCandle, spotCandle, triggerPricePolicy);
            TargetHit targetHit = findTargetHit(trade, position, targets, optionCandle, spotCandle, tslEnabled,
                    triggerPricePolicy);

            if (stopHit && targetHit.hit() && !"OPTIMISTIC".equals(sameCandlePolicy)) {
                Exit exit = exitAll(position, optionCandle, position.stopLoss(), executionPricePolicy,
                        position.stopWasTrailed() ? "TRAILING_SL_HIT" : "STOP_LOSS_HIT",
                        position.entryPrice());
                pnl += exit.pnl();
                lastExitAt = optionCandle.dateTime();
                lastExitPrice = exit.price();
                lastExitReason = exit.reason();
                exitCount++;
                events.add(exitEvent(optionCandle.dateTime(), exit, position.stopLoss(), position.quantity(), position.currentLots()));
                position.quantity(0L);
                if (canWaitForReEntry(reEntryOnStopLoss, reEntriesUsed, maxReEntries, optionCandle, squareOffTime, exit.reason())) {
                    waitingForReEntry = true;
                    continue;
                }
                break;
            }

            if (targetHit.hit()) {
                if (!tslEnabled || position.currentLots() <= 1) {
                    Exit exit = exitAll(position, optionCandle, targetHit.level(), executionPricePolicy, "TARGET_HIT", position.entryPrice());
                    pnl += exit.pnl();
                    lastExitAt = optionCandle.dateTime();
                    lastExitPrice = exit.price();
                    lastExitReason = exit.reason();
                    exitCount++;
                    events.add(exitEvent(optionCandle.dateTime(), exit, targetHit.level(), position.quantity(), position.currentLots()));
                    position.quantity(0L);
                    break;
                }

                BookingStep step = resolveNextBookingStep(position.originalLots(), position.currentLots());
                if (step == null) {
                    warnings.add("Unable to resolve TSL booking step; exiting full quantity at target.");
                    Exit exit = exitAll(position, optionCandle, targetHit.level(), executionPricePolicy, "TARGET_HIT", position.entryPrice());
                    pnl += exit.pnl();
                    lastExitAt = optionCandle.dateTime();
                    lastExitPrice = exit.price();
                    lastExitReason = exit.reason();
                    exitCount++;
                    events.add(exitEvent(optionCandle.dateTime(), exit, targetHit.level(), position.quantity(), position.currentLots()));
                    position.quantity(0L);
                    break;
                }

                int lotsToBook = Math.min(step.lotsToBook(), position.currentLots());
                if (lotsToBook >= position.currentLots()) {
                    Exit exit = exitAll(position, optionCandle, targetHit.level(), executionPricePolicy, "TARGET_HIT_FULL", position.entryPrice());
                    pnl += exit.pnl();
                    lastExitAt = optionCandle.dateTime();
                    lastExitPrice = exit.price();
                    lastExitReason = exit.reason();
                    exitCount++;
                    events.add(exitEvent(optionCandle.dateTime(), exit, targetHit.level(), position.quantity(), position.currentLots()));
                    position.quantity(0L);
                    break;
                }

                long quantityToBook = Math.min(position.quantity(), (long) lotsToBook * lotSize);
                Exit partial = exitPartial(quantityToBook, optionCandle, targetHit.level(), executionPricePolicy,
                        "TARGET_HIT_PARTIAL", position.entryPrice());
                pnl += partial.pnl();
                lastExitAt = optionCandle.dateTime();
                lastExitPrice = partial.price();
                lastExitReason = partial.reason();
                exitCount++;
                events.add(exitEvent(optionCandle.dateTime(), partial, targetHit.level(), quantityToBook, lotsToBook));

                position.quantity(position.quantity() - quantityToBook);
                position.currentLots(position.currentLots() - lotsToBook);
                position.stopLoss(nextTslStop(position.stopLoss(), targetHit, position.entryPrice(), trailingSl, partial.price()));
                position.stopWasTrailed(true);
                events.add(BacktestReplayResponse.Event.builder()
                        .at(optionCandle.dateTime())
                        .type("STOP_LOSS_MOVED")
                        .reason("LIVE_COMPAT_TSL")
                        .priceSource(position.stopLoss() != null ? position.stopLoss().source().name() : null)
                        .stopLoss(levelValue(position.stopLoss()))
                        .quantity(position.quantity())
                        .lots(position.currentLots())
                        .build());
                continue;
            }

            if (stopHit) {
                Exit exit = exitAll(position, optionCandle, position.stopLoss(), executionPricePolicy,
                        position.stopWasTrailed() ? "TRAILING_SL_HIT" : "STOP_LOSS_HIT",
                        position.entryPrice());
                pnl += exit.pnl();
                lastExitAt = optionCandle.dateTime();
                lastExitPrice = exit.price();
                lastExitReason = exit.reason();
                exitCount++;
                events.add(exitEvent(optionCandle.dateTime(), exit, position.stopLoss(), position.quantity(), position.currentLots()));
                position.quantity(0L);
                if (canWaitForReEntry(reEntryOnStopLoss, reEntriesUsed, maxReEntries, optionCandle, squareOffTime, exit.reason())) {
                    waitingForReEntry = true;
                    continue;
                }
                break;
            }
        }

        if (position.quantity() > 0L) {
            Candle squareOffCandle = lastSeen != null ? lastSeen : lastCandleAtOrBefore(optionHistory, tradeDate, squareOffTime);
            if (squareOffCandle == null) {
                throw new IllegalArgumentException("No candle available to square off remaining quantity.");
            }
            Exit exit = exitAll(position, squareOffCandle, null, "CANDLE_CLOSE", "SQUARE_OFF", position.entryPrice());
            pnl += exit.pnl();
            lastExitAt = squareOffCandle.dateTime();
            lastExitPrice = exit.price();
            lastExitReason = exit.reason();
            exitCount++;
            events.add(exitEvent(squareOffCandle.dateTime(), exit, null, position.quantity(), position.currentLots()));
            position.quantity(0L);
        }

        BacktestReplayResponse.Result result = BacktestReplayResponse.Result.builder()
                .entryAt(entryAt)
                .entryPrice(round(optionEntryPrice))
                .exitAt(lastExitAt)
                .exitPrice(roundNullable(lastExitPrice))
                .exitReason(lastExitReason)
                .quantity(totalQuantity)
                .remainingQuantity(position.quantity())
                .pnl(round(pnl))
                .exitCount(exitCount)
                .build();
        return new Simulation(result, events, warnings);
    }

    private Candle matchingSpotCandle(List<Candle> spotHistory,
                                      Candle optionCandle,
                                      LocalDateTime entryAt,
                                      int intervalMinutes) {
        if (spotHistory == null || spotHistory.isEmpty() || optionCandle == null) {
            return null;
        }
        LocalDateTime optionTime = optionCandle.dateTime();
        long toleranceSeconds = Math.max(60L, intervalMinutes * 60L);
        return spotHistory.stream()
                .filter(c -> c.dateTime().toLocalDate().equals(optionTime.toLocalDate()))
                .filter(c -> entryAt == null || !c.dateTime().isBefore(entryAt))
                .filter(c -> Math.abs(Duration.between(c.dateTime(), optionTime).getSeconds()) <= toleranceSeconds)
                .min(Comparator.comparingLong(c -> Math.abs(Duration.between(c.dateTime(), optionTime).getSeconds())))
                .orElse(null);
    }

    private boolean requiresSpotCandles(LevelState... levels) {
        if (levels == null) {
            return false;
        }
        for (LevelState level : levels) {
            if (level != null && level.source() == PriceSource.SPOT) {
                return true;
            }
        }
        return false;
    }

    private List<LevelState> nullableTargets(LevelState target1, LevelState target2, LevelState target3) {
        List<LevelState> targets = new ArrayList<>();
        targets.add(target1);
        targets.add(target2);
        targets.add(target3);
        return targets;
    }

    private LevelState nextTslStop(LevelState currentStop,
                                   TargetHit targetHit,
                                   double optionEntryPrice,
                                   Double trailingSl,
                                   double optionExitPrice) {
        Double newStop = null;
        if (targetHit.targetNumber() == 1) {
            newStop = optionEntryPrice;
        } else if (targetHit.targetNumber() == 2) {
            if (trailingSl != null && trailingSl > 0d) {
                newStop = trailingSl;
            } else {
                newStop = optionExitPrice;
            }
        } else if (trailingSl != null && trailingSl > 0d) {
            newStop = trailingSl;
        }
        if (newStop == null || newStop <= 0d) {
            return currentStop;
        }
        return new LevelState(round(newStop), PriceSource.OPTION);
    }

    private TargetHit findTargetHit(TriggeredTradeSetupEntity trade,
                                    Position position,
                                    List<LevelState> targets,
                                    Candle optionCandle,
                                    Candle spotCandle,
                                    boolean tslEnabled,
                                    String triggerPricePolicy) {
        if (targets == null || targets.isEmpty()) {
            return TargetHit.none();
        }
        if (tslEnabled) {
            BookingStep step = resolveNextBookingStep(position.originalLots(), position.currentLots());
            if (step == null) {
                return TargetHit.none();
            }
            LevelState level = targetAt(targets, step.targetNumber());
            return isTargetHit(trade, level, optionCandle, spotCandle, triggerPricePolicy)
                    ? new TargetHit(true, step.targetNumber(), level)
                    : TargetHit.none();
        }
        for (int i = 0; i < targets.size(); i++) {
            LevelState target = targets.get(i);
            if (isTargetHit(trade, target, optionCandle, spotCandle, triggerPricePolicy)) {
                return new TargetHit(true, i + 1, target);
            }
        }
        return TargetHit.none();
    }

    private boolean isStopHit(TriggeredTradeSetupEntity trade,
                              LevelState stopLoss,
                              Candle optionCandle,
                              Candle spotCandle,
                              String triggerPricePolicy) {
        if (stopLoss == null || stopLoss.price() == null || stopLoss.price() <= 0d) {
            return false;
        }
        Candle reference = referenceCandle(stopLoss.source(), optionCandle, spotCandle);
        if (reference == null) {
            return false;
        }
        boolean peSpot = stopLoss.source() == PriceSource.SPOT && "PE".equalsIgnoreCase(trade.getOptionType());
        if ("CLOSE".equals(triggerPricePolicy)) {
            return peSpot ? reference.close() >= stopLoss.price() : reference.close() <= stopLoss.price();
        }
        return peSpot ? reference.high() >= stopLoss.price() : reference.low() <= stopLoss.price();
    }

    private boolean isTargetHit(TriggeredTradeSetupEntity trade,
                                LevelState target,
                                Candle optionCandle,
                                Candle spotCandle,
                                String triggerPricePolicy) {
        if (target == null || target.price() == null || target.price() <= 0d) {
            return false;
        }
        Candle reference = referenceCandle(target.source(), optionCandle, spotCandle);
        if (reference == null) {
            return false;
        }
        boolean peSpot = target.source() == PriceSource.SPOT && "PE".equalsIgnoreCase(trade.getOptionType());
        if ("CLOSE".equals(triggerPricePolicy)) {
            return peSpot ? reference.close() <= target.price() : reference.close() >= target.price();
        }
        return peSpot ? reference.low() <= target.price() : reference.high() >= target.price();
    }

    private boolean isReEntryTriggered(Candle optionCandle, double originalEntryPrice, String triggerPricePolicy) {
        if (optionCandle == null || originalEntryPrice <= 0d) {
            return false;
        }
        if ("CLOSE".equals(triggerPricePolicy)) {
            return optionCandle.close() >= originalEntryPrice;
        }
        return optionCandle.high() >= originalEntryPrice;
    }

    private boolean canWaitForReEntry(boolean reEntryOnStopLoss,
                                      int reEntriesUsed,
                                      int maxReEntries,
                                      Candle optionCandle,
                                      LocalTime squareOffTime,
                                      String exitReason) {
        return reEntryOnStopLoss
                && reEntriesUsed < maxReEntries
                && optionCandle != null
                && optionCandle.dateTime().toLocalTime().isBefore(squareOffTime)
                && "STOP_LOSS_HIT".equals(exitReason);
    }

    private Candle referenceCandle(PriceSource source, Candle optionCandle, Candle spotCandle) {
        return source == PriceSource.SPOT ? spotCandle : optionCandle;
    }

    private Exit exitAll(Position position,
                         Candle optionCandle,
                         LevelState triggerLevel,
                         String executionPricePolicy,
                         String reason,
                         double optionEntryPrice) {
        return exitPartial(position.quantity(), optionCandle, triggerLevel, executionPricePolicy, reason, optionEntryPrice);
    }

    private Exit exitPartial(long quantity,
                             Candle optionCandle,
                             LevelState triggerLevel,
                             String executionPricePolicy,
                             String reason,
                             double optionEntryPrice) {
        double exitPrice = resolveExitPrice(optionCandle, triggerLevel, executionPricePolicy);
        double pnl = (exitPrice - optionEntryPrice) * quantity;
        return new Exit(round(exitPrice), round(pnl), reason);
    }

    private double resolveExitPrice(Candle optionCandle, LevelState triggerLevel, String executionPricePolicy) {
        if ("TRIGGER_LEVEL".equals(executionPricePolicy)
                && triggerLevel != null
                && triggerLevel.source() == PriceSource.OPTION
                && triggerLevel.price() != null
                && triggerLevel.price() > 0d) {
            return triggerLevel.price();
        }
        return optionCandle.close();
    }

    private BacktestReplayResponse.Event exitEvent(LocalDateTime at,
                                                   Exit exit,
                                                   LevelState triggerLevel,
                                                   long quantity,
                                                   int lots) {
        return BacktestReplayResponse.Event.builder()
                .at(at)
                .type("EXIT")
                .reason(exit.reason())
                .priceSource(triggerLevel != null ? triggerLevel.source().name() : "OPTION")
                .referencePrice(levelValue(triggerLevel))
                .optionPrice(round(exit.price()))
                .stopLoss(triggerLevel != null && ("STOP_LOSS_HIT".equals(exit.reason()) || "TRAILING_SL_HIT".equals(exit.reason()))
                        ? levelValue(triggerLevel)
                        : null)
                .target(triggerLevel != null && exit.reason() != null && exit.reason().startsWith("TARGET")
                        ? levelValue(triggerLevel)
                        : null)
                .quantity(quantity)
                .lots(lots)
                .pnl(round(exit.pnl()))
                .build();
    }

    private LevelState resolveLevel(BacktestReplayRequest.LevelRule rule,
                                    Double originalValue,
                                    PriceSource originalSource,
                                    LevelPurpose purpose,
                                    LevelResolutionContext context) {
        String type = rule != null ? normalizeEnum(rule.getType(), "ORIGINAL") : "ORIGINAL";
        if ("NONE".equals(type)) {
            return null;
        }
        PriceSource source = resolvePriceSource(rule, originalSource);
        if ("ORIGINAL".equals(type)) {
            return originalValue != null && originalValue > 0d ? new LevelState(round(originalValue), source) : null;
        }
        if ("FIXED".equals(type)) {
            Double price = rule.getPrice() != null ? rule.getPrice() : rule.getValue();
            return price != null && price > 0d ? new LevelState(round(price), source) : null;
        }

        Double base = source == PriceSource.SPOT ? context.spotEntryPrice() : context.optionEntryPrice();
        if (base == null || base <= 0d) {
            throw new IllegalArgumentException("Unable to resolve " + purpose.name().toLowerCase(Locale.ROOT)
                    + " because " + source.name().toLowerCase(Locale.ROOT) + " entry price is unavailable.");
        }

        double distance;
        if ("POINTS_FROM_ENTRY".equals(type)) {
            Double points = firstNonNull(rule.getPoints(), rule.getValue());
            if (points == null || points < 0d) {
                throw new IllegalArgumentException("POINTS_FROM_ENTRY requires points or value.");
            }
            distance = points;
        } else if ("PERCENT_FROM_ENTRY".equals(type)) {
            Double percent = firstNonNull(rule.getPercent(), rule.getValue());
            if (percent == null || percent < 0d) {
                throw new IllegalArgumentException("PERCENT_FROM_ENTRY requires percent or value.");
            }
            distance = base * (percent / 100d);
        } else if ("ATR_MULTIPLE".equals(type)) {
            int period = rule.getPeriod() != null && rule.getPeriod() > 0 ? rule.getPeriod() : DEFAULT_ATR_PERIOD;
            double multiplier = rule.getMultiplier() != null && rule.getMultiplier() > 0d ? rule.getMultiplier() : 1d;
            double atr = computeAtr(source == PriceSource.SPOT ? context.spotHistory() : context.optionHistory(), context.entryAt(), period);
            distance = atr * multiplier;
            context.warnings().add(purpose.name() + " ATR resolved using source=" + source + ", period=" + period
                    + ", multiplier=" + multiplier + ", atr=" + round(atr));
        } else {
            throw new IllegalArgumentException("Unsupported level rule type: " + type);
        }

        double resolved;
        boolean peSpot = source == PriceSource.SPOT && "PE".equalsIgnoreCase(context.trade().getOptionType());
        if (purpose == LevelPurpose.STOP_LOSS) {
            resolved = peSpot ? base + distance : base - distance;
        } else {
            resolved = peSpot ? base - distance : base + distance;
        }
        return resolved > 0d ? new LevelState(round(resolved), source) : null;
    }

    private double computeAtr(List<Candle> history, LocalDateTime entryAt, int period) {
        List<Candle> eligible = history.stream()
                .filter(c -> c.dateTime().isBefore(entryAt))
                .sorted(Comparator.comparing(Candle::dateTime))
                .toList();
        if (eligible.size() < period + 1) {
            throw new IllegalArgumentException("Not enough candles to compute ATR(" + period + "). Required "
                    + (period + 1) + ", found " + eligible.size());
        }
        List<Candle> tail = eligible.subList(eligible.size() - period - 1, eligible.size());
        double sum = 0d;
        for (int i = 1; i < tail.size(); i++) {
            Candle current = tail.get(i);
            Candle previous = tail.get(i - 1);
            double highLow = current.high() - current.low();
            double highPreviousClose = Math.abs(current.high() - previous.close());
            double lowPreviousClose = Math.abs(current.low() - previous.close());
            sum += Math.max(highLow, Math.max(highPreviousClose, lowPreviousClose));
        }
        return sum / period;
    }

    private BacktestReplayRequest.LevelRule originalStopLossRule(BacktestReplayRequest.Overrides overrides) {
        return overrides != null ? overrides.getStopLoss() : null;
    }

    private PriceSource resolvePriceSource(BacktestReplayRequest.LevelRule rule, PriceSource originalSource) {
        if (rule == null || !StringUtils.hasText(rule.getPriceSource())) {
            return originalSource != null ? originalSource : PriceSource.OPTION;
        }
        String value = rule.getPriceSource().trim().toUpperCase(Locale.ROOT);
        if ("SPOT".equals(value)) {
            return PriceSource.SPOT;
        }
        if ("OPTION".equals(value)) {
            return PriceSource.OPTION;
        }
        if ("ORIGINAL".equals(value)) {
            return originalSource != null ? originalSource : PriceSource.OPTION;
        }
        throw new IllegalArgumentException("Unsupported priceSource: " + rule.getPriceSource());
    }

    private boolean resolveTslEnabled(TriggeredTradeSetupEntity trade, BacktestReplayRequest.Overrides overrides) {
        if (overrides != null && overrides.getTrailingSl() != null && overrides.getTrailingSl().getEnabled() != null) {
            return overrides.getTrailingSl().getEnabled();
        }
        return Boolean.TRUE.equals(trade.getTslEnabled());
    }

    private Double resolveTrailingSl(TriggeredTradeSetupEntity trade, BacktestReplayRequest.Overrides overrides) {
        if (overrides != null && overrides.getTrailingSl() != null && overrides.getTrailingSl().getPrice() != null) {
            return overrides.getTrailingSl().getPrice();
        }
        return trade.getTrailingSl();
    }

    private List<Candle> loadCandles(Integer scripCode, String interval, LocalDate from, LocalDate to) {
        if (scripCode == null) {
            return List.of();
        }
        List<Candle> mStockCandles = loadMStockCandles(scripCode, interval, from, to);
        if (!mStockCandles.isEmpty()) {
            return mStockCandles;
        }
        return loadSharekhanCandles(scripCode, interval, from, to);
    }

    private List<Candle> loadSharekhanCandles(Integer scripCode, String interval, LocalDate from, LocalDate to) {
        return historicalService.getHistoricalCandles(scripCode, interval, from, to).stream()
                .filter(Objects::nonNull)
                .filter(c -> c.date() != null && c.time() != null)
                .filter(c -> Double.isFinite(c.open()) && Double.isFinite(c.high()) && Double.isFinite(c.low()) && Double.isFinite(c.close()))
                .map(c -> new Candle(LocalDateTime.of(c.date(), c.time()), c.open(), c.high(), c.low(), c.close()))
                .sorted(Comparator.comparing(Candle::dateTime))
                .toList();
    }

    private List<Candle> loadMStockCandles(Integer scripCode, String interval, LocalDate from, LocalDate to) {
        try {
            MStockHistoricalService.HistoricalResponse response = mStockHistoricalService.getHistoricalCandles(
                    scripCode,
                    null,
                    null,
                    null,
                    null,
                    null,
                    interval,
                    from.toString(),
                    to.toString());
            if (response == null || response.candles() == null) {
                return List.of();
            }
            return response.candles().stream()
                    .filter(Objects::nonNull)
                    .filter(c -> c.date() != null && c.time() != null)
                    .filter(c -> Double.isFinite(c.open()) && Double.isFinite(c.high()) && Double.isFinite(c.low()) && Double.isFinite(c.close()))
                    .map(c -> new Candle(LocalDateTime.of(c.date(), c.time()), c.open(), c.high(), c.low(), c.close()))
                    .sorted(Comparator.comparing(Candle::dateTime))
                    .toList();
        } catch (Exception ex) {
            log.warn("MStock historical primary load failed for scripCode={} interval={} from={} to={}: {}",
                    scripCode, interval, from, to, ex.getMessage());
            log.debug("MStock historical primary load error", ex);
            return List.of();
        }
    }

    private LocalDateTime resolveEntryAt(TriggeredTradeSetupEntity trade) {
        if (trade.getEntryAt() != null) {
            return trade.getEntryAt();
        }
        if (trade.getTriggeredAt() != null) {
            return trade.getTriggeredAt();
        }
        throw new IllegalArgumentException("Trade setup has neither entryAt nor triggeredAt.");
    }

    private Double resolveOptionEntryPrice(TriggeredTradeSetupEntity trade, List<Candle> optionHistory, LocalDateTime entryAt) {
        if (trade.getActualEntryPrice() != null && trade.getActualEntryPrice() > 0d) {
            return trade.getActualEntryPrice();
        }
        if (!usesSpotForEntry(trade) && trade.getEntryPrice() != null && trade.getEntryPrice() > 0d) {
            return trade.getEntryPrice();
        }
        return firstCandleAtOrAfter(optionHistory, entryAt).map(Candle::open).orElse(null);
    }

    private Double resolveSpotEntryPrice(TriggeredTradeSetupEntity trade, List<Candle> spotHistory, LocalDateTime entryAt) {
        if (usesSpotForEntry(trade) && trade.getEntryPrice() != null && trade.getEntryPrice() > 0d) {
            return trade.getEntryPrice();
        }
        return firstCandleAtOrAfter(spotHistory, entryAt).map(Candle::open).orElse(null);
    }

    private java.util.Optional<Candle> firstCandleAtOrAfter(List<Candle> candles, LocalDateTime at) {
        return candles.stream()
                .filter(c -> !c.dateTime().isBefore(at))
                .findFirst();
    }

    private Candle lastCandleAtOrBefore(List<Candle> candles, LocalDate date, LocalTime time) {
        return candles.stream()
                .filter(c -> date.equals(c.dateTime().toLocalDate()))
                .filter(c -> !c.dateTime().toLocalTime().isAfter(time))
                .max(Comparator.comparing(Candle::dateTime))
                .orElse(null);
    }

    private boolean usesSpotForEntry(TriggeredTradeSetupEntity trade) {
        if (Boolean.TRUE.equals(trade.getUseSpotForEntry())) {
            return true;
        }
        return trade.getUseSpotForEntry() == null && Boolean.TRUE.equals(trade.getUseSpotPrice());
    }

    private PriceSource originalStopSource(TriggeredTradeSetupEntity trade) {
        if (Boolean.TRUE.equals(trade.getUseSpotForSl())) {
            return PriceSource.SPOT;
        }
        return trade.getUseSpotForSl() == null && Boolean.TRUE.equals(trade.getUseSpotPrice())
                ? PriceSource.SPOT
                : PriceSource.OPTION;
    }

    private PriceSource originalTargetSource(TriggeredTradeSetupEntity trade) {
        if (Boolean.TRUE.equals(trade.getUseSpotForTarget())) {
            return PriceSource.SPOT;
        }
        return trade.getUseSpotForTarget() == null && Boolean.TRUE.equals(trade.getUseSpotPrice())
                ? PriceSource.SPOT
                : PriceSource.OPTION;
    }

    private long resolveQuantity(TriggeredTradeSetupEntity trade, int lotSize) {
        if (trade.getQuantity() != null && trade.getQuantity() > 0L) {
            return trade.getQuantity();
        }
        int lots = trade.getLots() != null && trade.getLots() > 0 ? trade.getLots() : 1;
        return (long) lots * Math.max(lotSize, 1);
    }

    private int resolveLots(TriggeredTradeSetupEntity trade, long quantity, int lotSize) {
        if (lotSize > 0 && quantity > 0) {
            return Math.max(1, (int) Math.ceil((double) quantity / lotSize));
        }
        return trade.getLots() != null && trade.getLots() > 0 ? trade.getLots() : 1;
    }

    private BookingStep resolveNextBookingStep(int totalLots, int currentLots) {
        if (currentLots <= 0) {
            return null;
        }
        if (totalLots <= 1) {
            return new BookingStep(1, currentLots);
        }
        if (totalLots == 2) {
            return currentLots >= 2 ? new BookingStep(1, currentLots - 1) : new BookingStep(2, currentLots);
        }
        if (totalLots == 3) {
            if (currentLots >= 3) {
                return new BookingStep(1, currentLots - 2);
            }
            return currentLots == 2 ? new BookingStep(2, 1) : new BookingStep(3, currentLots);
        }

        int exitAtT1 = Math.max(1, (int) Math.round(totalLots * 0.4));
        int exitAtT3 = Math.max(1, totalLots - exitAtT1 - exitAtT1);
        int exitAtT2 = Math.max(1, totalLots - exitAtT1 - exitAtT3);
        int bookedLots = Math.max(0, totalLots - currentLots);
        int afterT1Booked = exitAtT1;
        int afterT2Booked = exitAtT1 + exitAtT2;

        if (bookedLots < afterT1Booked) {
            return new BookingStep(1, afterT1Booked - bookedLots);
        }
        if (bookedLots < afterT2Booked) {
            return new BookingStep(2, afterT2Booked - bookedLots);
        }
        return new BookingStep(3, currentLots);
    }

    private LevelState targetAt(List<LevelState> targets, int targetNumber) {
        int index = targetNumber - 1;
        if (index < 0 || index >= targets.size()) {
            return null;
        }
        return targets.get(index);
    }

    private BacktestReplayResponse.TradeSnapshot snapshot(TriggeredTradeSetupEntity trade) {
        return BacktestReplayResponse.TradeSnapshot.builder()
                .symbol(trade.getSymbol())
                .scripCode(trade.getScripCode())
                .spotScripCode(trade.getSpotScripCode())
                .exchange(trade.getExchange())
                .strikePrice(trade.getStrikePrice())
                .optionType(trade.getOptionType())
                .expiry(trade.getExpiry())
                .quantity(trade.getQuantity())
                .lots(trade.getLots())
                .originalLots(trade.getOriginalLots())
                .entryPrice(trade.getEntryPrice())
                .actualEntryPrice(trade.getActualEntryPrice())
                .stopLoss(trade.getStopLoss())
                .target1(trade.getTarget1())
                .target2(trade.getTarget2())
                .target3(trade.getTarget3())
                .trailingSl(trade.getTrailingSl())
                .tslEnabled(trade.getTslEnabled())
                .useSpotForEntry(trade.getUseSpotForEntry())
                .useSpotForSl(trade.getUseSpotForSl())
                .useSpotForTarget(trade.getUseSpotForTarget())
                .triggeredAt(trade.getTriggeredAt())
                .entryAt(trade.getEntryAt())
                .exitedAt(trade.getExitedAt())
                .exitPrice(trade.getExitPrice())
                .pnl(trade.getPnl())
                .exitReason(trade.getExitReason())
                .status(trade.getStatus() != null ? trade.getStatus().name() : null)
                .build();
    }

    private BacktestReplayResponse.Result actualResult(TriggeredTradeSetupEntity trade) {
        Double entry = trade.getActualEntryPrice() != null && trade.getActualEntryPrice() > 0d
                ? trade.getActualEntryPrice()
                : trade.getEntryPrice();
        return BacktestReplayResponse.Result.builder()
                .entryAt(trade.getEntryAt())
                .entryPrice(roundNullable(entry))
                .exitAt(trade.getExitedAt())
                .exitPrice(roundNullable(trade.getExitPrice()))
                .exitReason(trade.getExitReason())
                .quantity(trade.getQuantity())
                .remainingQuantity(null)
                .pnl(roundNullable(trade.getPnl()))
                .exitCount(trade.getExitedAt() != null ? 1 : 0)
                .build();
    }

    private String firstSource(LevelState... levels) {
        for (LevelState level : levels) {
            if (level != null && level.source() != null) {
                return level.source().name();
            }
        }
        return null;
    }

    private Double levelValue(LevelState level) {
        return level != null ? roundNullable(level.price()) : null;
    }

    private double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private Double roundNullable(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return value;
        }
        return round(value);
    }

    private Double firstNonNull(Double first, Double second) {
        return first != null ? first : second;
    }

    private String textOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String normalizeEnum(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : fallback;
    }

    private String normalizeTriggerPricePolicy(String value) {
        String normalized = normalizeEnum(value, "LTP");
        if ("LTP".equals(normalized)
                || "LAST_TRADED_PRICE".equals(normalized)
                || "HIGH_LOW".equals(normalized)
                || "INTRABAR".equals(normalized)) {
            return "LTP";
        }
        if ("CLOSE".equals(normalized) || "CANDLE_CLOSE".equals(normalized)) {
            return "CLOSE";
        }
        throw new IllegalArgumentException("Unsupported triggerPricePolicy: " + value + ". Use LTP or CLOSE.");
    }

    private LocalTime parseTime(String raw, LocalTime fallback) {
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        return LocalTime.parse(raw.trim());
    }

    private int intervalMinutes(String interval) {
        String value = textOrDefault(interval, DEFAULT_INTERVAL).toLowerCase(Locale.ROOT);
        if (value.endsWith("minute")) {
            return Integer.parseInt(value.substring(0, value.indexOf("minute")));
        }
        if (value.endsWith("min")) {
            return Integer.parseInt(value.substring(0, value.indexOf("min")));
        }
        return 5;
    }

    private enum PriceSource {
        SPOT,
        OPTION
    }

    private enum LevelPurpose {
        STOP_LOSS,
        TARGET
    }

    private record Candle(LocalDateTime dateTime, double open, double high, double low, double close) {
    }

    private record LevelState(Double price, PriceSource source) {
    }

    private record LevelResolutionContext(TriggeredTradeSetupEntity trade,
                                          List<Candle> optionHistory,
                                          List<Candle> spotHistory,
                                          LocalDateTime entryAt,
                                          Double optionEntryPrice,
                                          Double spotEntryPrice,
                                          List<String> warnings) {
    }

    private record TargetHit(boolean hit, int targetNumber, LevelState level) {
        private static TargetHit none() {
            return new TargetHit(false, 0, null);
        }
    }

    private record BookingStep(int targetNumber, int lotsToBook) {
    }

    private record Exit(double price, double pnl, String reason) {
    }

    private record Simulation(BacktestReplayResponse.Result result,
                              List<BacktestReplayResponse.Event> events,
                              List<String> warnings) {
    }

    private static class Position {
        private Long quantity;
        private Integer currentLots;
        private final Integer originalLots;
        private LevelState stopLoss;
        private boolean stopWasTrailed;
        private final Double entryPrice;

        Position(Long quantity, Integer currentLots, Integer originalLots, LevelState stopLoss, boolean stopWasTrailed, Double entryPrice) {
            this.quantity = quantity;
            this.currentLots = currentLots;
            this.originalLots = originalLots;
            this.stopLoss = stopLoss;
            this.stopWasTrailed = stopWasTrailed;
            this.entryPrice = entryPrice;
        }

        Long quantity() {
            return quantity;
        }

        void quantity(Long quantity) {
            this.quantity = quantity;
        }

        Integer currentLots() {
            return currentLots;
        }

        void currentLots(Integer currentLots) {
            this.currentLots = currentLots;
        }

        Integer originalLots() {
            return originalLots;
        }

        LevelState stopLoss() {
            return stopLoss;
        }

        void stopLoss(LevelState stopLoss) {
            this.stopLoss = stopLoss;
        }

        boolean stopWasTrailed() {
            return stopWasTrailed;
        }

        void stopWasTrailed(boolean stopWasTrailed) {
            this.stopWasTrailed = stopWasTrailed;
        }

        Double entryPrice() {
            return entryPrice;
        }
    }
}
