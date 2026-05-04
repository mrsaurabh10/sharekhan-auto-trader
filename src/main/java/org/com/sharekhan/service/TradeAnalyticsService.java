package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.dto.TradeAnalyticsResponse;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    public TradeAnalyticsResponse getTradeAnalytics(Long userId,
                                                    LocalDate from,
                                                    LocalDate to,
                                                    String symbol,
                                                    String source,
                                                    Long brokerCredentialsId,
                                                    Boolean intraday) {
        LocalDate resolvedTo = to != null ? to : LocalDate.now(MARKET_ZONE);
        LocalDate resolvedFrom = from != null ? from : resolvedTo.minusDays(30);
        if (resolvedFrom.isAfter(resolvedTo)) {
            LocalDate previousFrom = resolvedFrom;
            resolvedFrom = resolvedTo;
            resolvedTo = previousFrom;
        }

        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : symbol.trim();
        String normalizedSource = source == null || source.isBlank() ? null : source.trim();
        List<TriggeredTradeSetupEntity> candidateTrades = tradeRepository.findForAnalytics(
                userId,
                normalizedSymbol,
                normalizedSource,
                brokerCredentialsId,
                intraday
        );

        LocalDateTime start = resolvedFrom.atStartOfDay();
        LocalDateTime end = resolvedTo.atTime(LocalTime.MAX);

        List<TriggeredTradeSetupEntity> realizedTrades = candidateTrades.stream()
                .filter(this::isRealizedTrade)
                .filter(trade -> isWithin(trade.getExitedAt(), start, end))
                .toList();

        List<TriggeredTradeSetupEntity> openTrades = candidateTrades.stream()
                .filter(trade -> trade.getStatus() != null && OPEN_STATUSES.contains(trade.getStatus()))
                .filter(trade -> isWithin(trade.getTriggeredAt(), start, end))
                .toList();

        int rejectedTrades = (int) candidateTrades.stream()
                .filter(trade -> trade.getStatus() == TriggeredTradeStatus.REJECTED)
                .filter(trade -> isWithin(eventTime(trade), start, end))
                .count();
        int failedTrades = (int) candidateTrades.stream()
                .filter(trade -> trade.getStatus() != null && FAILED_STATUSES.contains(trade.getStatus()))
                .filter(trade -> isWithin(eventTime(trade), start, end))
                .count();

        return TradeAnalyticsResponse.builder()
                .filters(TradeAnalyticsResponse.Filters.builder()
                        .userId(userId)
                        .from(resolvedFrom)
                        .to(resolvedTo)
                        .symbol(normalizedSymbol)
                        .source(normalizedSource)
                        .brokerCredentialsId(brokerCredentialsId)
                        .intraday(intraday)
                        .build())
                .summary(buildSummary(realizedTrades, openTrades, rejectedTrades, failedTrades))
                .bySymbol(buildBySymbol(realizedTrades))
                .byDay(buildByDay(realizedTrades))
                .recentClosedTrades(buildRecentClosedTrades(realizedTrades))
                .build();
    }

    private TradeAnalyticsResponse.Summary buildSummary(List<TriggeredTradeSetupEntity> realizedTrades,
                                                        List<TriggeredTradeSetupEntity> openTrades,
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
}
