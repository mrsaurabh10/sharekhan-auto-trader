package org.com.sharekhan.analysis;
import com.fasterxml.jackson.databind.ObjectMapper;import org.springframework.stereotype.Component;import org.springframework.beans.factory.annotation.Value;
import java.nio.file.*;import java.util.*;import static org.com.sharekhan.analysis.ThesisModels.*;
import org.springframework.web.server.ResponseStatusException;import static org.springframework.http.HttpStatus.*;
/** Account-scoped immutable revisions on the persistent mount. No deletion or cross-account admin view. */
@Component
public class ThesisStore {
 private final Path directory;private final ObjectMapper mapper;
 private record Stored(String ownerHash,Entry entry){}
 public ThesisStore(ObjectMapper mapper,@Value("${app.thesis.directory:/app/data/trader-memory}")String directory){this.mapper=mapper;this.directory=Path.of(directory).resolve(VERSION);}
 static String id(String id){try{if(id==null||!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();return id;}catch(Exception e){throw new ResponseStatusException(BAD_REQUEST,"Invalid journal ID");}}
 private String ownerHash(String owner){if(owner==null||!owner.matches("(?:ADMIN|USER):[1-9][0-9]{0,18}"))throw new IllegalArgumentException("Invalid account owner");return StockDailyHistoryStore.digest(owner);}
 private Path account(String owner){return directory.resolve(ownerHash(owner));}
 public synchronized Optional<Entry> read(String owner,String id){Path folder=account(owner).resolve(id(id));if(!Files.exists(folder))return Optional.empty();
  try(var files=Files.list(folder)){var revisions=files.filter(p->p.getFileName().toString().matches("[0-9]{6}\\.json")).sorted().toList();if(revisions.isEmpty())return Optional.empty();if(revisions.size()>200)throw new IllegalStateException("Too many journal revisions");
   // No missing revisions can be silently skipped.
   for(int i=0;i<revisions.size();i++)if(!revisions.get(i).getFileName().toString().equals(String.format(java.util.Locale.ROOT,"%06d.json",i+1)))throw new IllegalStateException("Journal revision gap");
   var path=revisions.getLast();if(Files.size(path)>1_000_000)throw new IllegalStateException("Journal record too large");var stored=mapper.readValue(Files.readAllBytes(path),Stored.class);var entry=stored.entry();
   if(!Objects.equals(stored.ownerHash(),ownerHash(owner))||!entry.id().equals(id)||!entry.version().equals(VERSION)||entry.revision()!=revisions.size()||entry.history().size()!=entry.revision())throw new IllegalStateException("Journal record integrity mismatch");validate(entry);return Optional.of(entry);
  }catch(ResponseStatusException e){throw e;}catch(Exception e){throw new IllegalStateException("Unable to read private journal",e);}
 }
 public synchronized List<Entry> list(String owner){Path folder=account(owner);if(!Files.exists(folder))return List.of();
  try(var files=Files.list(folder)){var ids=files.filter(Files::isDirectory).map(p->p.getFileName().toString()).filter(n->n.matches("[a-f0-9-]{36}")).toList();if(ids.size()>500)throw new IllegalStateException("Journal capacity requires review");var entries=new ArrayList<Entry>();for(var id:ids)read(owner,id).ifPresent(entries::add);entries.sort(Comparator.comparing(Entry::updatedAt).reversed().thenComparing(Entry::id));return List.copyOf(entries);
  }catch(ResponseStatusException e){throw e;}catch(Exception e){throw new IllegalStateException("Unable to list private journal",e);}
 }
 public synchronized Entry append(String owner,Entry entry,int expected){
  validate(entry);id(entry.id());if(entry.revision()!=expected+1||entry.revision()>200||!entry.version().equals(VERSION))throw new ResponseStatusException(CONFLICT,"Invalid journal revision");
  var current=read(owner,entry.id());if(current.map(Entry::revision).orElse(0)!=expected)throw new ResponseStatusException(CONFLICT,"Journal changed; reload before editing");
  if(expected==0&&list(owner).size()>=500)throw new ResponseStatusException(CONFLICT,"Journal limit reached; retain records for review");
  Path temp=null;try{var folder=account(owner).resolve(entry.id());Files.createDirectories(folder);var target=folder.resolve(String.format(java.util.Locale.ROOT,"%06d.json",entry.revision()));byte[] bytes=mapper.writeValueAsBytes(new Stored(ownerHash(owner),entry));if(bytes.length>1_000_000)throw new IllegalStateException("Journal record too large");temp=Files.createTempFile(folder,".revision-",".tmp");Files.write(temp,bytes);
   try{Files.createLink(target,temp);}catch(FileAlreadyExistsException conflict){throw new ResponseStatusException(CONFLICT,"Journal changed; reload before editing");}return entry;
  }catch(ResponseStatusException e){throw e;}catch(Exception e){throw new IllegalStateException("Unable to save private journal",e);}finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(Exception ignored){}}
 }
 static void validate(Entry e){
  if(e==null||!VERSION.equals(e.version())||!"RESEARCH_THESIS".equals(e.kind())||e.revision()<1||e.revision()>200||e.history()==null||e.history().size()!=e.revision()||e.createdAt()==null||e.updatedAt()==null||e.nextReview()==null||e.source()==null||!Objects.equals(e.symbol(),e.source().symbol())||e.creationFingerprint()==null||!e.creationFingerprint().matches("[a-f0-9]{64}"))throw new IllegalStateException("Invalid journal contract");
  if(!Set.of("GROWTH_MOMENTUM","MEAN_REVERSION","PIVOT_BREAKOUT","OTHER_RESEARCH").contains(e.thesisType())||e.symbol()==null||!e.symbol().matches("[A-Z0-9&.\\-]{1,40}"))throw new IllegalStateException("Invalid journal classification");
  String previous=null;java.time.Instant time=null;
  for(int i=0;i<e.history().size();i++){var event=e.history().get(i);if(event==null||event.revision()!=i+1||event.at()==null||time!=null&&event.at().isBefore(time)||!Objects.equals(previous,event.fromState()))throw new IllegalStateException("Invalid journal history");
   if(i==0){if(!event.action().equals("CREATE")||!event.toState().equals("IDEA"))throw new IllegalStateException("Invalid journal origin");}
   else switch(event.action()){
    case "AMEND","REVIEW" -> {if(Set.of("CLOSED","INVALIDATED").contains(previous)||!previous.equals(event.toState()))throw new IllegalStateException("Invalid research edit");}
    case "POSTMORTEM" -> {if(!Set.of("CLOSED","INVALIDATED").contains(previous)||!previous.equals(event.toState()))throw new IllegalStateException("Invalid postmortem");}
    case "TRANSITION" -> {boolean allowed=!Set.of("CLOSED","INVALIDATED").contains(previous)&&(event.toState().equals("INVALIDATED")||previous.equals("IDEA")&&event.toState().equals("ENTRY_READY")||previous.equals("ENTRY_READY")&&event.toState().equals("TRACKING")||previous.equals("TRACKING")&&event.toState().equals("CLOSED"));if(!allowed)throw new IllegalStateException("Invalid research transition");}
    default -> throw new IllegalStateException("Invalid journal event");
   }
   if(event.thesis()==null||event.thesis().length()<10||event.thesis().length()>2000||event.entryCondition()==null||event.entryCondition().length()<3||event.entryCondition().length()>1200||event.invalidation()==null||event.invalidation().length()<3||event.invalidation().length()>1200||event.nextReview()==null||event.note()==null)throw new IllegalStateException("Incomplete journal event");
   if(Set.of("CLOSED","INVALIDATED").contains(event.toState())&&(event.outcome()==null||event.outcome().length()<3||event.lessons()==null||event.lessons().length()<3))throw new IllegalStateException("Incomplete terminal research outcome");previous=event.toState();time=event.at();
  }
  var last=e.history().getLast();if(!e.history().getFirst().at().equals(e.createdAt())||!last.at().equals(e.updatedAt())||!last.toState().equals(e.state())||!Objects.equals(last.thesis(),e.thesis())||!Objects.equals(last.entryCondition(),e.entryCondition())||!Objects.equals(last.invalidation(),e.invalidation())||!Objects.equals(last.nextReview(),e.nextReview())||!Objects.equals(last.outcome(),e.outcome())||!Objects.equals(last.lessons(),e.lessons()))throw new IllegalStateException("Journal head does not match history");
 }

}
