package org.com.sharekhan.analysis;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.com.sharekhan.analysis.SwingModels.*;

class SwingReportsTest {
    @TempDir Path directory;
    final LocalDate day=LocalDate.of(2026,10,29);
    StockResult row(String status,boolean above){return new StockResult("DEMO",10,status,"detail",day,above,above,null);}
    Report report(String status){return new Report("v1",day,day.plusDays(1),Instant.parse("2026-10-29T13:00:00Z"),status,1,1,1,List.of(),List.of(),List.of());}
    @Test void partialCoverageIsNotMisreportedAsNoSetups(){
        var rows=List.of(row("FILTERED",true),row("UNAVAILABLE",false),row("UNAVAILABLE",false));
        assertThat(DailySwingService.assemble(day,day.plusDays(1),rows,Instant.now()).status()).isEqualTo("DATA_INCOMPLETE");
        assertThat(DailySwingService.assemble(day,day.plusDays(1),List.of(row("FILTERED",true)),Instant.now()).status()).isEqualTo("NO_SETUPS");
        assertThat(DailySwingService.assemble(day,day.plusDays(1),List.of(row("FILTERED",false)),Instant.now()).status()).isEqualTo("MARKET_CAUTION");
    }
    @Test void publishedReportIsImmutableButIncompleteReportCanBeReplaced()throws Exception{
        var store=new SwingReportStore(JsonMapper.builder().findAndAddModules().build(),directory.toString());
        store.save(report("DATA_INCOMPLETE"));store.save(report("NO_SETUPS"));store.save(report("MARKET_CAUTION"));
        assertThat(store.read(day).orElseThrow().status()).isEqualTo("NO_SETUPS");assertThat(store.history()).hasSize(1);
        Files.writeString(directory.resolve("keep.txt"),"unrelated");
        store.save(new Report("v1",day.plusDays(181),day.plusDays(182),Instant.now(),"NO_SETUPS",1,1,1,List.of(),List.of(),List.of()));
        assertThat(store.history()).hasSize(1);assertThat(directory.resolve("keep.txt")).exists();
    }
    @Test void observationsRoundTripWithoutOverwritingPlans(){
        var store=new SwingReportStore(JsonMapper.builder().findAndAddModules().build(),directory.toString());store.save(report("NO_SETUPS"));
        var observation=new Observation("DEMO",day,day.plusDays(1),"AMBIGUOUS","No fill inferred");
        store.saveObservations(List.of(observation));assertThat(store.observations()).containsExactly(observation);assertThat(store.read(day)).contains(report("NO_SETUPS"));
    }
    @Test void holidayCalendarAndValidityFailClosedBeyondVerifiedYear(){
        var sessions=new SwingSessions();assertThat(sessions.next(LocalDate.of(2026,11,9))).isEqualTo(LocalDate.of(2026,11,11));
        assertThat(sessions.regular(LocalDate.of(2026,11,8))).isFalse();assertThat(sessions.next(LocalDate.of(2026,12,31))).isNull();
        assertThat(sessions.next(LocalDate.of(2026,11,6))).isNull();
        assertThat(sessions.expected(day.atTime(17,59))).isEqualTo(day.minusDays(1));assertThat(sessions.expected(day.atTime(18,0))).isEqualTo(day);
        assertThat(DailySwingService.window(day,day.atTime(15,30))).isEqualTo("EXPIRED");
        assertThat(DailySwingService.window(day,day.atTime(9,15))).isEqualTo("ENTRY_SESSION");
    }
}
