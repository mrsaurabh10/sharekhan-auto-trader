# NSE company assessment

Stock Analysis → Company assessment adapts selected methods from the installed
US Stock Analysis skill. This is an NSE Nifty 200 saved-data variant, not complete
US/BSE coverage or an automated fundamental research service.

The initial assessment combines saved daily price, trend, SMA20/50/200, RSI14 and
previous 20-bar range with closed-week context. Daily and weekly cutoffs are shown
separately. The user-requested **200-day SMA** remains alongside 20/50-week SMAs.
All existing daily/weekly data-quality caveats are visible. No live broker request.

Optional annual evidence includes an HTTPS filing reference, publication date,
annual period end, consolidated/standalone basis, diluted EPS and book value in
INR/share, and revenue/prior-year revenue/net income/operating cash flow/capex/
debt/equity in INR crore. The user confirms company, periods, units and share basis.
The URL is displayed as text and never fetched, previewed or automatically opened.
Figures and source contents are **user supplied and unverified**, not an authenticated
statement feed. Existing Sharekhan price services do not supply these statements.

Calculations: annual P/E = saved daily close / positive annual EPS (not TTM/forward);
P/B = close / positive book value; revenue growth = (current/prior - 1) ×100 for
positive prior revenue; margin = net income/revenue ×100; FCF = CFO minus positive
capex outflow; debt/equity uses positive equity; cash conversion = CFO/positive net
income. Blank values remain null; signed income/CFO/equity/EPS are permitted.
Nonfinite or absolute values above 1e12, negative revenue/debt/capex, invalid basis,
symbol mismatch or publication after the saved daily cutoff are rejected. Annual
period >540 days before the cutoff withholds all financial metrics. Unusable daily
or weekly input withholds price multiples, without suppressing independent annual
margin/FCF calculations. Banks/insurers require sector-specific review.

Overall assessment always REVIEW_REQUIRED. Positives/risks are narrow observations,
not a company score, moats, fair value, price targets, Buy/Hold/Sell recommendation
or permission to trade. Missing business/management, events, independent source
verification, peer valuations and multi-year/share-count checks remain explicit.

## API and privacy

USER/ADMIN routes, normal authentication, no-store responses:

- GET `/api/stock-analysis/company?symbol=RELIANCE`
- GET `/api/stock-analysis/company/symbols`
- POST `/api/stock-analysis/company` with Financials JSON and existing CSRF token.

GET uses saved PostgreSQL data; POST calculates in memory under a read-only
transaction. No statement persistence, shared evidence cache, journal write,
request-body logging by this feature, URL requests, DB migration, scheduler, account
change or order dependency. Evidence remains in the current document's form until
cleared, stock change or page exit; it is not browser storage. Local JSON export
requires an explicit Download click and includes the supplied evidence and limits.
The separate private thesis journal is unchanged. A refresh reloads price-only
assessment; the input form is retained for explicit resubmission.

Tests cover ratios, missing/loss/zero/stale data, invalid symbol/URL/date/basis/numbers,
saved-only routing, USER/ADMIN access, anonymous/other-role denial and CSRF. Browser
checks use intercepted synthetic data, test explicit submission, no automatic POST,
clearing on stock change, safe text, export, mobile width and failed-read clearing.
No test is an authenticated production financial evidence submission.

See [MIT attribution](licenses/tradermonty-stock-analysis-MIT.txt).
