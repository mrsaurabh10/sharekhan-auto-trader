package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.dto.backtest.BacktestReportRequest;
import org.com.sharekhan.dto.backtest.BacktestReportResponse;
import org.com.sharekhan.dto.backtest.BacktestReplayRequest;
import org.com.sharekhan.dto.backtest.BacktestReplayResponse;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BacktestReportService {

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final Path REPORT_DIR = Path.of("outputs", "backtest-reports");

    private final TriggeredTradeSetupRepository tradeRepository;
    private final BacktestReplayService backtestReplayService;

    public BacktestReportResponse generateReport(BacktestReportRequest request) {
        BacktestReportRequest safeRequest = request != null ? request : new BacktestReportRequest();
        LocalDate to = safeRequest.getTo() != null ? safeRequest.getTo() : LocalDate.now(MARKET_ZONE).minusDays(1);
        LocalDate from = safeRequest.getFrom() != null ? safeRequest.getFrom() : to;
        if (from.isAfter(to)) {
            LocalDate previousFrom = from;
            from = to;
            to = previousFrom;
        }
        String source = StringUtils.hasText(safeRequest.getSource()) ? safeRequest.getSource().trim() : "atr-signal";
        List<String> intervals = safeRequest.getIntervals() != null && !safeRequest.getIntervals().isEmpty()
                ? safeRequest.getIntervals()
                : List.of("1minute", "5minute");

        List<TriggeredTradeSetupEntity> trades = tradeRepository.findBySourceForBacktestRange(
                source,
                from.atStartOfDay(),
                to.atTime(23, 59, 59));

        String reportId = LocalDateTime.now(MARKET_ZONE).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path reportPath = reportPath(reportId);
        LocalDateTime generatedAt = LocalDateTime.now(MARKET_ZONE);

        int resultCount = 0;
        int successCount = 0;
        int errorCount = 0;
        double actualPnl = 0d;
        double backtestPnl = 0d;

        try {
            Files.createDirectories(REPORT_DIR);
            try (BufferedWriter writer = Files.newBufferedWriter(reportPath, StandardCharsets.UTF_8)) {
                writer.write(header());
                writer.newLine();
                for (TriggeredTradeSetupEntity trade : trades) {
                    for (String interval : intervals) {
                        resultCount++;
                        try {
                            BacktestReplayResponse response = backtestReplayService.replayTrade(trade.getId(), replayRequest(safeRequest, interval));
                            successCount++;
                            actualPnl += value(response.getActual() != null ? response.getActual().getPnl() : null);
                            backtestPnl += value(response.getBacktest() != null ? response.getBacktest().getPnl() : null);
                            writer.write(successRow(trade, interval, response));
                            writer.newLine();
                        } catch (Exception ex) {
                            errorCount++;
                            writer.write(errorRow(trade, interval, ex.getMessage()));
                            writer.newLine();
                        }
                    }
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to write backtest report: " + ex.getMessage(), ex);
        }

        return BacktestReportResponse.builder()
                .status("success")
                .reportId(reportId)
                .from(from)
                .to(to)
                .source(source)
                .tradeCount(trades.size())
                .resultCount(resultCount)
                .successCount(successCount)
                .errorCount(errorCount)
                .actualPnl(round(actualPnl))
                .backtestPnl(round(backtestPnl))
                .downloadUrl("/api/backtests/reports/" + reportId + "/download")
                .generatedAt(generatedAt)
                .build();
    }

    public Path reportPath(String reportId) {
        return REPORT_DIR.resolve(reportId + ".csv").normalize();
    }

    private BacktestReplayRequest replayRequest(BacktestReportRequest request, String interval) {
        BacktestReplayRequest replay = new BacktestReplayRequest();
        replay.setIntradayOnly(request.getIntradayOnly() == null || Boolean.TRUE.equals(request.getIntradayOnly()));
        replay.setInterval(interval);
        replay.setSquareOffTime(request.getSquareOffTime());
        replay.setTriggerPriceMode(request.getTriggerPriceMode());
        replay.setExecutionPricePolicy(request.getExecutionPricePolicy());
        replay.setReEntryOnStopLoss(request.getReEntryOnStopLoss());
        replay.setMaxReEntries(request.getMaxReEntries());
        replay.setQuantity(request.getQuantity());
        replay.setLevels(request.getLevels());
        replay.setOverrides(request.getOverrides());
        return replay;
    }

    private String header() {
        return "tradeSetupId,tradeDate,symbol,scripCode,optionType,strikePrice,expiry,interval,status,"
                + "actualEntryAt,actualExitAt,actualExitReason,actualPnl,"
                + "btEntryAt,btEntryPrice,btExitAt,btExitPrice,btExitReason,btQuantity,btLots,btPnl,"
                + "quantityMode,levelMode,stopLoss,target1,target2,target3,error";
    }

    private String successRow(TriggeredTradeSetupEntity trade, String interval, BacktestReplayResponse response) {
        BacktestReplayResponse.Result actual = response.getActual();
        BacktestReplayResponse.Result backtest = response.getBacktest();
        BacktestReplayResponse.ResolvedConfig resolved = response.getResolved();
        return csv(
                trade.getId(), tradeDate(trade), trade.getSymbol(), trade.getScripCode(), trade.getOptionType(),
                trade.getStrikePrice(), trade.getExpiry(), interval, "SUCCESS",
                actual != null ? actual.getEntryAt() : null,
                actual != null ? actual.getExitAt() : null,
                actual != null ? actual.getExitReason() : null,
                actual != null ? actual.getPnl() : null,
                backtest != null ? backtest.getEntryAt() : null,
                backtest != null ? backtest.getEntryPrice() : null,
                backtest != null ? backtest.getExitAt() : null,
                backtest != null ? backtest.getExitPrice() : null,
                backtest != null ? backtest.getExitReason() : null,
                backtest != null ? backtest.getQuantity() : null,
                resolved != null ? resolved.getLots() : null,
                backtest != null ? backtest.getPnl() : null,
                resolved != null ? resolved.getQuantityMode() : null,
                resolved != null ? resolved.getLevelMode() : null,
                resolved != null ? resolved.getStopLoss() : null,
                resolved != null ? resolved.getTarget1() : null,
                resolved != null ? resolved.getTarget2() : null,
                resolved != null ? resolved.getTarget3() : null,
                null);
    }

    private String errorRow(TriggeredTradeSetupEntity trade, String interval, String message) {
        return csv(
                trade.getId(), tradeDate(trade), trade.getSymbol(), trade.getScripCode(), trade.getOptionType(),
                trade.getStrikePrice(), trade.getExpiry(), interval, "ERROR",
                null, null, null, trade.getPnl(),
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                message);
    }

    private LocalDate tradeDate(TriggeredTradeSetupEntity trade) {
        LocalDateTime at = trade.getEntryAt() != null ? trade.getEntryAt() : trade.getTriggeredAt();
        return at != null ? at.toLocalDate() : null;
    }

    private String csv(Object... values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(escape(values[i]));
        }
        return builder.toString();
    }

    private String escape(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private double value(Double value) {
        return value != null && Double.isFinite(value) ? value : 0d;
    }

    private double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }
}
