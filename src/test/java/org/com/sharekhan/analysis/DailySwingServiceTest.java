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
        var scripts=mock(ScriptMasterRepository.class);var feed=mock(StockAnalysisShoonyaFeed.class);var fixtures=new SwingPlannerTest();
        var now=fixtures.day.plusDays(1).atTime(8,55).atZone(StockAnalysisService.IST).toInstant();
        var store=new SwingReportStore(JsonMapper.builder().findAndAddModules().build(),directory.toString());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(scripts.findByExchangeIgnoreCaseAndTradingSymbolIgnoreCase("NC","DEMO")).thenReturn(List.of(fixtures.stock()));
        when(feed.dailyInput(any(),eq(now))).thenAnswer(call->{entered.countDown();release.await(3,TimeUnit.SECONDS);return new StockAnalysisShoonyaFeed.DailyInput(fixtures.prices(),.05);});
        var service=new DailySwingService(scripts,feed,new SwingPlanner(),new SwingSessions(),store,"DEMO",Clock.fixed(now,StockAnalysisService.IST));
        try {
            assertThat(service.state().report()).isNull();verifyNoInteractions(feed);
            assertThat(service.requestScan()).isTrue();assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(service.requestScan()).isFalse();assertThat(service.state().running()).isTrue();release.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(service.state().running()&&System.nanoTime()<deadline)Thread.sleep(20);
            assertThat(service.state().running()).isFalse();assertThat(service.state().report()).isNotNull();
            assertThat(service.requestScan()).isFalse();verify(feed,times(1)).dailyInput(any(),eq(now));
        }finally{release.countDown();service.close();}
    }
}
