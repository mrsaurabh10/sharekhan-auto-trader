package org.com.sharekhan.analysis;
import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;import org.springframework.web.server.ResponseStatusException;
import java.time.*;import java.util.*;import static org.com.sharekhan.analysis.ThesisModels.*;import static org.springframework.http.HttpStatus.*;
@Service
public class ThesisService {
 private final ThesisStore store;private final SavedDailyAnalysis daily;private final FluencySnapshotStore sources;private final Clock clock;
 private static final Set<String> TYPES=Set.of("GROWTH_MOMENTUM","MEAN_REVERSION","PIVOT_BREAKOUT","OTHER_RESEARCH");
 @org.springframework.beans.factory.annotation.Autowired public ThesisService(ThesisStore store,SavedDailyAnalysis daily,FluencySnapshotStore sources){this(store,daily,sources,Clock.system(StockAnalysisService.IST));}
 ThesisService(ThesisStore store,SavedDailyAnalysis daily,FluencySnapshotStore sources,Clock clock){this.store=store;this.daily=daily;this.sources=sources;this.clock=clock;}
 private LocalDate today(){return LocalDate.now(clock);}
 public Book book(String owner){var entries=store.list(owner);return new Book(VERSION,today(),(int)entries.stream().filter(e->!terminal(e.state())&&!e.nextReview().isAfter(today())).count(),entries,rules());}
 public Entry get(String owner,String id){return store.read(owner,id).orElseThrow(()->new ResponseStatusException(NOT_FOUND,"Journal record not found"));}
 public List<SourceOption> sourceOptions(){var result=new ArrayList<SourceOption>();for(var s:sources.load())for(var c:s.candidates())result.add(new SourceOption(c.id(),c.symbol(),c.setupDate(),c.score(),s.marketGate()));return List.copyOf(result);}
 private Source source(String sourceId,String symbol){if(sourceId.isEmpty())return new Source("MANUAL_RESEARCH",null,symbol,null,null,null,null,null,null,null);
  for(var s:sources.load())for(var c:s.candidates())if(c.id().equals(sourceId)){if(!c.symbol().equals(symbol))throw new ResponseStatusException(BAD_REQUEST,"Source and symbol must match");return new Source(s.origin(),c.id(),c.symbol(),c.setupDate(),c.score(),s.marketGate(),c.sourceHash(),c.referenceClose(),c.referenceLow(),c);}
  throw new ResponseStatusException(BAD_REQUEST,"Saved source example unavailable; choose a current saved example or manual research");
 }
 @Transactional(readOnly=true)
 public Entry create(String owner,Create request){if(request==null)throw new ResponseStatusException(BAD_REQUEST,"Journal details required");String id=ThesisStore.id(request.requestId());
  String symbol=text(request.symbol(),40,1,"Symbol").toUpperCase(Locale.ROOT),type=text(request.thesisType(),40,1,"Thesis type"),sourceId=text(request.sourceId(),180,0,"Source ID");
  if(!TYPES.contains(type))throw new ResponseStatusException(BAD_REQUEST,"Unsupported research thesis type");
  String thesis=text(request.thesis(),2000,10,"Thesis"),entry=text(request.entryCondition(),1200,3,"Entry conditions"),invalidation=text(request.invalidation(),1200,3,"Invalidation");
  String fingerprint=StockDailyHistoryStore.digest(String.join("\u0000",symbol,type,sourceId,thesis,entry,invalidation,String.valueOf(request.nextReview())));
  var prior=store.read(owner,id);if(prior.isPresent()){if(!prior.get().creationFingerprint().equals(fingerprint))throw new ResponseStatusException(CONFLICT,"Request ID already used; start a new draft");return prior.get();}
  if(!symbol.matches("[A-Z0-9&.\\-]+")||daily.members().stream().noneMatch(m->m.symbol().equals(symbol)))throw new ResponseStatusException(BAD_REQUEST,"This journal version accepts saved NSE Nifty 200 symbols only");
  LocalDate review=reviewDate(request.nextReview());Instant now=clock.instant();var event=new Event(1,now,"CREATE",null,"IDEA","Research thesis created; no broker execution.",null,review,thesis,entry,invalidation,null,null);
  var result=new Entry(VERSION,id,"RESEARCH_THESIS",1,fingerprint,now,now,symbol,type,"IDEA",thesis,entry,invalidation,review,"NOT_REVIEWED",null,null,source(sourceId,symbol),List.of(event));
  try{return store.append(owner,result,0);}catch(ResponseStatusException e){if(e.getStatusCode()==CONFLICT){var winner=store.read(owner,id);if(winner.isPresent()&&winner.get().creationFingerprint().equals(fingerprint))return winner.get();}throw e;}
 }
 public Entry amend(String owner,String id,Amend r){if(r==null)throw new ResponseStatusException(BAD_REQUEST,"Amendment required");var e=current(owner,id,r.expectedRevision());nonterminal(e);
  String thesis=text(r.thesis(),2000,10,"Thesis"),entry=text(r.entryCondition(),1200,3,"Entry conditions"),invalidation=text(r.invalidation(),1200,3,"Invalidation"),note=text(r.note(),1500,3,"Reason for edit");LocalDate review=reviewDate(r.nextReview());
  return revise(owner,e,"AMEND",e.state(),note,e.reviewResult(),review,thesis,entry,invalidation,e.outcome(),e.lessons());
 }
 public Entry transition(String owner,String id,Transition r){if(r==null)throw new ResponseStatusException(BAD_REQUEST,"Transition required");var e=current(owner,id,r.expectedRevision());nonterminal(e);String target=text(r.target(),40,1,"State"),reason=text(r.reason(),1500,3,"Reason");
  boolean valid=target.equals("INVALIDATED")||e.state().equals("IDEA")&&target.equals("ENTRY_READY")||e.state().equals("ENTRY_READY")&&target.equals("TRACKING")||e.state().equals("TRACKING")&&target.equals("CLOSED");
  if(!valid)throw new ResponseStatusException(CONFLICT,"Research lifecycle must move forward: IDEA → ENTRY_READY → TRACKING → CLOSED, or invalidate");
  String outcome=e.outcome(),lessons=e.lessons();if(terminal(target)){outcome=text(r.outcome(),2000,3,"Research outcome" );lessons=text(r.lessons(),2000,3,"Lessons learned");}
  return revise(owner,e,"TRANSITION",target,reason,e.reviewResult(),e.nextReview(),e.thesis(),e.entryCondition(),e.invalidation(),outcome,lessons);
 }
 public Entry review(String owner,String id,Review r){if(r==null)throw new ResponseStatusException(BAD_REQUEST,"Review required");var e=current(owner,id,r.expectedRevision());nonterminal(e);String result=text(r.result(),20,1,"Review result");if(!Set.of("OK","WARN","REVIEW").contains(result))throw new ResponseStatusException(BAD_REQUEST,"Choose OK, WARN or REVIEW");return revise(owner,e,"REVIEW",e.state(),text(r.note(),2000,3,"Review note"),result,reviewDate(r.nextReview()),e.thesis(),e.entryCondition(),e.invalidation(),e.outcome(),e.lessons());}
 public Entry postmortem(String owner,String id,Postmortem r){if(r==null)throw new ResponseStatusException(BAD_REQUEST,"Postmortem required");var e=current(owner,id,r.expectedRevision());if(!terminal(e.state()))throw new ResponseStatusException(CONFLICT,"Close or invalidate the research thesis before its postmortem");return revise(owner,e,"POSTMORTEM",e.state(),text(r.note(),3000,10,"Postmortem note"),e.reviewResult(),e.nextReview(),e.thesis(),e.entryCondition(),e.invalidation(),e.outcome(),e.lessons());}
 private Entry current(String owner,String id,int revision){var e=get(owner,id);if(revision!=e.revision())throw new ResponseStatusException(CONFLICT,"Journal changed; reload before editing");return e;}
 private Entry revise(String owner,Entry e,String action,String state,String note,String result,LocalDate review,String thesis,String condition,String invalidation,String outcome,String lessons){Instant now=clock.instant();if(now.isBefore(e.updatedAt()))throw new ResponseStatusException(CONFLICT,"Server time precedes last journal revision; retry later");var history=new ArrayList<>(e.history());history.add(new Event(e.revision()+1,now,action,e.state(),state,note,result,review,thesis,condition,invalidation,outcome,lessons));var next=new Entry(VERSION,e.id(),e.kind(),e.revision()+1,e.creationFingerprint(),e.createdAt(),now,e.symbol(),e.thesisType(),state,thesis,condition,invalidation,review,result,outcome,lessons,e.source(),List.copyOf(history));return store.append(owner,next,e.revision());}
 private static boolean terminal(String s){return s.equals("CLOSED")||s.equals("INVALIDATED");}
 private static void nonterminal(Entry e){if(terminal(e.state()))throw new ResponseStatusException(CONFLICT,"Terminal research theses accept postmortem notes only");}
 private LocalDate reviewDate(LocalDate d){if(d==null)return today().plusDays(7);if(d.isBefore(today().minusYears(1))||d.isAfter(today().plusYears(1)))throw new ResponseStatusException(BAD_REQUEST,"Review date must be within one year of today");return d;}
 private static String text(String s,int max,int min,String name){String v=s==null?"":s.trim();if(v.length()<min||v.length()>max||v.chars().anyMatch(c->c==0||c<32&&c!='\n'&&c!='\r'&&c!='\t'))throw new ResponseStatusException(BAD_REQUEST,name+" must contain "+min+"–"+max+" characters");return v;}
 private static List<String> rules(){return List.of(
  "Selected Trader Memory Core methods adapted to a PRIVATE per-account NSE research journal. Research-only states IDEA → ENTRY_READY → TRACKING → CLOSED; any nonterminal may be INVALIDATED. TRACKING is not upstream ACTIVE and does not claim an actual broker position.",
  "Each signed-in account can read/edit only its own records. Stable admin/user account IDs resolve ownership on the server; client inputs cannot choose another owner. Admins do not get a cross-account journal view. Never paste credentials, tokens or account secrets into notes.",
  "Manual research supports saved NSE Nifty200 symbols. Drafts from the shared Setup Fluency book freeze original source candidate, date, score, market gate, references and input hash. Reconstructed/current-membership/adjustment limits remain; reference close/low are study values, not fill or stop orders.",
  "ENTRY_READY is your review status; it cannot bypass app entry/market gates or activate a strategy. TRACKING records following an idea. Closure/invalidation require an outcome and lessons; terminal theses accept additional postmortem notes, never reopening or silent edits.",
  "All writes require a signed-in USER/ADMIN session and CSRF. Expected revisions reject stale edits; immutable revision files provide history. Request IDs make identical create retries idempotent. No delete route, broker API, account-position sync, quantity, fill, P&L, notification or scheduled job is introduced.",
  "Private journal revisions stay on the existing persistent data mount; they are not pushed to the project Git repository or public skill audit. Review dates are reminders shown when opening the journal, with no background notification. Source snapshots remain unchanged; this is not full upstream thesis/YAML or portfolio integration.");}
}
