package org.com.sharekhan.backtest.csv;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.time.LocalTime;
import static org.assertj.core.api.Assertions.*;
class CsvCandleReaderTest {
    private final String header="date,open,high,low,close,volume\n";
    @Test void aggregatesCompleteGroupsAndDropsIncompleteAndOutsideSession() throws Exception {
        var rows=new StringBuilder(header);
        for(int i=0;i<5;i++) rows.append("2026-10-01 09:"+(15+i)+":00,"+(100+i)+","+(102+i)+","+(99+i)+","+(101+i)+",10\n");
        rows.append("2026-10-01 09:20:00,100,102,99,101,10\n2026-10-01 17:30:00,100,102,99,101,10\n");
        var result=new CsvCandleReader().read(new StringReader(rows.toString().replace("09:16:00","09:16:01")),1);
        assertThat(result.candles()).hasSize(1);
        var bar=result.candles().get(0);assertThat(bar.time()).isEqualTo(LocalTime.of(9,15));
        assertThat(bar.open()).isEqualTo(100);assertThat(bar.close()).isEqualTo(105);assertThat(bar.high()).isEqualTo(106);assertThat(bar.low()).isEqualTo(99);assertThat(bar.volume()).isEqualTo(50);
        assertThat(result.normalizedTimestamps()).isEqualTo(1);assertThat(result.incompleteGroups()).isEqualTo(1);assertThat(result.outsideSession()).isEqualTo(1);
    }
    @Test void duplicatesAndMalformedPricesFailWithRowNumber() {
        String row="2026-10-01 09:15:00,100,102,99,101,0\n";
        assertThatThrownBy(()->new CsvCandleReader().read(new StringReader(header+row+row),5)).hasMessageContaining("row 3").hasMessageContaining("Duplicate");
        assertThatThrownBy(()->new CsvCandleReader().read(new StringReader(header+"2026-10-01 09:15:00,100,98,99,101,0\n"),5)).hasMessageContaining("Invalid OHLC");
    }
}
