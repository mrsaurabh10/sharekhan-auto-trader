package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.dto.TradeAnalyticsResponse;
import org.com.sharekhan.entity.BacktestReplayResultEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.BacktestReplayResultRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TradeAnalyticsService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final EnumSet<TriggeredTradeStatus> OPEN_STATUSES = EnumSet.of(
            TriggeredTradeStatus.EXECUTED,
            TriggeredTradeStatus.EXIT_ORDER_PLACED,
            TriggeredTradeStatus.TARGET_ORDER_PLACED
    );
    private static final EnumSet<TriggeredTradeStatus> FAILED_STATUSES = EnumSet.of(
            TriggeredTradeStatus.FAILED,
            TriggeredTradeStatus.EXIT_FAILED
    );

    private final TriggeredTradeSetupRepository tradeRepository;
    private final BacktestReplayResultRepository backtestResultRepository;

    public TradeAnalyticsResponse getTradeAnalytics(Long userId,
                                                    LocalDate from,
                                                    LocalDate to,
                                                    String symbol,
                                                    String source,
                                                    Long brokerCredentialsId,
                                                    Boolean intraday) {
        return getTradeAnalytics(userId, from, to, symbol, source, brokerCredentialsId, intraday, null);
    }

    public TradeAnalyticsResponse getTradeAnalytics(Long userId,
                                                    LocalDate from,
                                                    LocalDate to,
                                                    String symbol,
                                                    String source,
                                                    Long brokerCredentialsId,
                                                    Boolean intraday,
                                                    String scope) {
        return buildTradeAnalytics(userId, from, to, symbol, source, null, brokerCredentialsId, intraday, scope);
    }

    public TradeAnalyticsResponse getTradeAnalyticsForSources(Long userId,
                                                              LocalDate from,
                                                              LocalDate to,
                                                              String symbol,
                                                              List<String> sources,
                                                              Long brokerCredentialsId,
                                                              Boolean intraday,
                                                              String scope) {
        return buildTradeAnalytics(userId, from, to, symbol, null, sources, brokerCredentialsId, intraday, scope);
    }

    private TradeAnalyticsResponse buildTradeAnalytics(Long userId,
                                                       LocalDate from,
                                                       LocalDate to,
                                                       String symbol,
                                                       String source,
                                                       List<String> sources,
                                                       Long brokerCredentialsId,
                                                       Boolean intraday,
                                                       String scope) {
        LocalDate resolvedTo = to != null ? to : LocalDate.now(MARKET_ZONE);
        LocalDate resolvedFrom = from != null ? from : resolvedTo.minusDays(30);
        if (resolvedFrom.isAfter(resolvedTo)) {
            LocalDate previousFrom = resolvedFrom;
            resolvedFrom = resolvedTo;
            resolvedTo = previousFrom;
        }

        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : symbol.trim();
        String normalizedSource = source == null || source.isBlank() ? null : source.trim();
        List<String> normalizedSources = normalizeSources(sources);
        String sourceFilter = normalizedSources.size() == 1 ? normalizedSources.get(0) : normalizedSource;
        List<TriggeredTradeSetupEntity> candidateTrades = findCandidateTrades(
                userId,
                normalizedSymbol,
                sourceFilter,
                brokerCredentialsId,
                intraday,
                scope
        );
        if (normalizedSources.size() > 1) {
            candidateTrades = candidateTrades.stream()
                    .filter(trade -> matchesAnySource(trade.getSource(), normalizedSources))
                    .toList();
        }

        LocalDateTime start = resolvedFrom.atStartOfDay();
        LocalDateTime end = resolvedTo.atTime(LocalTime.MAX);

        List<TriggeredTradeSetupEntity> realizedTrades = candidateTrades.stream()
                .filter(this::isRealizedTrade)
                .filter(trade -> isWithin(trade.getExitedAt(), start, end))
                .toList();
        realizedTrades = rootTradesOnly(realizedTrades);

        List<TriggeredTradeSetupEntity> openTrades = candidateTrades.stream()
                .filter(trade -> trade.getStatus() != null && OPEN_STATUSES.contains(trade.getStatus()))
                .filter(trade -> isWithin(trade.getTriggeredAt(), start, end))
                .toList();
        openTrades = rootTradesOnly(openTrades);

        int rejectedTrades = (int) candidateTrades.stream()
                .filter(trade -> trade.getStatus() == TriggeredTradeStatus.REJECTED)
                .filter(trade -> isWithin(eventTime(trade), start, end))
                .count();
        int failedTrades = (int) candidateTrades.stream()
                .filter(trade -> trade.getStatus() != null && FAILED_STATUSES.contains(trade.getStatus()))
                .filter(trade -> isWithin(eventTime(trade), start, end))
                .count();

        List<TriggeredTradeSetupEntity> fundedTrades = candidateTrades.stream()
                .filter(this::usesFunds)
                .filter(trade -> overlapsFundUseWindow(trade, start, end))
                .toList();
        fundedTrades = rootTradesOnly(fundedTrades);

        return TradeAnalyticsResponse.builder()
                .filters(TradeAnalyticsResponse.Filters.builder()
                        .userId(userId)
                        .from(resolvedFrom)
                        .to(resolvedTo)
                        .symbol(normalizedSymbol)
                        .source(!normalizedSources.isEmpty() ? String.join(",", normalizedSources) : normalizedSource)
                        .sources(normalizedSources.isEmpty() ? null : normalizedSources)
                        .scope(normalizedScope(scope))
                        .brokerCredentialsId(brokerCredentialsId)
                        .intraday(intraday)
                        .build())
                .summary(buildSummary(realizedTrades, openTrades, fundedTrades, start, end, rejectedTrades, failedTrades))
                .bySymbol(buildBySymbol(realizedTrades))
                .byDay(buildByDay(realizedTrades))
                .backtest(buildBacktestAnalytics(findBacktestResults(
                        resolvedFrom,
                        resolvedTo,
                        userId,
                        normalizedSymbol,
                        normalizedSources.isEmpty() ? normalizedSource : null,
                        normalizedSources,
                        brokerCredentialsId,
                        intraday,
                        scope)))
                .recentClosedTrades(buildRecentClosedTrades(realizedTrades))
                .build();
    }

    private List<String> normalizeSources(List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        return sources.stream()
                .filter(Objects::nonNull)
                .flatMap(source -> Arrays.stream(source.split(",")))
                .map(String::trim)
                .filter(source -> !source.isBlank())
                .distinct()
                .toList();
    }

    private boolean matchesAnySource(String source, List<String> selectedSources) {
        if (source == null) {
            return false;
        }
        String normalized = source.trim();
        return selectedSources.stream().anyMatch(selected -> selected.equalsIgnoreCase(normalized));
    }

    public List<String> getAvailableSources(Long userId, String scope) {
        List<String> sources;
        if (isSimulatorScope(scope)) {
            sources = tradeRepository.findAnalyticsSourcesBySimulator(Broker.SIMULATOR.getDisplayName());
        } else if (isOwnScope(scope) && userId != null) {
            sources = tradeRepository.findAnalyticsSourcesByUserExcludingSimulator(userId, Broker.SIMULATOR.getDisplayName());
        } else if (isAllScope(scope) && userId != null) {
            sources = tradeRepository.findAnalyticsSourcesByUserOrSimulator(userId, Broker.SIMULATOR.getDisplayName());
        } else if (userId != null) {
            sources = tradeRepository.findAnalyticsSourcesByUserExcludingSimulator(userId, Broker.SIMULATOR.getDisplayName());
        } else {
            sources = tradeRepository.findAnalyticsSources(userId);
        }
        return sources.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(source -> !source.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private List<TriggeredTradeSetupEntity> findCandidateTrades(Long userId,
                                                                String symbol,
                                                                String source,
                                                                Long brokerCredentialsId,
                                                                Boolean intraday,
                                                                String scope) {
        if (isSimulatorScope(scope)) {
            return tradeRepository.findForAnalyticsBySimulator(
                    Broker.SIMULATOR.getDisplayName(),
                    symbol,
                    source,
                    brokerCredentialsId,
                    intraday
            );
        }
        if (isOwnScope(scope) && userId != null) {
            return tradeRepository.findForAnalyticsByUserExcludingSimulator(
                    userId,
                    Broker.SIMULATOR.getDisplayName(),
                    symbol,
                    source,
                    brokerCredentialsId,
                    intraday
            );
        }
        if (isAllScope(scope) && userId != null) {
            return tradeRepository.findForAnalyticsByUserOrSimulator(
                    userId,
                    Broker.SIMULATOR.getDisplayName(),
                    symbol,
                    source,
                    brokerCredentialsId,
                    intraday
            );
        }
        if (userId != null) {
            return tradeRepository.findForAnalyticsByUserExcludingSimulator(
                    userId,
                    Broker.SIMULATOR.getDisplayName(),
                    symbol,
                    source,
                    brokerCredentialsId,
                    intraday
            );
        }
        return tradeRepository.findForAnalytics(userId, symbol, source, brokerCredentialsId, intraday);
    }

    private List<BacktestReplayResultEntity> findBacktestResults(LocalDate from,
                                                                 LocalDate to,
                                                                 Long userId,
                                                                 String symbol,
                                                                 String source,
                                                                 List<String> sources,
                                                                 Long brokerCredentialsId,
                                                                 Boolean intraday,
                                                                 String scope) {
        List<BacktestReplayResultEntity> results = backtestResultRepository.findByTradeDateBetween(from, to);
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        List<String> selectedSources = normalizeSources(sources);
        return results.stream()
                .filter(result -> matchesBacktestScope(result, userId, scope))
                .filter(result -> symbol == null || containsIgnoreCase(result.getSymbol(), symbol))
                .filter(result -> brokerCredentialsId == null || brokerCredentialsId.equals(result.getBrokerCredentialsId()))
                .filter(result -> intraday == null || intraday.equals(result.getIntradayOnly()))
                .filter(result -> source == null || containsIgnoreCase(result.getSource(), source))
                .filter(result -> selectedSources.isEmpty() || matchesAnySource(result.getSource(), selectedSources))
                .toList();
    }

    private boolean matchesBacktestScope(BacktestReplayResultEntity result, Long userId, String scope) {
        if (isSimulatorScope(scope)) {
            return Boolean.TRUE.equals(result.getSimulator());
        }
        if (isOwnScope(scope) && userId != null) {
            return userId.equals(result.getAppUserId()) && !Boolean.TRUE.equals(result.getSimulator());
        }
        if (isAllScope(scope) && userId != null) {
            return userId.equals(result.getAppUserId()) || Boolean.TRUE.equals(result.getSimulator());
        }
        if (userId != null) {
            return userId.equals(result.getAppUserId()) && !Boolean.TRUE.equals(result.getSimulator());
        }
        return true;
    }

    private boolean containsIgnoreCase(String value, String filter) {
        return value != null && filter != null && value.toLowerCase().contains(filter.toLowerCase());
    }

    private boolean isOwnScope(String scope) {
        return scope != null && "own".equalsIgnoreCase(scope.trim());
    }

    private boolean isSimulatorScope(String scope) {
        return scope != null && "simulator".equalsIgnoreCase(scope.trim());
    }

    private boolean isAllScope(String scope) {
        return scope != null && "all".equalsIgnoreCase(scope.trim());
    }

    private String normalizedScope(String scope) {
        return scope == null || scope.isBlank() ? null : scope.trim().toLowerCase();
    }

    private TradeAnalyticsResponse.Summary buildSummary(List<TriggeredTradeSetupEntity> realizedTrades,
                                                        List<TriggeredTradeSetupEntity> openTrades,
                                                        List<TriggeredTradeSetupEntity> fundedTrades,
                                                        LocalDateTime start,
                                                        LocalDateTime end,
                                                        int rejectedTrades,
                                                        int failedTrades) {
        double realizedPnl = realizedTrades.stream().mapToDouble(TriggeredTradeSetupEntity::getPnl).sum();
        List<Double> wins = realizedTrades.stream()
                .map(TriggeredTradeSetupEntity::getPnl)
                .filter(pnl -> pnl > 0)
                .toList();
        List<Double> losses = realizedTrades.stream()
                .map(TriggeredTradeSetupEntity::getPnl)
                .filter(pnl -> pnl < 0)
                .toList();
        int breakevenTrades = (int) realizedTrades.stream()
                .map(TriggeredTradeSetupEntity::getPnl)
                .filter(pnl -> pnl == 0)
                .count();
        double grossProfit = wins.stream().mapToDouble(Double::doubleValue).sum();
        double grossLoss = losses.stream().mapToDouble(Double::doubleValue).sum();
        PeakFundUse peakFundUse = peakFundUse(fundedTrades, start, end);

        return TradeAnalyticsResponse.Summary.builder()
                .realizedPnl(round(realizedPnl))
                .totalClosedTrades(realizedTrades.size())
                .winningTrades(wins.size())
                .losingTrades(losses.size())
                .breakevenTrades(breakevenTrades)
                .winRate(percent(wins.size(), realizedTrades.size()))
                .lossRate(percent(losses.size(), realizedTrades.size()))
                .profitFactor(grossLoss < 0 ? round(grossProfit / Math.abs(grossLoss)) : null)
                .averageWin(wins.isEmpty() ? null : round(wins.stream().mapToDouble(Double::doubleValue).average().orElse(0)))
                .averageLoss(losses.isEmpty() ? null : round(losses.stream().mapToDouble(Double::doubleValue).average().orElse(0)))
                .bestTradePnl(realizedTrades.stream().map(TriggeredTradeSetupEntity::getPnl).max(Double::compareTo).map(this::round).orElse(null))
                .worstTradePnl(realizedTrades.stream().map(TriggeredTradeSetupEntity::getPnl).min(Double::compareTo).map(this::round).orElse(null))
                .maxFundUseAtTime(round(peakFundUse.amount()))
                .maxFundUseAt(peakFundUse.at())
                .activeTradesAtMaxFundUse(peakFundUse.activeTrades())
                .openTrades(openTrades.size())
                .openQuantity(openTrades.stream().map(TriggeredTradeSetupEntity::getQuantity).filter(Objects::nonNull).mapToLong(Long::longValue).sum())
                .rejectedTrades(rejectedTrades)
                .failedTrades(failedTrades)
                .build();
    }

    private List<TradeAnalyticsResponse.SymbolAnalytics> buildBySymbol(List<TriggeredTradeSetupEntity> realizedTrades) {
        return realizedTrades.stream()
                .collect(Collectors.groupingBy(trade -> {
                    String symbol = trade.getSymbol();
                    return symbol == null || symbol.isBlank() ? "-" : symbol;
                }))
                .entrySet()
                .stream()
                .map(entry -> {
                    List<TriggeredTradeSetupEntity> trades = entry.getValue();
                    double realizedPnl = trades.stream().mapToDouble(TriggeredTradeSetupEntity::getPnl).sum();
                    long winners = trades.stream().filter(trade -> trade.getPnl() > 0).count();
                    return TradeAnalyticsResponse.SymbolAnalytics.builder()
                            .symbol(entry.getKey())
                            .closedCount(trades.size())
                            .realizedPnl(round(realizedPnl))
                            .winRate(percent(winners, trades.size()))
                            .averagePnl(round(realizedPnl / trades.size()))
                            .build();
                })
                .sorted(Comparator.comparing(TradeAnalyticsResponse.SymbolAnalytics::getRealizedPnl).reversed())
                .toList();
    }

    private PeakFundUse peakFundUse(List<TriggeredTradeSetupEntity> fundedTrades,
                                    LocalDateTime rangeStart,
                                    LocalDateTime rangeEnd) {
        Map<LocalDateTime, FundUseEvent> eventsByTime = fundedTrades.stream()
                .flatMap(trade -> fundUseEvents(trade, rangeStart, rangeEnd).stream())
                .collect(Collectors.toMap(
                        FundUseEvent::at,
                        event -> event,
                        (left, right) -> new FundUseEvent(
                                left.at(),
                                left.deltaAmount() + right.deltaAmount(),
                                left.deltaActiveTrades() + right.deltaActiveTrades()
                        )
                ));
        double activeAmount = 0.0d;
        int activeTrades = 0;
        PeakFundUse peak = new PeakFundUse(null, 0.0d, 0);
        for (FundUseEvent event : eventsByTime.values().stream()
                .sorted(Comparator.comparing(FundUseEvent::at))
                .toList()) {
            activeAmount += event.deltaAmount();
            activeTrades += event.deltaActiveTrades();
            if (activeAmount > peak.amount()) {
                peak = new PeakFundUse(event.at(), activeAmount, activeTrades);
            }
        }
        return peak;
    }

    private List<FundUseEvent> fundUseEvents(TriggeredTradeSetupEntity trade,
                                             LocalDateTime rangeStart,
                                             LocalDateTime rangeEnd) {
        LocalDateTime start = fundUseTime(trade);
        LocalDateTime end = fundReleaseTime(trade);
        LocalDateTime effectiveStart = start.isBefore(rangeStart) ? rangeStart : start;
        double amount = fundUseAmount(trade);
        if (end == null || end.isAfter(rangeEnd)) {
            return List.of(new FundUseEvent(effectiveStart, amount, 1));
        }
        return List.of(
                new FundUseEvent(effectiveStart, amount, 1),
                new FundUseEvent(end, -amount, -1)
        );
    }

    private boolean overlapsFundUseWindow(TriggeredTradeSetupEntity trade,
                                          LocalDateTime rangeStart,
                                          LocalDateTime rangeEnd) {
        LocalDateTime start = fundUseTime(trade);
        LocalDateTime end = fundReleaseTime(trade);
        return start != null
                && !start.isAfter(rangeEnd)
                && (end == null || !end.isBefore(rangeStart));
    }

    private LocalDateTime fundReleaseTime(TriggeredTradeSetupEntity trade) {
        if (trade.getStatus() != TriggeredTradeStatus.EXITED_SUCCESS) {
            return null;
        }
        if (trade.getExitedAt() != null) {
            return trade.getExitedAt();
        }
        // Defensive fallback for historical rows with EXITED_SUCCESS but missing exitedAt.
        return fundUseTime(trade);
    }

    private List<TradeAnalyticsResponse.DailyAnalytics> buildByDay(List<TriggeredTradeSetupEntity> realizedTrades) {
        Map<LocalDate, List<TriggeredTradeSetupEntity>> tradesByDay = realizedTrades.stream()
                .collect(Collectors.groupingBy(trade -> trade.getExitedAt().toLocalDate()));
        double[] cumulative = {0.0d};
        return tradesByDay.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    double realizedPnl = entry.getValue().stream().mapToDouble(TriggeredTradeSetupEntity::getPnl).sum();
                    cumulative[0] += realizedPnl;
                    return TradeAnalyticsResponse.DailyAnalytics.builder()
                            .date(entry.getKey())
                            .closedCount(entry.getValue().size())
                            .realizedPnl(round(realizedPnl))
                            .cumulativeRealizedPnl(round(cumulative[0]))
                            .build();
                })
                .toList();
    }

    private TradeAnalyticsResponse.BacktestAnalytics buildBacktestAnalytics(List<BacktestReplayResultEntity> results) {
        List<BacktestTradePair> pairs = backtestPairs(rootBacktestResultsOnly(results));
        BacktestAggregate summary = aggregateBacktestPairs(pairs);
        return TradeAnalyticsResponse.BacktestAnalytics.builder()
                .summary(backtestSummary(summary))
                .byDay(pairs.stream()
                        .collect(Collectors.groupingBy(BacktestTradePair::tradeDate))
                        .entrySet()
                        .stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> backtestDaily(entry.getKey(), aggregateBacktestPairs(entry.getValue())))
                        .toList())
                .build();
    }

    private List<BacktestTradePair> backtestPairs(List<BacktestReplayResultEntity> results) {
        return results.stream()
                .collect(Collectors.groupingBy(BacktestReplayResultEntity::getTradeSetupId))
                .entrySet()
                .stream()
                .map(entry -> new BacktestTradePair(
                        entry.getKey(),
                        entry.getValue().stream()
                                .map(BacktestReplayResultEntity::getTradeDate)
                                .filter(Objects::nonNull)
                                .findFirst()
                                .orElse(null),
                        latestIntervalResult(entry.getValue(), "1minute", "CLOSE").orElse(null),
                        latestIntervalResult(entry.getValue(), "5minute", "CLOSE").orElse(null),
                        latestIntervalResult(entry.getValue(), "1minute", "CLOSE_REENTRY").orElse(null)))
                .toList();
    }

    private List<TriggeredTradeSetupEntity> rootTradesOnly(List<TriggeredTradeSetupEntity> trades) {
        if (trades == null || trades.isEmpty()) {
            return List.of();
        }
        Map<String, TriggeredTradeSetupEntity> roots = new LinkedHashMap<>();
        trades.stream()
                .sorted(Comparator
                        .comparing((TriggeredTradeSetupEntity trade) -> signalTime(trade), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(TriggeredTradeSetupEntity::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(trade -> roots.putIfAbsent(signalKey(trade), trade));
        return new ArrayList<>(roots.values());
    }

    private List<BacktestReplayResultEntity> rootBacktestResultsOnly(List<BacktestReplayResultEntity> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        Map<String, Long> rootTradeIds = new LinkedHashMap<>();
        results.stream()
                .sorted(Comparator.comparing(BacktestReplayResultEntity::getTradeSetupId, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(result -> rootTradeIds.putIfAbsent(backtestSignalKey(result), result.getTradeSetupId()));
        return results.stream()
                .filter(result -> Objects.equals(result.getTradeSetupId(), rootTradeIds.get(backtestSignalKey(result))))
                .toList();
    }

    private String signalKey(TriggeredTradeSetupEntity trade) {
        return String.join("|",
                textValue(tradeDate(trade)),
                textValue(trade.getSymbol()),
                textValue(trade.getScripCode()),
                textValue(trade.getSpotScripCode()),
                textValue(trade.getExchange()),
                textValue(trade.getOptionType()),
                textValue(trade.getStrikePrice()),
                textValue(trade.getExpiry()),
                textValue(signalTime(trade)));
    }

    private String backtestSignalKey(BacktestReplayResultEntity result) {
        return String.join("|",
                textValue(result.getTradeDate()),
                textValue(result.getSymbol()),
                textValue(result.getScripCode()),
                textValue(result.getOptionType()),
                textValue(result.getStrikePrice()),
                textValue(result.getExpiry()));
    }

    private LocalDate tradeDate(TriggeredTradeSetupEntity trade) {
        LocalDateTime at = signalTime(trade);
        return at != null ? at.toLocalDate() : null;
    }

    private LocalDateTime signalTime(TriggeredTradeSetupEntity trade) {
        if (trade == null) {
            return null;
        }
        return trade.getEntryAt() != null ? trade.getEntryAt() : trade.getTriggeredAt();
    }

    private String textValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toUpperCase();
    }

    private Optional<BacktestReplayResultEntity> latestIntervalResult(List<BacktestReplayResultEntity> results,
                                                                     String interval,
                                                                     String triggerPricePolicy) {
        return results.stream()
                .filter(result -> interval.equalsIgnoreCase(result.getInterval()))
                .filter(result -> triggerPricePolicy.equalsIgnoreCase(result.getTriggerPricePolicy()))
                .max(Comparator.comparing(BacktestReplayResultEntity::getUpdatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private BacktestAggregate aggregateBacktestPairs(List<BacktestTradePair> pairs) {
        BacktestAggregate aggregate = new BacktestAggregate();
        aggregate.totalTrades = pairs.size();
        for (BacktestTradePair pair : pairs) {
            BacktestReplayResultEntity oneMinute = pair.oneMinute();
            BacktestReplayResultEntity fiveMinute = pair.fiveMinute();
            BacktestReplayResultEntity oneMinuteReentry = pair.oneMinuteReentry();
            if (isBacktestError(oneMinute) || isBacktestError(fiveMinute) || isBacktestError(oneMinuteReentry)) {
                aggregate.failedTrades++;
            }
            aggregate.lastRunAt = latestRunAt(aggregate.lastRunAt, oneMinute);
            aggregate.lastRunAt = latestRunAt(aggregate.lastRunAt, fiveMinute);
            aggregate.lastRunAt = latestRunAt(aggregate.lastRunAt, oneMinuteReentry);
            if (!isBacktestSuccess(oneMinute) || !isBacktestSuccess(fiveMinute)
                    || oneMinute.getBacktestPnl() == null || fiveMinute.getBacktestPnl() == null) {
                continue;
            }

            aggregate.comparableTrades++;
            aggregate.oneMinutePnl += oneMinute.getBacktestPnl();
            aggregate.fiveMinutePnl += fiveMinute.getBacktestPnl();
            if (isBacktestSuccess(oneMinuteReentry) && oneMinuteReentry.getBacktestPnl() != null) {
                aggregate.oneMinuteReentryComparableTrades++;
                aggregate.oneMinuteReentryPnl += oneMinuteReentry.getBacktestPnl();
                aggregate.diffReentryMinusOne += oneMinuteReentry.getBacktestPnl() - oneMinute.getBacktestPnl();
            }
            Double actualPnl = firstNonNull(oneMinute.getActualPnl(), fiveMinute.getActualPnl());
            if (actualPnl == null) {
                continue;
            }

            aggregate.actualComparableTrades++;
            aggregate.actualPnl += actualPnl;
            double oneMinuteDiff = oneMinute.getBacktestPnl() - actualPnl;
            double fiveMinuteDiff = fiveMinute.getBacktestPnl() - actualPnl;
            aggregate.oneMinuteMinusActual += oneMinuteDiff;
            aggregate.fiveMinuteMinusActual += fiveMinuteDiff;
            double oneMinuteAbs = Math.abs(oneMinuteDiff);
            double fiveMinuteAbs = Math.abs(fiveMinuteDiff);
            aggregate.oneMinuteAbsoluteError += oneMinuteAbs;
            aggregate.fiveMinuteAbsoluteError += fiveMinuteAbs;
            if (isBacktestSuccess(oneMinuteReentry) && oneMinuteReentry.getBacktestPnl() != null) {
                double reentryDiff = oneMinuteReentry.getBacktestPnl() - actualPnl;
                aggregate.oneMinuteReentryMinusActual += reentryDiff;
                aggregate.oneMinuteReentryAbsoluteError += Math.abs(reentryDiff);
            }
            if (Double.compare(oneMinuteAbs, fiveMinuteAbs) == 0) {
                aggregate.closerToActualTies++;
            } else if (oneMinuteAbs < fiveMinuteAbs) {
                aggregate.oneMinuteCloserToActual++;
            } else {
                aggregate.fiveMinuteCloserToActual++;
            }
        }
        return aggregate;
    }

    private boolean isBacktestSuccess(BacktestReplayResultEntity result) {
        return result != null && "SUCCESS".equalsIgnoreCase(result.getStatus());
    }

    private boolean isBacktestError(BacktestReplayResultEntity result) {
        return result != null && !"SUCCESS".equalsIgnoreCase(result.getStatus());
    }

    private LocalDateTime latestRunAt(LocalDateTime current, BacktestReplayResultEntity result) {
        if (result == null || result.getRunAt() == null) {
            return current;
        }
        return current == null || result.getRunAt().isAfter(current) ? result.getRunAt() : current;
    }

    private TradeAnalyticsResponse.BacktestSummary backtestSummary(BacktestAggregate aggregate) {
        return TradeAnalyticsResponse.BacktestSummary.builder()
                .totalTrades(aggregate.totalTrades)
                .comparableTrades(aggregate.comparableTrades)
                .actualComparableTrades(aggregate.actualComparableTrades)
                .failedTrades(aggregate.failedTrades)
                .actualPnl(round(aggregate.actualPnl))
                .oneMinutePnl(round(aggregate.oneMinutePnl))
                .fiveMinutePnl(round(aggregate.fiveMinutePnl))
                .oneMinuteReentryPnl(round(aggregate.oneMinuteReentryPnl))
                .diffFiveMinusOne(round(aggregate.fiveMinutePnl - aggregate.oneMinutePnl))
                .diffReentryMinusOne(round(aggregate.diffReentryMinusOne))
                .oneMinuteMinusActual(round(aggregate.oneMinuteMinusActual))
                .fiveMinuteMinusActual(round(aggregate.fiveMinuteMinusActual))
                .oneMinuteReentryMinusActual(round(aggregate.oneMinuteReentryMinusActual))
                .oneMinuteAbsoluteError(round(aggregate.oneMinuteAbsoluteError))
                .fiveMinuteAbsoluteError(round(aggregate.fiveMinuteAbsoluteError))
                .oneMinuteReentryAbsoluteError(round(aggregate.oneMinuteReentryAbsoluteError))
                .oneMinuteCloserToActual(aggregate.oneMinuteCloserToActual)
                .fiveMinuteCloserToActual(aggregate.fiveMinuteCloserToActual)
                .closerToActualTies(aggregate.closerToActualTies)
                .oneMinuteReentryComparableTrades(aggregate.oneMinuteReentryComparableTrades)
                .lastRunAt(aggregate.lastRunAt)
                .build();
    }

    private TradeAnalyticsResponse.BacktestDailyAnalytics backtestDaily(LocalDate date, BacktestAggregate aggregate) {
        return TradeAnalyticsResponse.BacktestDailyAnalytics.builder()
                .date(date)
                .totalTrades(aggregate.totalTrades)
                .comparableTrades(aggregate.comparableTrades)
                .actualComparableTrades(aggregate.actualComparableTrades)
                .failedTrades(aggregate.failedTrades)
                .actualPnl(round(aggregate.actualPnl))
                .oneMinutePnl(round(aggregate.oneMinutePnl))
                .fiveMinutePnl(round(aggregate.fiveMinutePnl))
                .oneMinuteReentryPnl(round(aggregate.oneMinuteReentryPnl))
                .diffFiveMinusOne(round(aggregate.fiveMinutePnl - aggregate.oneMinutePnl))
                .diffReentryMinusOne(round(aggregate.diffReentryMinusOne))
                .oneMinuteMinusActual(round(aggregate.oneMinuteMinusActual))
                .fiveMinuteMinusActual(round(aggregate.fiveMinuteMinusActual))
                .oneMinuteReentryMinusActual(round(aggregate.oneMinuteReentryMinusActual))
                .oneMinuteAbsoluteError(round(aggregate.oneMinuteAbsoluteError))
                .fiveMinuteAbsoluteError(round(aggregate.fiveMinuteAbsoluteError))
                .oneMinuteReentryAbsoluteError(round(aggregate.oneMinuteReentryAbsoluteError))
                .oneMinuteCloserToActual(aggregate.oneMinuteCloserToActual)
                .fiveMinuteCloserToActual(aggregate.fiveMinuteCloserToActual)
                .closerToActualTies(aggregate.closerToActualTies)
                .oneMinuteReentryComparableTrades(aggregate.oneMinuteReentryComparableTrades)
                .build();
    }

    private List<TradeAnalyticsResponse.RecentClosedTrade> buildRecentClosedTrades(List<TriggeredTradeSetupEntity> realizedTrades) {
        return realizedTrades.stream()
                .sorted(Comparator.comparing(TriggeredTradeSetupEntity::getExitedAt).reversed())
                .limit(10)
                .map(trade -> TradeAnalyticsResponse.RecentClosedTrade.builder()
                        .id(trade.getId())
                        .symbol(trade.getSymbol())
                        .quantity(trade.getQuantity())
                        .entryPrice(trade.getActualEntryPrice() != null ? trade.getActualEntryPrice() : trade.getEntryPrice())
                        .exitPrice(trade.getExitPrice())
                        .pnl(round(trade.getPnl()))
                        .exitReason(trade.getExitReason())
                        .exitedAt(trade.getExitedAt())
                        .build())
                .toList();
    }

    private boolean isRealizedTrade(TriggeredTradeSetupEntity trade) {
        return trade.getStatus() == TriggeredTradeStatus.EXITED_SUCCESS
                && trade.getPnl() != null
                && trade.getExitedAt() != null;
    }

    private boolean usesFunds(TriggeredTradeSetupEntity trade) {
        return trade.getQuantity() != null
                && trade.getQuantity() > 0
                && effectiveEntryPrice(trade) != null
                && trade.getStatus() != null
                && (trade.getStatus() == TriggeredTradeStatus.EXITED_SUCCESS || OPEN_STATUSES.contains(trade.getStatus()))
                && fundUseTime(trade) != null;
    }

    private double fundUseAmount(TriggeredTradeSetupEntity trade) {
        return effectiveEntryPrice(trade) * trade.getQuantity();
    }

    private Double effectiveEntryPrice(TriggeredTradeSetupEntity trade) {
        if (trade.getActualEntryPrice() != null) {
            return trade.getActualEntryPrice();
        }
        if (usesSpotReferenceForEntry(trade)) {
            return null;
        }
        return trade.getEntryPrice();
    }

    private boolean usesSpotReferenceForEntry(TriggeredTradeSetupEntity trade) {
        if (trade == null) {
            return false;
        }
        if (Boolean.TRUE.equals(trade.getUseSpotForEntry())) {
            return true;
        }
        return trade.getUseSpotForEntry() == null && Boolean.TRUE.equals(trade.getUseSpotPrice());
    }

    private LocalDateTime fundUseTime(TriggeredTradeSetupEntity trade) {
        return trade.getEntryAt() != null ? trade.getEntryAt() : trade.getTriggeredAt();
    }

    private LocalDateTime eventTime(TriggeredTradeSetupEntity trade) {
        if (trade.getExitedAt() != null) return trade.getExitedAt();
        if (trade.getEntryAt() != null) return trade.getEntryAt();
        return trade.getTriggeredAt();
    }

    private boolean isWithin(LocalDateTime value, LocalDateTime start, LocalDateTime end) {
        return value != null && !value.isBefore(start) && !value.isAfter(end);
    }

    private Double percent(long numerator, long denominator) {
        if (denominator == 0) return 0.0d;
        return round((numerator * 100.0d) / denominator);
    }

    private Double round(Double value) {
        if (value == null) return null;
        return Math.round(value * 100.0d) / 100.0d;
    }

    private Double firstNonNull(Double first, Double second) {
        return first != null ? first : second;
    }

    private record BacktestTradePair(Long tradeSetupId,
                                     LocalDate tradeDate,
                                     BacktestReplayResultEntity oneMinute,
                                     BacktestReplayResultEntity fiveMinute,
                                     BacktestReplayResultEntity oneMinuteReentry) {
    }

    private static class BacktestAggregate {
        private int totalTrades;
        private int comparableTrades;
        private int actualComparableTrades;
        private int failedTrades;
        private double actualPnl;
        private double oneMinutePnl;
        private double fiveMinutePnl;
        private double oneMinuteReentryPnl;
        private double diffReentryMinusOne;
        private double oneMinuteMinusActual;
        private double fiveMinuteMinusActual;
        private double oneMinuteReentryMinusActual;
        private double oneMinuteAbsoluteError;
        private double fiveMinuteAbsoluteError;
        private double oneMinuteReentryAbsoluteError;
        private int oneMinuteCloserToActual;
        private int fiveMinuteCloserToActual;
        private int closerToActualTies;
        private int oneMinuteReentryComparableTrades;
        private LocalDateTime lastRunAt;
    }

    private record FundUseEvent(LocalDateTime at, double deltaAmount, int deltaActiveTrades) {
    }

    private record PeakFundUse(LocalDateTime at, Double amount, Integer activeTrades) {
    }
}
