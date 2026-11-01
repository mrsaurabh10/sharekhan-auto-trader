package org.com.sharekhan.analysis;

import org.com.sharekhan.repository.ScriptMasterRepository;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DailySwingServiceTest {
    @TempDir Path directory;
    @Test void publicReadsNeverFetchAndRepeatedScanRequestsDoNotDuplicateWork()throws Exception{
        var daily=mock(SavedDailyAnalysis.class);var fixtures=new SwingPlannerTest();
        var now=fixtures.day.plusDays(1).atTime(8,55).atZone(StockAnalysisService.IST).toInstant();
        var store=new SwingReportStore(JsonMapper.builder().findAndAddModules().build(),directory.toString());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(daily.members()).thenReturn(List.of(new StockDailyHistoryStore.Member("DEMO","INE000000001",fixtures.day)));
        when(daily.read(eq("DEMO"),eq(fixtures.day))).thenAnswer(call->{entered.countDown();release.await(3,TimeUnit.SECONDS);return new StockDailyHistoryStore.Saved("DEMO","10",java.math.BigDecimal.valueOf(.05),now,fixtures.prices());});
        var service=new DailySwingService(daily,new SwingPlanner(),new SwingSessions(),store,Clock.fixed(now,StockAnalysisService.IST));
        try {
            assertThat(service.state().report()).isNull();verify(daily,never()).read(any(),any());
            assertThat(service.requestScan()).isTrue();assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(service.requestScan()).isFalse();assertThat(service.state().running()).isTrue();release.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(service.state().running()&&System.nanoTime()<deadline)Thread.sleep(20);
            assertThat(service.state().running()).isFalse();assertThat(service.state().report()).isNotNull();
            assertThat(service.requestScan()).isFalse();verify(daily,times(1)).read(eq("DEMO"),eq(fixtures.day));
        }finally{release.countDown();service.close();}
    }
}
