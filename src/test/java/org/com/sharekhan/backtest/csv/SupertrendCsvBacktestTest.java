package org.com.sharekhan.backtest.csv;
import org.com.sharekhan.strategy.*;
import org.com.sharekhan.config.SupertrendStrategyProperties;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class SupertrendCsvBacktestTest {
    @Test void nextBarExecutionUsesStopFirstAndDoesNotReenterTheSameDirectionThatDay() {
        var indicators=mock(IndicatorService.class);when(indicators.minimumCandles()).thenReturn(50);
        when(indicators.computeSnapshot(anyList())).thenAnswer(call->{List<StrategyCandle> bars=call.getArgument(0);return new IndicatorSnapshot(bars.get(bars.size()-1),90,60,59,95,25,30,15);});
        var bars=new ArrayList<StrategyCandle>();var day=LocalDate.of(2026,10,1);
        for(int i=0;i<49;i++)bars.add(new StrategyCandle(day.minusDays(1),LocalTime.of(9,15).plusMinutes(i*5L),100,102,99,101,0L));
        bars.add(new StrategyCandle(day,LocalTime.of(9,20),100,102,99,101,0L));
        bars.add(new StrategyCandle(day,LocalTime.of(9,25),101,110,98,105,0L));
        bars.add(new StrategyCandle(day,LocalTime.of(9,30),105,110,104,109,0L));
        var engine=new SupertrendCsvBacktest(indicators,new SupertrendSignalRules(new SupertrendStrategyProperties()));
        var result=engine.run(new CsvCandleReader.Dataset(bars,bars.size(),0,0,0),new SupertrendCsvBacktest.Config("NIFTY",day,day,2,0,0,LocalTime.of(15,20)));
        assertThat(result.trades()).hasSize(1);var trade=result.trades().get(0);
        assertThat(trade.entryAt()).isEqualTo(day.atTime(9,25));assertThat(trade.exitBarAt()).isEqualTo(day.atTime(9,25));
        assertThat(trade.exitReason()).isEqualTo("STOP");assertThat(trade.netPoints()).isEqualTo(-2);
    }
    @Test void gapsInDataCannotProduceAnEntry() {
        var indicators=mock(IndicatorService.class);when(indicators.minimumCandles()).thenReturn(1);
        var day=LocalDate.of(2026,10,1);
        var bars=List.of(new StrategyCandle(day,LocalTime.of(9,20),100,102,99,101,0L),new StrategyCandle(day,LocalTime.of(9,30),101,104,100,103,0L));
        var result=new SupertrendCsvBacktest(indicators,new SupertrendSignalRules(new SupertrendStrategyProperties()))
                .run(new CsvCandleReader.Dataset(bars,2,0,0,1),new SupertrendCsvBacktest.Config("NIFTY",null,null,2,0,0,LocalTime.of(15,20)));
        assertThat(result.trades()).isEmpty();verify(indicators,never()).computeSnapshot(anyList());
    }
}
