# Analytics PDF report

In the Analytics panel, select the source(s), date range, symbol and intraday filter, then choose **Download PDF**. The download reads the current filters and includes the full matching position list, independent of the dashboard's ten recent closed trades.

The PDF contains:

- Gross and effective realized P&L, brokerage, taxes and total costs.
- Closed/winning/losing/breakeven counts, accuracy (gross win rate), profit factor, average win/loss and best trade.
- Maximum single-trade gross loss and maximum gross/effective drawdown.
- Blue gross realized equity curve with a separate red drawdown panel, largest-drawdown annotation, date/time ticks and final P&L/drawdown callouts, starting from zero and ordered by exit time, then trade ID. Effective P&L remains in the metrics and position table.
- Peak funds in use, open quantity, and rejected/failed attempt counts.
- A landscape table grouped by entry date, with gross/effective day P&L in each date heading, repeated headers, daily subtotals and each matching closed/open position's ID, symbol, source, side, status, quantity, entry/exit prices and timestamps, gross/effective P&L, costs and exit reason.

Closed positions match the selected exit-date range; currently open positions match the trigger-date range, consistent with existing analytics. Open unrealized P&L is excluded. Failed/rejected attempts appear as counts, rather than positions. Amounts are INR and report generation time is IST. Drawdown is the greatest fall from a cumulative realized P&L peak, including the initial zero baseline; it is not an intratrade or mark-to-market measure. Costs use the existing analytics calculation and stored-cost fallback.

`GET /api/analytics/trades/report.pdf` accepts the same user, date, source (repeatable or comma-separated), symbol, broker credential, intraday and scope filters as `/api/analytics/trades`. It applies the same session user and scope restrictions and returns an `application/pdf` attachment with `Cache-Control: no-store`. No AI call, remote renderer or browser runtime is required.

Validation: `mvn -Dtest=TradeAnalyticsServiceTest,TradeAnalyticsControllerTest,TradeAnalyticsPdfServiceTest,TriggeredTradeSetupRepositoryAnalyticsTest test`. The PDF test writes a synthetic multi-page sample and rendered review images under `target/pdf-review/`.
