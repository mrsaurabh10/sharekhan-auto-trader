package org.com.sharekhan.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.com.sharekhan.dto.TradeAnalyticsResponse;
import org.springframework.stereotype.Service;

import java.awt.Color;
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
    private static final Color TEXT = new Color(30, 44, 65);
    private static final Color BLUE = new Color(25, 80, 155);
    private static final Color PALE_BLUE = new Color(232, 241, 253);
    private static final Color PROFIT = new Color(0, 115, 70);
    private static final Color LOSS = new Color(185, 35, 50);
    private static final Color ROW_TINT = new Color(246, 249, 254);
    private static final java.util.regex.Pattern PNL_VALUE = java.util.regex.Pattern.compile(
            "(?:Day P&L|Gross P&L|Effective P&L|Gross realized P&L|Effective realized P&L|Average win|Average loss|Best trade|Max single-trade loss): ([-0-9,]+\\.[0-9]{2})");

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
            page.line("Profit factor: " + money(s.getProfitFactor()) + "     Average win: " + money(s.getAverageWin()) + " | Average loss: " + money(s.getAverageLoss()));
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
            page.heading("Equity and drawdown");
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
        private float pageWidth;
        private int positionRow;
        private static final float WIDTH = PDRectangle.A4.getWidth() - 80;

        Layout(PDDocument document) throws IOException { this.document = document; newPage(); }

        void newPage() throws IOException { newPage(PDRectangle.A4); }

        void newPage(PDRectangle size) throws IOException {
            finish();
            PDPage page = new PDPage(size);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            pageWidth = size.getWidth();
            stream.setNonStrokingColor(BLUE);
            stream.addRect(0, size.getHeight() - 8, pageWidth, 8); stream.fill();
            y = size.getHeight() - 42;
        }

        void finish() throws IOException {
            if (stream == null) return;
            stream.setStrokingColor(new Color(190, 210, 237));
            stream.setLineWidth(0.6f); stream.moveTo(40, 40); stream.lineTo(pageWidth - 40, 40); stream.stroke();
            text("Trading analytics | Page " + document.getNumberOfPages(), 40, 25, 9, FONT);
            stream.close();
            stream = null;
        }

        void text(String value, float x, float baseline, float size, PDType1Font font) throws IOException {
            text(value, x, baseline, size, font, TEXT);
        }

        void text(String value, float x, float baseline, float size, PDType1Font font, Color color) throws IOException {
            stream.setNonStrokingColor(color);
            stream.beginText(); stream.setFont(font, size); stream.newLineAtOffset(x, baseline);
            stream.showText(value); stream.endText();
        }

        Color pnlColor(String amount) {
            try {
                double number = Double.parseDouble(amount.replace(",", ""));
                return number > 0 ? PROFIT : number < 0 ? LOSS : TEXT;
            } catch (NumberFormatException e) { return TEXT; }
        }

        void financialText(String value, float x, float baseline, float size, PDType1Font font, Color base) throws IOException {
            var matcher = PNL_VALUE.matcher(value);
            int previous = 0;
            while (matcher.find()) {
                String prefix = value.substring(previous, matcher.start(1));
                text(prefix, x, baseline, size, font, base);
                x += font.getStringWidth(prefix) / 1000 * size;
                String amount = matcher.group(1);
                Color color = matcher.group().startsWith("Max single-trade loss:") && Double.parseDouble(amount.replace(",", "")) > 0
                        ? LOSS : pnlColor(amount);
                text(amount, x, baseline, size, font, color);
                x += font.getStringWidth(amount) / 1000 * size;
                previous = matcher.end(1);
            }
            text(value.substring(previous), x, baseline, size, font, base);
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
            y -= 10;
            stream.setNonStrokingColor(PALE_BLUE);
            stream.addRect(40, y - 7, pageWidth - 80, 27); stream.fill();
            stream.setNonStrokingColor(BLUE);
            stream.addRect(40, y - 7, 4, 27); stream.fill();
            text(title, 50, y, 14, BOLD, BLUE); y -= 24;
        }

        void line(String value) throws IOException {
            for (String row : wrap(value)) {
                if (y < 50) newPage();
                financialText(row, 40, y, 9, FONT, TEXT); y -= 15;
            }
        }

        private static final PDRectangle TABLE_PAGE = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
        private static final float[] COLUMNS = {52, 75, 70, 100, 30, 48, 48, 75, 75, 60, 54, 75};
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
                positionRow = 0;
                tableHeader(label);
                for (var position : rows) {
                    var cells = List.of("#" + value(position.id()), value(position.symbol()), value(position.source()),
                            value(position.side()) + "\n" + value(position.status()) + "\n" + value(position.exitReason()),
                            value(position.quantity()), money(position.entryPrice()), money(position.exitPrice()),
                            date(position.enteredAt()).replace(' ', '\n'), date(position.exitedAt()).replace(' ', '\n'),
                            money(position.pnl()), money(position.cost()), money(position.effectivePnl()));
                    tableRow(cells, false, label);
                    positionRow++;
                }
                if (y < 75) { newPage(TABLE_PAGE); heading("Positions continued"); tableHeader(label + " (continued)"); }
                stream.setNonStrokingColor(PALE_BLUE);
                stream.addRect(40, y - 24, 762, 24); stream.fill();
                financialText("Date subtotal | Positions: " + rows.size() + " | Gross P&L: " + money(gross)
                        + " | Cost: " + money(cost) + " | Effective P&L: " + money(effective), 46, y - 15, 9, BOLD, BLUE);
                y -= 40;
            }
        }

        void tableHeader(String label) throws IOException {
            stream.setNonStrokingColor(PALE_BLUE);
            stream.addRect(40, y - 7, 762, 25); stream.fill();
            financialText(label, 46, y, 11, BOLD, BLUE); y -= 10;
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
                stream.setNonStrokingColor(header ? BLUE : positionRow % 2 == 0 ? Color.WHITE : ROW_TINT);
                stream.addRect(40, y - height, 762, height); stream.fill();
                float x = 40;
                stream.setLineWidth(0.4f);
                for (int col = 0; col < cells.size(); col++) {
                    for (int row = 0; row < count && offset + row < wrapped.get(col).size(); row++) {
                        text(wrapped.get(col).get(offset + row), x + 4, y - 13 - row * 11, 7.5f,
                                header || col == 9 || col == 11 ? BOLD : FONT,
                                header ? Color.WHITE : col == 9 || col == 11 ? pnlColor(cells.get(col)) : TEXT);
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
            if (y < 330) { newPage(); heading("Equity and drawdown"); }
            double[] equity = new double[closed.size() + 1];
            double minimum = 0, maximum = 0;
            for (int i = 0; i < closed.size(); i++) {
                equity[i + 1] = equity[i] + closed.get(i).pnl();
                minimum = Math.min(minimum, equity[i + 1]);
                maximum = Math.max(maximum, equity[i + 1]);
            }
            double span = maximum - minimum;
            double targetStep = (span == 0 ? 1 : span * 1.12) / 4;
            double magnitude = Math.pow(10, Math.floor(Math.log10(targetStep)));
            double fraction = targetStep / magnitude;
            double step = (fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10) * magnitude;
            double low = minimum < 0 ? Math.floor((minimum - span * 0.04) / step) * step : 0;
            double high = maximum > 0 ? Math.ceil((maximum + span * 0.04) / step) * step : 0;
            if (low == high) { low = -1; high = 1; step = 0.5; }
            text("GROSS REALIZED P&L", 40, y + 2, 8, BOLD, BLUE);
            text(money(equity[equity.length - 1]), 40, y - 18, 16, BOLD, pnlColor(money(equity[equity.length - 1])));
            text("MAX DRAWDOWN", 310, y + 2, 8, BOLD, BLUE);
            double maximumDrawdown = drawdown(closed, false);
            text(money(maximumDrawdown), 310, y - 18, 16, BOLD, maximumDrawdown > 0 ? LOSS : TEXT);

            float left = 105, width = WIDTH - 65, top = y - 48;
            float totalHeight = Math.min(285, top - 130);
            float drawdownHeight = 60, panelGap = 30;
            float height = totalHeight - drawdownHeight - panelGap, bottom = top - height;
            float drawdownTop = bottom - panelGap, drawdownBottom = drawdownTop - drawdownHeight;
            double range = high - low;
            stream.setNonStrokingColor(new Color(250, 252, 255));
            stream.addRect(left, bottom, width, height); stream.fill();
            for (double tick = low; tick <= high + step / 100; tick += step) {
                float yy = bottom + (float)((tick - low) / range * height);
                stream.setStrokingColor(Math.abs(tick) < step / 100 ? new Color(130, 151, 181) : new Color(220, 229, 241));
                stream.setLineWidth(Math.abs(tick) < step / 100 ? 1f : 0.5f);
                stream.moveTo(left, yy); stream.lineTo(left + width, yy); stream.stroke();
                String label = Math.abs(tick) < 0.00001 ? "0" : String.format(Locale.US, step < 1 ? "%,.2f" : "%,.0f", tick);
                text(label, left - 10 - FONT.getStringWidth(label) / 1000 * 8, yy - 3, 8, FONT, TEXT);
            }
            text("INR", 40, top + 4, 8, BOLD, BLUE);
            int intervals = Math.min(4, closed.size());
            var dayFormat = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH);
            var timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
            for (int tick = 0; tick <= intervals; tick++) {
                int index = (int)Math.round((double)tick * closed.size() / intervals);
                float xx = left + width * index / closed.size();
                stream.setStrokingColor(new Color(228, 235, 246)); stream.setLineWidth(0.5f);
                stream.moveTo(xx, bottom); stream.lineTo(xx, top); stream.stroke();
                String label = index == 0 ? "Start" : dayFormat.format(closed.get(index - 1).exitedAt());
                text(label, xx - FONT.getStringWidth(label) / 1000 * 8 / 2, drawdownBottom - 17, 8, FONT);
                if (index > 0) {
                    String time = timeFormat.format(closed.get(index - 1).exitedAt());
                    text(time, xx - FONT.getStringWidth(time) / 1000 * 8 / 2, drawdownBottom - 29, 8, FONT);
                }
            }
            stream.setStrokingColor(BLUE); stream.setLineWidth(2.2f); stream.setLineJoinStyle(1); stream.setLineCapStyle(1);
            for (int i = 0; i < equity.length; i++) {
                float xx = left + width * i / closed.size(), yy = bottom + (float)((equity[i] - low) / range * height);
                if (i == 0) stream.moveTo(xx, yy); else stream.lineTo(xx, yy);
            }
            stream.stroke();
            float endY = bottom + (float)((equity[equity.length - 1] - low) / range * height);
            String finalValue = money(equity[equity.length - 1]);
            float finalLabelWidth = BOLD.getStringWidth(finalValue) / 1000 * 9;
            float finalLabelX = left + width - finalLabelWidth - 5;
            float finalLabelY = Math.min(top - 11, endY + 12);
            stream.setNonStrokingColor(equity[equity.length - 1] >= 0 ? new Color(228, 246, 237) : new Color(252, 228, 232));
            stream.addRect(finalLabelX - 4, finalLabelY - 3, finalLabelWidth + 8, 14); stream.fill();
            text(finalValue, finalLabelX, finalLabelY, 9, BOLD, pnlColor(finalValue));
            stream.setNonStrokingColor(BLUE); stream.addRect(left + width - 2.5f, endY - 2.5f, 5, 5); stream.fill();
            double[] drawdowns = new double[equity.length];
            double peak = 0;
            int peakIndex = 0, largestPeak = 0, largestTrough = 0;
            for (int i = 0; i < equity.length; i++) {
                if (equity[i] >= peak) { peak = equity[i]; peakIndex = i; }
                drawdowns[i] = equity[i] - peak;
                if (drawdowns[i] < drawdowns[largestTrough]) { largestPeak = peakIndex; largestTrough = i; }
            }
            if (maximumDrawdown > 0) {
                float peakX = left + width * largestPeak / closed.size();
                float troughX = left + width * largestTrough / closed.size();
                float peakY = bottom + (float)((equity[largestPeak] - low) / range * height);
                float troughY = bottom + (float)((equity[largestTrough] - low) / range * height);
                stream.setStrokingColor(new Color(209, 112, 124)); stream.setLineWidth(0.7f);
                stream.setLineDashPattern(new float[] {2, 2}, 0);
                stream.moveTo(peakX, peakY); stream.lineTo(troughX, peakY); stream.lineTo(troughX, troughY); stream.stroke();
                stream.setLineDashPattern(new float[0], 0);
                String label = "Largest drawdown";
                float labelWidth = FONT.getStringWidth(label) / 1000 * 7;
                float labelX = Math.max(left + 4, Math.min(left + width - labelWidth, (peakX + troughX) / 2 - labelWidth / 2));
                text(label, labelX, Math.min(top - 9, peakY + 10), 7, FONT, LOSS);
            }
            text("DRAWDOWN (INR)", left, drawdownTop + 10, 8, BOLD, LOSS);
            double drawdownScale = maximumDrawdown > 0 ? maximumDrawdown * 1.15 : 1;
            stream.setNonStrokingColor(new Color(252, 228, 232));
            stream.moveTo(left, drawdownTop);
            for (int i = 0; i < drawdowns.length; i++) {
                stream.lineTo(left + width * i / closed.size(), drawdownTop + (float)(drawdowns[i] / drawdownScale * drawdownHeight));
            }
            stream.lineTo(left + width, drawdownTop); stream.closePath(); stream.fill();
            stream.setStrokingColor(new Color(225, 211, 219)); stream.setLineWidth(0.5f);
            stream.moveTo(left, drawdownTop); stream.lineTo(left + width, drawdownTop); stream.stroke();
            stream.setStrokingColor(LOSS); stream.setLineWidth(1f);
            for (int i = 0; i < drawdowns.length; i++) {
                float xx = left + width * i / closed.size();
                float yy = drawdownTop + (float)(drawdowns[i] / drawdownScale * drawdownHeight);
                if (i == 0) stream.moveTo(xx, yy); else stream.lineTo(xx, yy);
            }
            stream.stroke();
            text("0", left - 14, drawdownTop - 3, 8, FONT);
            if (maximumDrawdown > 0) {
                String label = "-" + money(maximumDrawdown);
                float level = drawdownTop - (float)(maximumDrawdown / drawdownScale * drawdownHeight);
                text(label, left - 10 - FONT.getStringWidth(label) / 1000 * 8, level - 3, 8, FONT, LOSS);
            }
            y = drawdownBottom - 51;
            text("Trade closes in exit-time order (equally spaced) | Gross P&L before costs", 40, y, 8, FONT);
            y -= 16;
            text("First: " + date(closed.getFirst().exitedAt()) + "   Last: " + date(closed.getLast().exitedAt()), 40, y, 8, FONT);
            y -= 15;
        }

        @Override public void close() throws IOException { finish(); }
    }
}
