package org.com.sharekhan.analysis;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class StockDailyHistoryStoreTest {
    JdbcTemplate db;StockDailyHistoryStore store;final LocalDate day=LocalDate.of(2026,10,30);final Instant now=Instant.parse("2026-10-30T14:00:00Z");
    @BeforeEach void setup(){var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");db=new JdbcTemplate(ds);store=new StockDailyHistoryStore(db,new DataSourceTransactionManager(ds));store.initialize();}
    StockDailyHistoryStore.Member member(){return new StockDailyHistoryStore.Member("DEMO","INE000000001",day);}
    DhanDailyBars.Candle bar(LocalDate date,int close){return new DhanDailyBars.Candle(date,date.atStartOfDay(StockAnalysisService.IST).toEpochSecond(),BigDecimal.valueOf(100),BigDecimal.valueOf(105),BigDecimal.valueOf(98),BigDecimal.valueOf(close),1000);}
    @Test void correctionsAndRepeatSavesDoNotDuplicateBarsAndFutureBarsAreExcluded(){
        store.save(member(),"10",BigDecimal.valueOf(.05),day,day,List.of(bar(day,101)),now);
        store.save(member(),"10",BigDecimal.valueOf(.05),day,day,List.of(bar(day,102)),now.plusSeconds(5));
        assertThat(store.read("DEMO",day).candles()).hasSize(1);assertThat(store.read("DEMO",day).candles().getFirst().close()).isEqualTo(102);assertThat(store.read("DEMO",day.minusDays(1)).candles()).isEmpty();
    }
    @Test void invalidBatchRollsBackAllWritesAndKeepsPreviousHistory(){
        store.save(member(),"10",BigDecimal.valueOf(.05),day,day,List.of(bar(day,101)),now);
        assertThatThrownBy(()->store.save(member(),"10",BigDecimal.valueOf(.05),day,day.plusDays(1),List.of(bar(day,103),bar(day.plusDays(1),500)),now)).isInstanceOf(Exception.class);
        assertThat(store.read("DEMO",day.plusDays(1)).candles()).hasSize(1);assertThat(store.read("DEMO",day).candles().getFirst().close()).isEqualTo(101);assertThat(db.queryForObject("SELECT count(*) FROM stock_history_imports",Integer.class)).isEqualTo(1);
    }
    @Test void leaseAndCooldownSurviveAnotherServiceInstance(){
        assertThat(store.claim(day,now)).isTrue();var other=new StockDailyHistoryStore(db,new DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource())));
        assertThat(other.claim(day,now.plusSeconds(10))).isFalse();store.finish(day,now,now.plusSeconds(10),"PARTIAL",0,0,1,"Delayed data");
        assertThat(other.claim(day,now.plusSeconds(60))).isFalse();assertThat(other.claim(day,now.plusSeconds(1801))).isTrue();
        assertThat(other.latestUpdate().orElseThrow().status()).isEqualTo("RUNNING");
    }
    @Test void readerFailsOnAmbiguousSecurityIdentityAndOnlyUsesLatestMembership(){
        store.save(member(),"10",BigDecimal.valueOf(.05),day,day,List.of(bar(day,101)),now);store.save(member(),"11",BigDecimal.valueOf(.05),day,day,List.of(bar(day,101)),now);
        assertThatThrownBy(()->store.read("DEMO",day)).isInstanceOf(IllegalStateException.class);
        for(String[] row:new String[][]{{"OLD","2026-10-01"},{"DEMO","2026-11-01"}})db.update("INSERT INTO stock_index_constituents VALUES ('NIFTY 200',?,?,?,'official','hash',?)",LocalDate.parse(row[1]),row[0],"INE000000001",java.sql.Timestamp.from(now));
        assertThat(store.members()).extracting(StockDailyHistoryStore.Member::symbol).containsExactly("DEMO");
    }
}
