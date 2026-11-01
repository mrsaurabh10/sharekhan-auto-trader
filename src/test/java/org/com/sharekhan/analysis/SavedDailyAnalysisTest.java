package org.com.sharekhan.analysis;
import org.junit.jupiter.api.Test;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.service.SharekhanHistoricalService;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class SavedDailyAnalysisTest {
    final SwingPlannerTest fixture=new SwingPlannerTest();
    @Test void afterCloseDailyReadAcceptsCompletedCurrentSessionAndNeedsNoBroker(){
        var store=mock(StockDailyHistoryStore.class);var saved=new SavedDailyAnalysis(store,new SwingSessions());var now=fixture.day.atTime(19,0).atZone(StockAnalysisService.IST).toInstant();
        when(store.members()).thenReturn(List.of(new StockDailyHistoryStore.Member("DEMO","INE000000001",fixture.day)));
        when(store.read("DEMO",fixture.day)).thenReturn(new StockDailyHistoryStore.Saved("DEMO","999",BigDecimal.valueOf(.05),now,fixture.prices()));
        var report=saved.analyze("NC","demo",now);assertThat(report.provider()).isEqualTo(SavedDailyAnalysis.PROVIDER);assertThat(report.instrument().scripCode()).isNull();assertThat(report.candles()).hasSize(220);assertThat(report.asOf().toLocalDate()).isEqualTo(fixture.day);assertThat(report.dataStatus()).isEqualTo("HISTORICAL");
        var scripts=mock(ScriptMasterRepository.class);var share=mock(SharekhanHistoricalService.class);var shoonya=mock(StockAnalysisShoonyaFeed.class);var delegated=mock(SavedDailyAnalysis.class);when(delegated.analyze(eq("NC"),eq("DEMO"),any())).thenReturn(report);var wired=new StockAnalysisService(scripts,share,shoonya,delegated);
        wired.analyzeSaved("NC","DEMO");verifyNoInteractions(scripts,share,shoonya);
    }
    @Test void aRecentGapOrOldSessionIsClearlyStale(){
        var store=mock(StockDailyHistoryStore.class);var saved=new SavedDailyAnalysis(store,new SwingSessions());var now=fixture.day.atTime(19,0).atZone(StockAnalysisService.IST).toInstant();var rows=fixture.prices();rows.remove(rows.size()-2);
        when(store.members()).thenReturn(List.of(new StockDailyHistoryStore.Member("DEMO","INE000000001",fixture.day)));
        when(store.read("DEMO",fixture.day)).thenReturn(new StockDailyHistoryStore.Saved("DEMO","999",BigDecimal.valueOf(.05),now,rows));
        assertThat(saved.analyze("NC","DEMO",now).dataStatus()).isEqualTo("STALE");
        assertThatThrownBy(()->saved.analyze("BC","DEMO",now)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void symbolSearchUsesSavedUniverseRatherThanAnotherBrokersIds(){
        var store=mock(StockDailyHistoryStore.class);var saved=new SavedDailyAnalysis(store,new SwingSessions());when(store.members()).thenReturn(List.of(new StockDailyHistoryStore.Member("DEMO","INE000000001",fixture.day)));
        assertThat(saved.search("NC","dem")).containsExactly(new StockAnalysisService.Instrument(null,"DEMO","NC"));assertThat(saved.search("BC","dem")).isEmpty();
    }
}
