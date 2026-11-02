package org.com.sharekhan.analysis;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.net.URI;
import java.util.*;

/** Selected Stock Analysis methods. Saved NSE prices and explicitly unverified human evidence. */
@Service
public class CompanyAnalysisService {
 public static final String VERSION="NSE_COMPANY_ASSESSMENT_V1";
 private final SavedDailyAnalysis daily;
 private final WeeklyTechnicalService weekly;
 public CompanyAnalysisService(SavedDailyAnalysis daily,WeeklyTechnicalService weekly){this.daily=daily;this.weekly=weekly;}
 public record Financials(String symbol,String sourceUrl,LocalDate publishedDate,LocalDate periodEnd,String basis,
   Double eps,Double bookValuePerShare,Double revenue,Double priorRevenue,Double netIncome,Double operatingCashFlow,
   Double capex,Double debt,Double equity) { }
 public record Metric(String name,Double value,String unit,String explanation) { }
 public record Report(String version,String symbol,String exchange,String isin,String currency,String timezone,Instant generatedAt,
  String status,String conclusion,StockAnalysisService.Report daily,WeeklyTechnicalService.Report weekly,
  String financialStatus,Financials financials,List<Metric> metrics,List<String> positives,List<String> risks,List<String> missing,List<String> rules) { }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=25)
 public Report report(String requested,Financials financials){
  String symbol=requested==null?"":requested.trim().toUpperCase(Locale.ROOT);
  if(!symbol.matches("[A-Z0-9&.\\-]{1,40}"))throw bad("Invalid saved NSE symbol");
  var member=daily.members().stream().filter(m->m.symbol().equals(symbol)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"No saved Nifty 200 stock for symbol"));
  var prices=daily.analyze("NC",symbol,Instant.now());var chart=weekly.report(symbol);
  return assemble(member,prices,chart,financials);
 }
 Report assemble(StockDailyHistoryStore.Member member,StockAnalysisService.Report prices,WeeklyTechnicalService.Report chart,Financials f){
  LocalDate cutoff=prices.asOf()==null?null:prices.asOf().toLocalDate();
  boolean usable="HISTORICAL".equals(prices.dataStatus()) && !"UNAVAILABLE".equals(chart.status());
  var metrics=new ArrayList<Metric>();var positive=new ArrayList<String>();var risks=new ArrayList<String>();
  var missing=new ArrayList<String>(List.of("Company business/management quality and competitive position", "Verified recent earnings, news and event calendar", "Comparable peer valuations and historical valuation ranges", "Multi-year financial trends, corporate actions and share-count reconciliation"));
  Double close=usable&&!prices.candles().isEmpty()?prices.candles().getLast().close():null;
  String fs="MISSING";
  if(f==null)missing.add("Dated company financial statements: add optional annual evidence below");
  else {
   validate(member.symbol(),f,cutoff);
   fs=java.time.temporal.ChronoUnit.DAYS.between(f.periodEnd(),cutoff)>540?"STALE_USER_SUPPLIED":"USER_SUPPLIED_UNVERIFIED";
   if(fs.startsWith("STALE"))risks.add("Annual period is more than 540 days before the price cutoff. Financial ratios withheld.");
   else {
    metrics.add(metric("Annual P/E",close!=null&&positive(f.eps())?close/f.eps():null,"×","Saved daily close / positive annual diluted EPS; not TTM or forward P/E."));
    metrics.add(metric("Price / book",close!=null&&positive(f.bookValuePerShare())?close/f.bookValuePerShare():null,"×","Saved daily close / positive book value per share."));
    Double growth=positive(f.priorRevenue())&&f.revenue()!=null?100*(f.revenue()/f.priorRevenue()-1):null;
    metrics.add(metric("Annual revenue growth",growth,"%","Two consecutive full fiscal years, same reporting basis and INR crore units."));
    metrics.add(metric("Net margin",positive(f.revenue())&&f.netIncome()!=null?100*f.netIncome()/f.revenue():null,"%","Annual net income / annual revenue."));
    Double fcf=f.operatingCashFlow()!=null&&f.capex()!=null?f.operatingCashFlow()-f.capex():null;
    metrics.add(metric("Free cash flow",fcf,"INR crore","Operating cash flow less capital expenditure (capex supplied as positive outflow). Not a bank/insurer valuation measure."));
    metrics.add(metric("Debt / equity",positive(f.equity())&&f.debt()!=null?f.debt()/f.equity():null,"×","Year-end debt / positive year-end equity; sector context required."));
    metrics.add(metric("Cash conversion",positive(f.netIncome())&&f.operatingCashFlow()!=null?f.operatingCashFlow()/f.netIncome():null,"×","Annual operating cash flow / positive annual net income."));
    if(growth!=null&&growth>0)positive.add("User-supplied annual revenue increased versus the previous fiscal year.");
    if(fcf!=null&&fcf>0)positive.add("User-supplied operating cash flow exceeds capital expenditure.");
    if(f.netIncome()!=null&&f.netIncome()<=0)risks.add("User-supplied net income is nonpositive; earnings-based multiples may be unsuitable.");
    if(f.equity()!=null&&f.equity()<=0)risks.add("User-supplied equity is nonpositive; debt/equity and price/book require review.");
    if(fcf!=null&&fcf<0)risks.add("User-supplied free cash flow is negative; investigate investment needs and funding.");
    if(f.eps()!=null&&f.eps()<=0)risks.add("Annual EPS is nonpositive; annual P/E is unavailable.");
   }
   missing.add("Independent verification of user-supplied source, dates, symbol, basis and figures");
   if(metrics.stream().anyMatch(m->m.value()==null))missing.add("One or more financial ratio inputs are missing, nonpositive or price evidence is unavailable");
  }
  if(!usable)risks.add("Daily/weekly data is stale, incomplete or unavailable. Price-based financial multiples are withheld.");
  if("UPTREND".equals(chart.trend())&&usable)positive.add("Closed-week technical conditions align with the existing uptrend methodology.");
  if("DOWNTREND".equals(chart.trend()))risks.add("Closed-week technical conditions align with the existing downtrend methodology.");
  risks.add("Saved-price corporate-action adjustment is unverified; financial per-share units must match the price share basis.");
  return new Report(VERSION,member.symbol(),"NSE cash",member.isin(),"INR","Asia/Kolkata",Instant.now(),"REVIEW_REQUIRED",
   "Company assessment requires human review. No supported fair value, price target or Buy/Hold/Sell rating with the available evidence.",prices,chart,fs,f,List.copyOf(metrics),List.copyOf(positive),List.copyOf(risks),List.copyOf(missing),rules());
 }
 static Metric metric(String n,Double v,String u,String e){return new Metric(n,v!=null&&Double.isFinite(v)?v:null,u,e);}
 static boolean positive(Double d){return d!=null&&d>0;}
 static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
 static void validate(String symbol,Financials f,LocalDate cutoff){
  if(!symbol.equals(f.symbol()))throw bad("Evidence symbol must match the selected stock exactly");
  if(cutoff==null)throw bad("A dated price cutoff is required before assessing financial evidence");
  if(f.sourceUrl()==null||f.sourceUrl().length()>1000)throw bad("A public HTTPS company/exchange filing URL is required");
  try{URI u=URI.create(f.sourceUrl());if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getFragment()!=null||u.getPort()!=-1)throw new IllegalArgumentException();}catch(Exception e){throw bad("Use a public HTTPS filing URL without credentials, port or fragment");}
  if(f.periodEnd()==null||f.publishedDate()==null||f.periodEnd().getYear()<2000||f.publishedDate().isBefore(f.periodEnd())||f.publishedDate().isAfter(cutoff))throw bad("Annual period end must precede publication; publication cannot follow the price cutoff");
  if(!Set.of("CONSOLIDATED_ANNUAL","STANDALONE_ANNUAL").contains(f.basis()==null?"":f.basis()))throw bad("Choose one consistent annual reporting basis");
  var values=Arrays.asList(f.eps(),f.bookValuePerShare(),f.revenue(),f.priorRevenue(),f.netIncome(),f.operatingCashFlow(),f.capex(),f.debt(),f.equity());
  if(values.stream().allMatch(Objects::isNull))throw bad("Supply at least one annual financial figure");
  for(var v:values)if(v!=null&&(!Double.isFinite(v)||Math.abs(v)>1e12))throw bad("Financial figures must be finite and within supported INR units");
  for(var v:Arrays.asList(f.revenue(),f.priorRevenue(),f.capex(),f.debt()))if(v!=null&&v<0)throw bad("Revenue, capex outflow and debt must be nonnegative");
 }
 static List<String> rules(){return List.of(
  "Selected US Stock Analysis methods adapted to saved NSE Nifty 200 cash stocks. BSE and US coverage unavailable. INR per-share prices; statement totals INR crore. No new external data feed or broker request.",
  "Daily indicators use daily.asOf; weekly references use weekly.weeklyThrough, which can be an earlier closed week. The existing 200-DAY reference remains alongside 20/50-week averages. Range levels and chart zones are observations, not fair value or executable targets.",
  "Optional financial evidence is human entered and never fetched or independently verified. Source URL is a reference only. Use one company, same annual consolidated/standalone basis, consecutive full fiscal years for growth, positive capex outflow, matching split-adjusted per-share units. Financial/insurance businesses require sector-specific review.",
  "Publication must not follow the daily price cutoff. Annual period older than 540 days is stale and all financial ratios are withheld. Losses or nonpositive denominators never create misleading earnings/book/debt multiples. Missing data is unavailable, never zero.",
  "Evidence submissions are calculated in memory and are not saved, journalled, cached, logged by this feature or shared with another account. Download is an explicit local browser action. Review source contents before submitting URLs. No source URL is requested by the server.",
  "No composite investment score, company quality certification, prediction, analyst consensus, news feed, peer discount or Buy/Hold/Sell recommendation is inferred from price alone. Human verification, event checks, portfolio review and costs remain outstanding. No orders, strategy activation, schema or scheduled jobs.");}
}
