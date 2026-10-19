package org.com.sharekhan.backtest.csv;
import org.com.sharekhan.service.CsvBacktestService;
import org.com.sharekhan.config.SupertrendStrategyProperties;
import org.com.sharekhan.strategy.*;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
@DataJpaTest @Import({CsvBacktestService.class,IndicatorService.class,SupertrendSignalRules.class,SupertrendStrategyProperties.class})
class CsvBacktestServiceTest {
    @Autowired CsvBacktestService service;
    @Autowired DataSource dataSource;
    @Test void storedDatasetIsReusableAndRepeatedImportDoesNotDuplicateCandles() throws Exception {
        var text=new StringBuilder("date,open,high,low,close,volume\n");
        for(int i=0;i<75;i++) text.append("2026-10-01 "+LocalTime.of(9,15).plusMinutes(i*5L)+":00,"+(100+i)+","+(102+i)+","+(99+i)+","+(101+i)+",0\n");
        byte[] csv=text.toString().getBytes(StandardCharsets.UTF_8);
        var first=service.importCsv(new ByteArrayInputStream(csv),"data.csv","NIFTY",5);
        var again=service.importCsv(new ByteArrayInputStream(csv),"renamed.csv","NIFTY",5);
        assertThat(again.getId()).isEqualTo(first.getId());assertThat(first.getCandleCount()).isEqualTo(75);
        assertThat(new JdbcTemplate(dataSource).queryForObject("select count(*) from csv_backtest_candles",Integer.class)).isEqualTo(75);
        var result=service.run(first.getId(),LocalDate.of(2026,10,1),LocalDate.of(2026,10,1),2,0,0,LocalTime.of(15,20));
        assertThat(result.report().dataQuality().candles()).isEqualTo(75);assertThat(result.pnlUnit()).contains("not option");
    }
    @Test void straddleStudyReusesStoredSpotBarsAndHonorsExplicitDatesAndLots() throws Exception {
        var text=new StringBuilder("date,open,high,low,close,volume\n");
        for(int i=0;i<75;i++) text.append("2026-10-13 "+LocalTime.of(9,15).plusMinutes(i*5L)+":00,"+(25000+i)+","+(25002+i)+","+(24999+i)+","+(25001+i)+",0\n");
        var dataset=service.importCsv(new ByteArrayInputStream(text.toString().getBytes(StandardCharsets.UTF_8)),"nifty.csv","NIFTY",5);
        var report=service.runExpiryStraddle(dataset.getId(),null,null,2,java.util.List.of(LocalDate.of(2026,10,13)));
        assertThat(report.lots()).isEqualTo(2);assertThat(report.observations()).hasSize(2);
        assertThat(report.observations()).allSatisfy(row->assertThat(row.signedPoints()).isEqualTo(12));
        assertThat(report.unit()).contains("not option P&L");
        assertThat(service.runExpiryStraddle(dataset.getId(),null,null,1,null).observations()).hasSize(2);
        assertThatThrownBy(()->service.runExpiryStraddle(dataset.getId(),LocalDate.of(2026,10,14),LocalDate.of(2026,10,13),1,null)).isInstanceOf(IllegalArgumentException.class);
    }
}
