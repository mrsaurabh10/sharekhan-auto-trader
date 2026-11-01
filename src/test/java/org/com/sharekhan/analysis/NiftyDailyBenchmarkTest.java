package org.com.sharekhan.analysis;
import org.junit.jupiter.api.*;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.*;import java.util.*;import static org.assertj.core.api.Assertions.*;
class NiftyDailyBenchmarkTest {
 JdbcTemplate db;NiftyDailyBenchmark store;final LocalDate day=LocalDate.of(2026,10,30);
 @BeforeEach void setup(){db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa",""));store=new NiftyDailyBenchmark(db);
 db.execute("CREATE TABLE market_history_candles(symbol varchar(20),interval_minutes int,provider varchar(20),source_segment varchar(20),source_security_id varchar(20),source_interval_minutes int,source_bar_count int,expected_source_bars int,quality_status varchar(50),candle_time timestamp,open_price double precision,high_price double precision,low_price double precision,close_price double precision)");
 var rows=new ArrayList<Object[]>();for(int i=0;i<375;i++)rows.add(new Object[]{"NIFTY",1,"DHAN","IDX_I","13",1,1,1,"NATIVE_5M_WINDOW_CONFIRMED",day.atTime(9,15).plusMinutes(i),100,102,99,101+i*.001});db.batchUpdate("INSERT INTO market_history_candles VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",rows);
 }
 @Test void completeRegularNativeMinutesProduceLastMinuteClose(){var snapshot=store.read(day);assertThat(snapshot.closes()).containsOnlyKeys(day);assertThat(snapshot.closes().get(day)).isCloseTo(101.374,within(.00001));assertThat(snapshot.provider()).isEqualTo("DHAN");assertThat(store.read(day.minusDays(1)).closes()).isEmpty();}
 @Test void missingFlaggedAndMalformedBarsNeverBecomeDailyReferences(){
  db.update("UPDATE market_history_candles SET quality_status='CROSS_INTERVAL_REVIEW' WHERE candle_time=?",day.atTime(10,0));assertThat(store.read(day).closes()).isEmpty();db.update("UPDATE market_history_candles SET quality_status='NATIVE_5M_WINDOW_CONFIRMED',high_price=90 WHERE candle_time=?",day.atTime(10,0));assertThat(store.read(day).closes()).isEmpty();db.update("DELETE FROM market_history_candles WHERE candle_time=?",day.atTime(10,0));assertThat(store.read(day).closes()).isEmpty();
 }
 @Test void wrongIdentityIntervalAndProviderAreNotSubstituted(){db.update("UPDATE market_history_candles SET provider='MSTOCK'");assertThat(store.read(day).closes()).isEmpty();db.update("UPDATE market_history_candles SET provider='DHAN',source_security_id='25'");assertThat(store.read(day).closes()).isEmpty();db.update("UPDATE market_history_candles SET source_security_id='13',interval_minutes=5");assertThat(store.read(day).closes()).isEmpty();}
 @Test void duplicateOrOffGridTimesCannotPassCountOnlyCheck(){db.execute("INSERT INTO market_history_candles SELECT * FROM market_history_candles WHERE candle_time=TIMESTAMP '2026-10-30 10:00:00'");assertThat(store.read(day).closes()).isEmpty();db.update("DELETE FROM market_history_candles WHERE candle_time=?",day.atTime(10,0));db.update("UPDATE market_history_candles SET candle_time=? WHERE candle_time=?",day.atTime(10,1,30),day.atTime(10,1));assertThat(store.read(day).closes()).isEmpty();}
}
