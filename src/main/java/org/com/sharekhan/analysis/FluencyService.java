package org.com.sharekhan.analysis;
import org.com.sharekhan.service.SharekhanHistoricalService.HistoricalCandle;
import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.*;
import java.time.*;import java.util.*;

/** Saved-archive learning studies. Reference moves are not fills, stop-adjusted P&L or live signals. */
@Service
public class FluencyService {
 public static final String VERSION="NSE_SETUP_FLUENCY_V1";
 private final SavedDailyAnalysis daily;private final MomentumResearchService momentum;private final SwingSessions calendar;private final FluencySnapshotStore store;private final Clock clock;
 private Report cached;
 public record Candidate(String id,String symbol,String securityId,LocalDate setupDate,int score,List<String> triggers,List<String> tags,
  double referenceClose,double referenceLow,String sourceHash,Instant archiveCapturedAt,boolean stockPlanQualified) { }
 public record Snapshot(String version,LocalDate setupDate,Instant reconstructedAt,LocalDate membershipDate,String origin,String marketGate,int universeCount,int coveredCount,double breadthAbove50,List<Candidate> candidates) { }
 public record Outcome(int horizon,String status,int observedSessions,LocalDate closeDate,Double forwardReturnPercent,Double mfePercent,Double maePercent,Boolean referenceStopTouched,LocalDate firstTouch,String tag,String reason) { }
 public record Study(Candidate candidate,boolean saved,String marketGate,Outcome day3,Outcome day5) { }
 public record Cohort(String group,String name,int records,int matured5,int pending5,int unavailable5,Double mean5Return,Double mean5Mfe,Double mean5Mae,Double referenceStopTouchRate,Double followThroughRate) { }
 public record Report(String version,LocalDate dataDate,Instant generatedAt,String status,int savedSnapshots,int previewSnapshots,int candidates,int matured3,int matured5,List<Study> studies,List<Cohort> cohorts,List<String> rules) { }
 public record Capture(int savedDates,int existingDates,int skippedDates,int savedCandidates) { }
 @org.springframework.beans.factory.annotation.Autowired
 public FluencyService(SavedDailyAnalysis daily,MomentumResearchService momentum,SwingSessions calendar,FluencySnapshotStore store){this(daily,momentum,calendar,store,Clock.system(StockAnalysisService.IST));}
 FluencyService(SavedDailyAnalysis daily,MomentumResearchService momentum,SwingSessions calendar,FluencySnapshotStore store,Clock clock){this.daily=daily;this.momentum=momentum;this.calendar=calendar;this.store=store;this.clock=clock;}
 record Inputs(LocalDate day,List<StockDailyHistoryStore.Member> members,Map<String,StockDailyHistoryStore.Saved> prices,Set<String> collisions) { }
 private Inputs inputs(){var day=calendar.expected(LocalDateTime.now(clock));var members=daily.members();var prices=new LinkedHashMap<String,StockDailyHistoryStore.Saved>();var ids=new HashMap<String,List<String>>();
  if(day!=null)for(var m:members){var p=daily.read(m.symbol(),day);prices.put(m.symbol(),p);if(p.securityId()!=null)ids.computeIfAbsent(p.securityId(),k->new ArrayList<>()).add(m.symbol());}
  var collisions=new HashSet<String>();ids.values().stream().filter(v->v.size()>1).forEach(collisions::addAll);return new Inputs(day,members,prices,collisions);
 }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=30)
 public synchronized Report report(){var day=calendar.expected(LocalDateTime.now(clock));if(cached!=null&&Objects.equals(cached.dataDate(),day)&&Duration.between(cached.generatedAt(),clock.instant()).getSeconds()<60)return cached;
  var input=inputs();var saved=store.load();var snapshots=new TreeMap<LocalDate,Snapshot>();var savedDates=new HashSet<LocalDate>();
  for(var s:saved)if(input.day()!=null&&!s.setupDate().isAfter(input.day())){snapshots.put(s.setupDate(),s);savedDates.add(s.setupDate());}
  for(var date:recentDates(input.day()))if(!snapshots.containsKey(date)){var s=reconstruct(input,date);if(!s.marketGate().equals("DATA_INCOMPLETE"))snapshots.put(date,s);}
  while(snapshots.size()>60)snapshots.pollFirstEntry();var studies=new ArrayList<Study>();
  for(var s:snapshots.descendingMap().values())for(var c:s.candidates()){
   var p=input.prices().get(c.symbol());boolean collision=input.collisions().contains(c.symbol());studies.add(new Study(c,savedDates.contains(s.setupDate()),s.marketGate(),outcome(c,p,input.day(),3,collision),outcome(c,p,input.day(),5,collision)));
  }
  studies.sort(Comparator.comparing((Study s)->s.candidate().setupDate()).reversed().thenComparing(s->s.candidate().symbol()));
  int countSaved=(int)snapshots.keySet().stream().filter(savedDates::contains).count(),countPreview=snapshots.size()-countSaved;
  cached=new Report(VERSION,input.day(),clock.instant(),input.day()==null?"CALENDAR_UNAVAILABLE":snapshots.isEmpty()?"DATA_INCOMPLETE":countPreview>0?"RECONSTRUCTED_PREVIEW":"SAVED_MODEL_BOOK",countSaved,countPreview,studies.size(),(int)studies.stream().filter(s->s.day3().status().equals("MATURED")).count(),(int)studies.stream().filter(s->s.day5().status().equals("MATURED")).count(),List.copyOf(studies),cohorts(studies),rules());return cached;
 }
 /** Admin explicitly saves research-only files; database transaction remains read-only. No timers or broker calls. */
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=30)
 public synchronized Capture capture(){var input=inputs();var existing=new HashSet<LocalDate>();for(var s:store.load())existing.add(s.setupDate());int saved=0,skipped=0,already=0,candidates=0;
  for(var day:recentDates(input.day())){if(existing.contains(day)){already++;continue;}var snapshot=reconstruct(input,day);if(snapshot.marketGate().equals("DATA_INCOMPLETE")){skipped++;continue;}if(store.save(snapshot)){saved++;candidates+=snapshot.candidates().size();}else already++;}
  cached=null;return new Capture(saved,already,skipped,candidates);
 }
 List<LocalDate> recentDates(LocalDate day){var dates=new ArrayList<LocalDate>();if(day==null)return dates;for(var d=day;dates.size()<20&&calendar.supported(d);d=d.minusDays(1))if(calendar.regular(d))dates.add(d);return dates;}
 Snapshot reconstruct(Inputs input,LocalDate day){var rows=new ArrayList<MomentumResearchService.Row>();var candidates=new ArrayList<Candidate>();
  for(var m:input.members()){if(input.collisions().contains(m.symbol()))continue;var p=input.prices().get(m.symbol());if(p==null)continue;
   var r=momentum.evaluate(p,day,calendar.next(day));rows.add(r);
   if(!r.status().equals("WATCH_ONLY")&&!r.status().equals("CONDITIONAL_BUY"))continue;
   var bars=p.candles().stream().filter(b->!b.date().isAfter(day)).toList();var last=bars.stream().filter(b->b.date().equals(day)).findFirst().orElseThrow();
   var tags=new ArrayList<String>();if(r.closeLocation()>=80)tags.add("CLOSE_NEAR_HIGH");if(r.closeLocation()<50)tags.add("WEAK_CLOSE");if(r.volumeRatio()>=2)tags.add("HIGH_VOLUME");if(r.baseDays()>=3&&r.baseDays()<=20)tags.add("CONTROLLED_BASE");double risk=100*(last.close()-last.low())/last.close();if(risk<=4)tags.add("COMPACT_REFERENCE_RISK");if(risk>6)tags.add("WIDE_REFERENCE_RISK");
   candidates.add(new Candidate(VERSION+":"+m.symbol()+":"+day,m.symbol(),p.securityId(),day,r.score(),r.triggers(),List.copyOf(tags),last.close(),last.low(),StockDailyHistoryStore.digest(bars.toString()),p.capturedAt(),r.plan()!=null));
  }
  long covered=rows.stream().filter(r->!r.status().equals("UNAVAILABLE")).count();double breadth=covered==0?0:rows.stream().filter(r->!r.status().equals("UNAVAILABLE")&&r.above50()).count()/(double)covered;
  String gate=input.members().size()!=200||covered<160||calendar.next(day)==null?"DATA_INCOMPLETE":breadth<.5?"MARKET_CAUTION":"READY";
  return new Snapshot(VERSION,day,clock.instant(),input.members().isEmpty()?null:input.members().getFirst().snapshotDate(),"SAVED_ARCHIVE_RECONSTRUCTION",gate,input.members().size(),(int)covered,breadth,List.copyOf(candidates));
 }
 Outcome outcome(Candidate c,StockDailyHistoryStore.Saved input,LocalDate through,int horizon,boolean collision){
  if(collision||input==null||!Objects.equals(c.securityId(),input.securityId()))return unavailable(horizon,"DATA_REVIEW","Saved instrument identity missing/changed.",0);
  if(through==null||c.referenceClose()<=0||c.referenceLow()<=0||c.referenceLow()>=c.referenceClose())return unavailable(horizon,"DATA_REVIEW","Invalid reference or calendar.",0);
  var bars=new TreeMap<LocalDate,HistoricalCandle>();var invalid=new HashSet<LocalDate>();
  for(var b:input.candles()){if(b==null||b.date()==null)return unavailable(horizon,"DATA_REVIEW","Malformed candle date.",0);if(b.date().isBefore(c.setupDate())||b.date().isAfter(through))continue;var previous=bars.putIfAbsent(b.date(),b);if(!StockAnalysisService.valid(b)||b.volume()==null||b.volume()<0||previous!=null&&!previous.equals(b))invalid.add(b.date());}
  var setup=bars.get(c.setupDate());if(invalid.contains(c.setupDate()))return unavailable(horizon,"DATA_REVIEW","Invalid/conflicting setup OHLCV.",0);if(setup==null)return unavailable(horizon,"DATA_GAP","Setup-day candle no longer available.",0);
  if(Math.abs(setup.close()-c.referenceClose())>.000001||Math.abs(setup.low()-c.referenceLow())>.000001)return unavailable(horizon,"DATA_REVIEW","Setup price changed since frozen snapshot; adjustment review required.",0);
  var future=new ArrayList<HistoricalCandle>();LocalDate d=c.setupDate();double previousClose=c.referenceClose();
  for(int i=0;i<horizon;i++){d=calendar.next(d);if(d==null)return unavailable(horizon,"CALENDAR_UNAVAILABLE","Unsupported/special session boundary; no compressed horizon.",future.size());if(d.isAfter(through))return unavailable(horizon,"PENDING","Not enough completed future sessions.",future.size());
   if(invalid.contains(d))return unavailable(horizon,"DATA_REVIEW","Invalid/conflicting forward OHLCV.",future.size());var b=bars.get(d);if(b==null)return unavailable(horizon,"DATA_GAP","Missing expected session "+d+"; no substitution.",future.size());if(Math.abs(b.close()/previousClose-1)>.30)return unavailable(horizon,"DATA_REVIEW","Forward price discontinuity requires adjustment review.",future.size());future.add(b);previousClose=b.close();}
  var last=future.getLast();double fwd=100*(last.close()/c.referenceClose()-1),mfe=100*(future.stream().mapToDouble(HistoricalCandle::high).max().orElseThrow()/c.referenceClose()-1),mae=100*(future.stream().mapToDouble(HistoricalCandle::low).min().orElseThrow()/c.referenceClose()-1);
  LocalDate touch=future.stream().filter(b->b.low()<=c.referenceLow()).map(HistoricalCandle::date).findFirst().orElse(null);String tag=classify(fwd,mfe,mae,touch!=null);
  return new Outcome(horizon,"MATURED",horizon,last.date(),fwd,mfe,mae,touch!=null,touch,tag,"Full-window reference study, including moves after any reference-stop touch; not executed or stop-adjusted P&L.");
 }
 static String classify(double fwd,double mfe,double mae,boolean stop){return stop?"FAILED_STOP":mfe>=12||fwd>=8?"STRONG_WINNER":mfe>=6||fwd>=4?"WORKED":fwd<=-2?"FAILED_FADE":mae<=-5&&fwd<2?"CHOPPY_FAILURE":"NEUTRAL";}
 private static Outcome unavailable(int horizon,String status,String reason,int available){return new Outcome(horizon,status,available,null,null,null,null,null,null,status,reason);}
 static List<Cohort> cohorts(List<Study> studies){var groups=new TreeMap<String,List<Study>>();for(var s:studies){var c=s.candidate();var keys=new ArrayList<String>();keys.add("ALL|ALL");keys.add("PRIMARY_TRIGGER|"+c.triggers().getFirst());keys.add("SCORE|"+(c.score()>=80?"80_PLUS":"55_TO_79"));keys.add("MARKET_GATE|"+s.marketGate());keys.add("STORAGE|"+(s.saved()?"SAVED":"PREVIEW"));for(var tag:c.tags())keys.add("TAG|"+tag);for(var k:keys)groups.computeIfAbsent(k,key->new ArrayList<>()).add(s);}
  var result=new ArrayList<Cohort>();for(var e:groups.entrySet()){var rows=e.getValue();var mature=rows.stream().map(Study::day5).filter(o->o.status().equals("MATURED")).toList();int n=mature.size(),pending=(int)rows.stream().filter(s->s.day5().status().equals("PENDING")).count();var parts=e.getKey().split("\\|",2);
   result.add(new Cohort(parts[0],parts[1],rows.size(),n,pending,rows.size()-n-pending,n<5?null:mature.stream().mapToDouble(Outcome::forwardReturnPercent).average().orElseThrow(),n<5?null:mature.stream().mapToDouble(Outcome::mfePercent).average().orElseThrow(),n<5?null:mature.stream().mapToDouble(Outcome::maePercent).average().orElseThrow(),n<5?null:100.0*mature.stream().filter(Outcome::referenceStopTouched).count()/n,n<5?null:100.0*mature.stream().filter(o->o.tag().equals("WORKED")||o.tag().equals("STRONG_WINNER")).count()/n));
  }return List.copyOf(result);
 }
 private static List<String> rules(){return List.of(
  "Selected Stockbee Setup Fluency methods using the app's native NSE Momentum Burst rubric. Reconstruct last20 regular setup sessions from the CURRENT Nifty200 membership; not historical point-in-time membership or original live screener reports. Survivorship/selection bias and adjustment UNKNOWN apply.",
  "Only ≥55 watch/conditional candidates are studied, using only candles through each setup date. Historical market gate and stock-level plan qualification are retained separately; watches under MARKET_CAUTION were never published buy plans. No automatic strategy promotion or gate changes.",
  "Study reference =setup-day CLOSE; reference stop =setup-day LOW. These are not next-session plan entries/ATR stops or actual fills. 3/5-session close return, maximum high excursion and minimum low excursion use the full future window, including after any stop touch. No stopped-trade P&L, costs, slippage or execution claim.",
  "Exact next3/5 verified regular NSE sessions required. Missing dates, changed setup prices/identity, invalid OHLCV or >30% forward close discontinuities block metrics; incomplete future windows remain PENDING. Unsupported special/calendar boundaries are not silently skipped.",
  "Labels: reference-stop touch takes precedence (FAILED_STOP); otherwise MFE≥12% or close≥8%=STRONG_WINNER; MFE≥6% or close≥4%=WORKED; close≤−2%=FAILED_FADE; MAE≤−5% and close<2%=CHOPPY_FAILURE; else NEUTRAL. Descriptive research labels, not actual winning trades.",
  "Preview is reconstructed on demand. Admin Save research snapshots freezes date/version files on the existing persistent data mount; duplicate saves never overwrite them. Database remains read-only; no new schema or scheduled job. Latest60 saved/preview setup dates shown; last20 eligible dates can be saved manually.",
  "Cohorts use only valid matured5-session outcomes; metrics withheld below5 samples. Pending/unavailable counts stay visible. Tags overlap, so cohort counts are not additive. Follow-through rate counts WORKED/STRONG_WINNER, not realised trade win rate. Small overlapping samples are not independent evidence or validated edge.");}
}
