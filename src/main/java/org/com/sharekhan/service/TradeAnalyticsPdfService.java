package org.com.sharekhan.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.com.sharekhan.dto.TradeAnalyticsResponse;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class TradeAnalyticsPdfService {
    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public byte[] generate(TradeAnalyticsReportData report) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             Layout page = new Layout(document)) {
            var analytics = report.analytics();
            var f = analytics.getFilters();
            var s = analytics.getSummary();
            page.heading("Trading analytics report");
            page.line("Generated " + TIME.format(LocalDateTime.now(ZoneId.of("Asia/Kolkata"))) + " IST | Amounts in INR");
            page.line("Period: " + f.getFrom() + " to " + f.getTo() + " | User: " + f.getUserId() + " | Scope: " + value(f.getScope()));
            page.line("Sources: " + (f.getSource() == null ? "All" : f.getSource()));
            page.line("Symbol: " + (f.getSymbol() == null ? "All" : f.getSymbol()) + " | Intraday: "
                    + (Boolean.TRUE.equals(f.getIntraday()) ? "Yes" : "All") + " | Broker credential: " + value(f.getBrokerCredentialsId()));
            page.heading("Performance");
            page.line("Gross realized P&L: " + money(s.getRealizedPnl()) + "     Effective realized P&L: " + money(s.getEffectiveRealizedPnl()));
            page.line("Brokerage: " + money(s.getBrokerage()) + "     Taxes / charges: " + money(s.getGovernmentTaxes())
                    + "     Total cost: " + money(s.getTotalTradeCost()));
            page.line("Closed: " + s.getTotalClosedTrades() + "     Wins / losses / breakeven: " + s.getWinningTrades()
                    + " / " + s.getLosingTrades() + " / " + s.getBreakevenTrades() + "     Accuracy (win rate): " + money(s.getWinRate()) + "%");
            page.line("Profit factor: " + money(s.getProfitFactor()) + "     Average win / loss: " + money(s.getAverageWin()) + " / " + money(s.getAverageLoss()));
            page.line("Best trade: " + money(s.getBestTradePnl()) + "     Max single-trade loss: "
                    + money(s.getWorstTradePnl() == null ? null : Math.max(0, -s.getWorstTradePnl())));
            List<TradeAnalyticsReportData.Position> closed = report.positions().stream().filter(p -> p.pnl() != null && p.exitedAt() != null)
                    .sorted(Comparator.comparing(TradeAnalyticsReportData.Position::exitedAt)
                            .thenComparing(TradeAnalyticsReportData.Position::id, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            page.line("Max drawdown (gross / effective): " + money(drawdown(closed, false)) + " / " + money(drawdown(closed, true)));
            page.line("Peak funds in use: " + money(s.getMaxFundUseAtTime()) + "     At: " + value(s.getMaxFundUseAt())
                    + "     Active trades: " + value(s.getActiveTradesAtMaxFundUse()));
            page.line("Open positions: " + s.getOpenTrades() + "     Open quantity: " + s.getOpenQuantity()
                    + "     Rejected / failed attempts: " + s.getRejectedTrades() + " / " + s.getFailedTrades());
            page.line("Closed trades use exit date; open trades use trigger date. Failed/rejected attempts are counts only.");
            page.line("Accuracy uses gross profitable closed trades / all closed trades. Costs follow the analytics charge model.");
            page.line("Equity starts at zero (cumulative realized P&L); drawdown is peak-to-trough at each trade close.");
            page.heading("Realized equity | gross (blue) / effective (green)");
            page.chart(closed);
            page.positions(report.positions());
            page.finish();
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    static double drawdown(List<TradeAnalyticsReportData.Position> closed, boolean net) {
        double equity = 0, peak = 0, maximum = 0;
        for (var p : closed) {
            equity += net ? p.effectivePnl() : p.pnl();
            peak = Math.max(peak, equity);
            maximum = Math.max(maximum, peak - equity);
        }
        return maximum;
    }

    private static String money(Number value) { return value == null ? "N/A" : String.format(Locale.US, "%,.2f", value.doubleValue()); }
    private static String value(Object value) { return value == null ? "N/A" : value.toString(); }
    private static String date(LocalDateTime date) { return date == null ? "N/A" : TIME.format(date); }

    private static class Layout implements AutoCloseable {
        private final PDDocument document;
        private PDPageContentStream stream;
        private float y;
        private static final float WIDTH = PDRectangle.A4.getWidth() - 80;

        Layout(PDDocument document) throws IOException { this.document = document; newPage(); }

        void newPage() throws IOException { newPage(PDRectangle.A4); }

        void newPage(PDRectangle size) throws IOException {
            finish();
            PDPage page = new PDPage(size);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = size.getHeight() - 42;
        }

        void finish() throws IOException {
            if (stream == null) return;
            text("Trading analytics | Page " + document.getNumberOfPages(), 40, 25, 9, FONT);
            stream.close();
            stream = null;
        }

        void text(String value, float x, float baseline, float size, PDType1Font font) throws IOException {
            stream.setNonStrokingColor(new java.awt.Color(30, 44, 65));
            stream.beginText(); stream.setFont(font, size); stream.newLineAtOffset(x, baseline);
            stream.showText(value); stream.endText();
        }

        List<String> wrap(String input) throws IOException { return wrap(input, WIDTH, 9); }

        List<String> wrap(String input, float width, float size) throws IOException {
            // Standard PDF fonts do not contain every Unicode glyph. Replace unsupported characters safely.
            StringBuilder safe = new StringBuilder();
            for (int cp : input.codePoints().toArray()) {
                if (cp == '\n') { safe.append('\n'); continue; }
                String ch = new String(Character.toChars(cp));
                try { FONT.encode(ch); safe.append(ch); } catch (IllegalArgumentException e) { safe.append('?'); }
            }
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (char ch : safe.toString().toCharArray()) {
                if (ch == '\n') { lines.add(line.toString()); line.setLength(0); continue; }
                if (FONT.getStringWidth(line.toString() + ch) / 1000 * size > width) {
                    lines.add(line.toString()); line.setLength(0);
                }
                line.append(ch);
            }
            lines.add(line.toString());
            return lines;
        }

        void heading(String title) throws IOException {
            if (y < 90) newPage();
            y -= 10; text(title, 40, y, 14, BOLD); y -= 24;
        }

        void line(String value) throws IOException {
            for (String row : wrap(value)) {
                if (y < 50) newPage();
                text(row, 40, y, 9, FONT); y -= 15;
            }
        }

        private static final PDRectangle TABLE_PAGE = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
        private static final float[] COLUMNS = {32, 85, 80, 100, 30, 48, 48, 75, 75, 60, 54, 75};
        private static final List<String> HEADERS = List.of("ID", "Symbol", "Source", "Side / status / reason", "Qty",
                "Entry", "Exit", "Entered", "Exited", "Gross P&L", "Cost", "Effective P&L");

        void positions(List<TradeAnalyticsReportData.Position> positions) throws IOException {
            newPage(TABLE_PAGE);
            heading("Positions taken (" + positions.size() + ")");
            text("Grouped by entry date (IST). Closed positions follow the selected exit-date filter; open P&L is excluded.", 40, y, 9, FONT);
            y -= 24;
            if (positions.isEmpty()) { line("No positions match the selected filters."); return; }
            var groups = new java.util.TreeMap<java.time.LocalDate, List<TradeAnalyticsReportData.Position>>(
                    Comparator.nullsLast(Comparator.naturalOrder()));
            for (var position : positions) {
                var day = position.enteredAt() == null ? null : position.enteredAt().toLocalDate();
                groups.computeIfAbsent(day, ignored -> new ArrayList<>()).add(position);
            }
            for (var group : groups.entrySet()) {
                var rows = group.getValue().stream().sorted(Comparator
                        .comparing(TradeAnalyticsReportData.Position::enteredAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(TradeAnalyticsReportData.Position::id, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
                double gross = rows.stream().filter(p -> p.pnl() != null).mapToDouble(TradeAnalyticsReportData.Position::pnl).sum();
                double cost = rows.stream().filter(p -> p.cost() != null).mapToDouble(TradeAnalyticsReportData.Position::cost).sum();
                double effective = rows.stream().filter(p -> p.effectivePnl() != null).mapToDouble(TradeAnalyticsReportData.Position::effectivePnl).sum();
                String label = "Entry date: " + (group.getKey() == null ? "Unknown" : group.getKey())
                        + " | Day P&L: " + money(gross) + " | Effective P&L: " + money(effective);
                if (y < 160) { newPage(TABLE_PAGE); heading("Positions continued"); }
                tableHeader(label);
                for (var position : rows) {
                    var cells = List.of("#" + value(position.id()), value(position.symbol()), value(position.source()),
                            value(position.side()) + "\n" + value(position.status()) + "\n" + value(position.exitReason()),
                            value(position.quantity()), money(position.entryPrice()), money(position.exitPrice()),
                            date(position.enteredAt()).replace(' ', '\n'), date(position.exitedAt()).replace(' ', '\n'),
                            money(position.pnl()), money(position.cost()), money(position.effectivePnl()));
                    tableRow(cells, false, label);
                }
                if (y < 75) { newPage(TABLE_PAGE); heading("Positions continued"); tableHeader(label + " (continued)"); }
                text("Date subtotal | Positions: " + rows.size() + " | Gross P&L: " + money(gross)
                        + " | Cost: " + money(cost) + " | Effective P&L: " + money(effective), 40, y - 14, 9, BOLD);
                y -= 40;
            }
        }

        void tableHeader(String label) throws IOException {
            text(label, 40, y, 11, BOLD); y -= 10;
            tableRow(HEADERS, true, label);
        }

        void tableRow(List<String> cells, boolean header, String groupLabel) throws IOException {
            List<List<String>> wrapped = new ArrayList<>();
            for (int i = 0; i < cells.size(); i++) wrapped.add(wrap(cells.get(i), COLUMNS[i] - 8, 7.5f));
            int lineCount = wrapped.stream().mapToInt(List::size).max().orElse(1);
            int offset = 0;
            while (offset < lineCount) {
                int available = Math.max(0, (int)((y - 48 - 10) / 11));
                if (available < Math.min(lineCount - offset, 3)) {
                    newPage(TABLE_PAGE); heading("Positions continued"); tableHeader(groupLabel + " (continued)");
                    available = (int)((y - 48 - 10) / 11);
                }
                // Keep ordinary rows together; unusually long source/reason cells may continue on the next page.
                if (!header && offset == 0 && lineCount * 11 + 10 <= 420 && available < lineCount) {
                    newPage(TABLE_PAGE); heading("Positions continued"); tableHeader(groupLabel + " (continued)");
                    available = (int)((y - 48 - 10) / 11);
                }
                int count = Math.min(available, lineCount - offset);
                float height = count * 11 + 10;
                if (header) {
                    stream.setNonStrokingColor(new java.awt.Color(232, 239, 248));
                    stream.addRect(40, y - height, 762, height); stream.fill();
                }
                float x = 40;
                stream.setLineWidth(0.4f);
                for (int col = 0; col < cells.size(); col++) {
                    for (int row = 0; row < count && offset + row < wrapped.get(col).size(); row++) {
                        text(wrapped.get(col).get(offset + row), x + 4, y - 13 - row * 11, 7.5f, header ? BOLD : FONT);
                    }
                    stream.setStrokingColor(new java.awt.Color(205, 215, 228));
                    stream.addRect(x, y - height, COLUMNS[col], height); stream.stroke();
                    x += COLUMNS[col];
                }
                y -= height;
                offset += count;
            }
        }

        void chart(List<TradeAnalyticsReportData.Position> closed) throws IOException {
            if (closed.isEmpty()) { line("No closed trades for this range."); return; }
            if (y < 260) newPage();
            float bottom = y - 175, left = 105, width = WIDTH - 65;
            double[] gross = new double[closed.size() + 1], net = new double[closed.size() + 1];
            double min = 0, max = 0;
            for (int i = 0; i < closed.size(); i++) {
                gross[i + 1] = gross[i] + closed.get(i).pnl(); net[i + 1] = net[i] + closed.get(i).effectivePnl();
                min = Math.min(min, Math.min(gross[i + 1], net[i + 1])); max = Math.max(max, Math.max(gross[i + 1], net[i + 1]));
            }
            if (max == min) { max += 1; min -= 1; }
            double range = max - min;
            for (int i = 0; i <= 4; i++) {
                float yy = bottom + i * 40;
                stream.setStrokingColor(new java.awt.Color(225, 230, 237)); stream.moveTo(left, yy); stream.lineTo(left + width, yy); stream.stroke();
                text(money(min + range * i / 4), 40, yy - 3, 8, FONT);
            }
            for (int series = 0; series < 2; series++) {
                stream.setStrokingColor(new java.awt.Color(series == 0 ? 30 : 15, series == 0 ? 100 : 140, series == 0 ? 210 : 95));
                stream.setLineWidth(1.5f);
                double[] values = series == 0 ? gross : net;
                for (int i = 0; i < values.length; i++) {
                    float xx = left + width * i / closed.size(), yy = bottom + (float)((values[i] - min) / range * 160);
                    if (i == 0) stream.moveTo(xx, yy); else stream.lineTo(xx, yy);
                }
                stream.stroke();
            }
            text("Start (0)", left, bottom - 17, 8, FONT);
            text("Trade closes: " + closed.size(), left + width - 90, bottom - 17, 8, FONT);
            y = bottom - 38;
            line("First close: " + date(closed.getFirst().exitedAt()) + " | Last close: " + date(closed.getLast().exitedAt()));
        }

        @Override public void close() throws IOException { finish(); }
    }
}
