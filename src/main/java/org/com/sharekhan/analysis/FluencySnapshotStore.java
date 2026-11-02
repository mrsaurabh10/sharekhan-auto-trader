package org.com.sharekhan.analysis;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;import org.springframework.beans.factory.annotation.Value;
import java.nio.file.*;import java.time.*;import java.util.*;
/** Immutable public-market research snapshots on the existing persistent data mount. */
@Component
public class FluencySnapshotStore {
 private final Path directory;private final ObjectMapper mapper;
 public FluencySnapshotStore(ObjectMapper mapper,@Value("${app.fluency.directory:/app/data/setup-fluency}")String directory){this.mapper=mapper;this.directory=Path.of(directory).resolve(FluencyService.VERSION);}
 public synchronized List<FluencyService.Snapshot> load(){
  if(!Files.exists(directory))return List.of();
  try(var paths=Files.list(directory)){var result=new ArrayList<FluencyService.Snapshot>();for(var p:paths.filter(f->f.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.json")).sorted(Comparator.reverseOrder()).limit(60).toList()){
   if(Files.size(p)>2_000_000)throw new IllegalStateException("Study snapshot too large");var s=mapper.readValue(Files.readAllBytes(p),FluencyService.Snapshot.class);
   if(!s.version().equals(FluencyService.VERSION)||!p.getFileName().toString().equals(s.setupDate()+".json"))throw new IllegalStateException("Study snapshot identity mismatch");result.add(s);
  }return List.copyOf(result);}catch(Exception e){throw new IllegalStateException("Unable to read saved study snapshots",e);}
 }
 public synchronized boolean save(FluencyService.Snapshot snapshot){
  if(!snapshot.version().equals(FluencyService.VERSION)||snapshot.marketGate().equals("DATA_INCOMPLETE"))throw new IllegalArgumentException("Unpublishable study snapshot");Path temporary=null;
  try{Files.createDirectories(directory);Path target=directory.resolve(snapshot.setupDate()+".json");if(Files.exists(target))return false;
   byte[] bytes=mapper.writeValueAsBytes(snapshot);if(bytes.length>2_000_000)throw new IllegalStateException("Study snapshot too large");
   temporary=Files.createTempFile(directory,".study-",".tmp");Files.write(temporary,bytes);
   // Exclusive atomic publication: an existing snapshot is never replaced, including concurrent processes.
   try{Files.createLink(target,temporary);return true;}catch(FileAlreadyExistsException exists){return false;}
  }catch(Exception e){throw new IllegalStateException("Unable to save research snapshot",e);}finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(Exception ignored){}}
 }
}
