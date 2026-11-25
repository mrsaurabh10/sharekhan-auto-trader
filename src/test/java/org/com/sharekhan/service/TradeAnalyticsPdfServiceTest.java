package org.com.sharekhan.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.com.sharekhan.dto.TradeAnalyticsResponse;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TradeAnalyticsPdfServiceTest {
    @Test
    void paginatesAllPositionsAndRendersEquity() throws Exception {
        var positions = new ArrayList<TradeAnalyticsReportData.Position>();
        var day = LocalDate.of(2026, 11, 25);
        for (long i = 1; i <= 35; i++) {
            double pnl = i % 3 == 0 ? -300 : 200;
            positions.add(new TradeAnalyticsReportData.Position(i, "NIFTY 25000 CE", "strategy-" + "long-source-".repeat(8),
                    "BUY", "EXITED_SUCCESS", 75L, 100d, 110d, (i <= 25 ? day.minusDays(1) : day).atTime(9, (int)i), day.atTime(10, (int)i),
                    pnl, 20d, pnl - 20, "Target / stop loss filled"));
        }
        var summary = TradeAnalyticsResponse.Summary.builder().realizedPnl(1500d).effectiveRealizedPnl(800d)
                .brokerage(350d).governmentTaxes(350d).totalTradeCost(700d).totalClosedTrades(35)
                .winningTrades(24).losingTrades(11).breakevenTrades(0).winRate(68.57).profitFactor(1.45)
                .averageWin(200d).averageLoss(-300d).bestTradePnl(200d).worstTradePnl(-300d)
                .maxFundUseAtTime(7500d).activeTradesAtMaxFundUse(1).openTrades(0).openQuantity(0L)
                .rejectedTrades(2).failedTrades(0).build();
        var analytics = TradeAnalyticsResponse.builder().summary(summary).filters(TradeAnalyticsResponse.Filters.builder()
                .from(day).to(day).source("alpha, beta").userId(7L).scope("own").build()).build();
        java.util.Collections.reverse(positions);
        byte[] bytes = new TradeAnalyticsPdfService().generate(new TradeAnalyticsReportData(analytics, positions));
        var output = Path.of("target/pdf-review");
        Files.createDirectories(output);
        Files.write(output.resolve("analytics-sample.pdf"), bytes);
        try (var pdf = Loader.loadPDF(bytes)) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(3);
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("Accuracy (win rate)", "Max drawdown", "#35", "Positions continued", "Effective P&L");
            assertThat(text).contains("Entry date: 2026-11-24", "Entry date: 2026-11-25", "Date subtotal | Positions: 25", "Date subtotal | Positions: 10");
            assertThat(text).contains("Entry date: 2026-11-24 | Day P&L: 1,000.00 | Effective P&L: 500.00",
                    "Entry date: 2026-11-25 | Day P&L: 500.00 | Effective P&L: 300.00");
            assertThat(text.indexOf("Entry date: 2026-11-24")).isLessThan(text.indexOf("Entry date: 2026-11-25"));
            assertThat(text.indexOf("#1 ")).isLessThan(text.indexOf("#25 "));
            for (int i = 2; i <= pdf.getNumberOfPages(); i++) {
                var stripper = new PDFTextStripper();
                stripper.setStartPage(i); stripper.setEndPage(i);
                assertThat(stripper.getText(pdf)).contains("Symbol", "Source", "Gross P&L", "Effective P&L", "Entry date:");
                assertThat(pdf.getPage(i - 1).getMediaBox().getWidth()).isGreaterThan(pdf.getPage(i - 1).getMediaBox().getHeight());
            }
            var renderer = new PDFRenderer(pdf);
            ImageIO.write(renderer.renderImageWithDPI(0, 120), "png", output.resolve("summary.png").toFile());
            ImageIO.write(renderer.renderImageWithDPI(1, 120), "png", output.resolve("positions.png").toFile());
            ImageIO.write(renderer.renderImageWithDPI(pdf.getNumberOfPages() - 1, 120), "png", output.resolve("last-page.png").toFile());
        }
    }

    @Test
    void emptyAndOpenOnlyReportsHaveNoRealizedDrawdown() throws Exception {
        var day = LocalDate.of(2026, 11, 25);
        var analytics = TradeAnalyticsResponse.builder().filters(TradeAnalyticsResponse.Filters.builder().from(day).to(day).build())
                .summary(TradeAnalyticsResponse.Summary.builder().build()).build();
        var bytes = new TradeAnalyticsPdfService().generate(new TradeAnalyticsReportData(analytics, List.of()));
        try (var pdf = Loader.loadPDF(bytes)) {
            assertThat(new PDFTextStripper().getText(pdf)).contains("No closed trades", "No positions match");
        }
        var open = new TradeAnalyticsReportData.Position(100L, "NIFTY", "alpha", "BUY", "EXECUTED", 75L,
                100d, null, null, null, null, null, null, null);
        var openBytes = new TradeAnalyticsPdfService().generate(new TradeAnalyticsReportData(analytics, List.of(open)));
        try (var pdf = Loader.loadPDF(openBytes)) {
            assertThat(new PDFTextStripper().getText(pdf)).contains("Entry date: Unknown", "#100", "EXECUTED", "Date subtotal | Positions: 1");
        }
        assertThat(TradeAnalyticsPdfService.drawdown(List.of(), false)).isZero();
    }

    @Test
    void drawdownStartsAtZeroAndTracksRecovery() {
        var positions = new ArrayList<TradeAnalyticsReportData.Position>();
        for (double pnl : new double[] {-100, 500, -200, -300, 600}) {
            positions.add(new TradeAnalyticsReportData.Position(1L, null, null, null, null, null,
                    null, null, null, null, pnl, 10d, pnl - 10, null));
        }
        assertThat(TradeAnalyticsPdfService.drawdown(positions, false)).isEqualTo(500);
        assertThat(TradeAnalyticsPdfService.drawdown(positions, true)).isEqualTo(520);
    }
}
