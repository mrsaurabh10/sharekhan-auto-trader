package org.com.sharekhan.analysis;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;import java.time.*;import java.util.*;import static org.assertj.core.api.Assertions.*;
class FluencyStoreTest {
 @TempDir Path dir;
 FluencySnapshotStore store(){return new FluencySnapshotStore(new ObjectMapper().findAndRegisterModules(),dir.toString());}
 FluencyService.Snapshot snapshot(String gate){return new FluencyService.Snapshot(FluencyService.VERSION,LocalDate.of(2026,10,30),Instant.now(),LocalDate.of(2026,11,1),"SAVED_ARCHIVE_RECONSTRUCTION",gate,200,197,.2,List.of());}
 @Test void loadCreatesNoFilesAndSaveIsAtomicImmutableAndRoundTrips()throws Exception{var s=store();assertThat(s.load()).isEmpty();assertThat(Files.list(dir).count()).isZero();var original=snapshot("MARKET_CAUTION");assertThat(s.save(original)).isTrue();assertThat(s.load()).containsExactly(original);assertThat(s.save(snapshot("READY"))).isFalse();assertThat(s.load()).containsExactly(original);try(var files=Files.list(dir.resolve(FluencyService.VERSION))){assertThat(files.map(p->p.getFileName().toString()).toList()).containsExactly("2026-10-30.json");}}
 @Test void incompleteSnapshotsCannotBeFrozen(){assertThatThrownBy(()->store().save(snapshot("DATA_INCOMPLETE"))).isInstanceOf(IllegalArgumentException.class);assertThat(store().load()).isEmpty();}
 @Test void corruptSavedFilesFailInsteadOfShowingMadeUpResults()throws Exception{var path=dir.resolve(FluencyService.VERSION);Files.createDirectories(path);Files.writeString(path.resolve("2026-10-30.json"),"{}");assertThatThrownBy(()->store().load()).isInstanceOf(IllegalStateException.class);}
}
