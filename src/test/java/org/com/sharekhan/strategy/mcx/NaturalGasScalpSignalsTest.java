package org.com.sharekhan.strategy.mcx;
import org.junit.jupiter.api.Test;
import org.com.sharekhan.strategy.StrategyCandle;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class NaturalGasScalpSignalsTest {
    static final LocalDateTime NOW=LocalDateTime.of(2026,10,27,14,3,5);
    static List<StrategyCandle> bars(int interval,int count,boolean bull) {
        var rows=new ArrayList<StrategyCandle>();
        var end=NOW.withMinute(NOW.getMinute()/interval*interval).withSecond(0);
        for(int i=0;i<count;i++) {
            var at=end.minusMinutes((long)(count-i)*interval);double price=bull?150+i*.1:170-i*.1;
            rows.add(new StrategyCandle(at.toLocalDate(),at.toLocalTime(),price,price+.15,price-.15,price+(bull?.05:-.05),100L));
        }
        if(interval==3) {var c=rows.getLast();double close=c.close()+(bull?.15:-.15);rows.set(count-1,new StrategyCandle(c.date(),c.time(),c.open(),Math.max(c.high(),close+.01),Math.min(c.low(),close-.01),close,100L));}
        return rows;
    }
    @Test void directCeAndPeBreakoutsNeedNoRetest() {
        for(boolean bull:List.of(true,false)) {var e=NaturalGasScalpSignals.inspect(bars(3,40,bull),bars(5,60,bull),NOW,.1);assertTrue(e.signal().isPresent(),e.reason());assertEquals(bull?"CE":"PE",e.signal().get().side());}
    }
    @Test void fiveMinuteTrendRemainsCurrentAtThreeMinuteClose() {assertTrue(NaturalGasScalpSignals.inspect(bars(3,40,true),bars(5,60,true),NOW,.1).signal().isPresent());}
    @Test void missingExpectedTrendCloseBlocks() {var five=bars(5,60,true);five.removeLast();assertTrue(NaturalGasScalpSignals.inspect(bars(3,40,true),five,NOW,.1).reason().contains("current completed five-minute"));}
    @Test void warmupGapVolumeAndStalenessBlock() {
        assertTrue(NaturalGasScalpSignals.inspect(bars(3,39,true),bars(5,60,true),NOW,.1).signal().isEmpty());
        var entry=bars(3,41,true);entry.remove(20);assertTrue(NaturalGasScalpSignals.inspect(entry,bars(5,60,true),NOW,.1).reason().contains("Gap"));
        entry=bars(3,40,true);var c=entry.get(0);entry.set(0,new StrategyCandle(c.date(),c.time(),c.open(),c.high(),c.low(),c.close(),0L));assertTrue(NaturalGasScalpSignals.inspect(entry,bars(5,60,true),NOW,.1).reason().contains("volume"));
        assertTrue(NaturalGasScalpSignals.inspect(bars(3,40,true),bars(5,60,true),NOW.plusSeconds(76),.1).reason().contains("fresh"));
    }
    @Test void spikeAndDuplicatesBlock() {
        var entry=bars(3,40,true);var c=entry.getLast();entry.set(39,new StrategyCandle(c.date(),c.time(),c.open(),c.high()+10,c.low(),c.close(),100L));assertTrue(NaturalGasScalpSignals.inspect(entry,bars(5,60,true),NOW,.1).reason().contains("spike"));
        entry=bars(3,40,true);entry.add(entry.getFirst());assertTrue(NaturalGasScalpSignals.inspect(entry,bars(5,60,true),NOW,.1).reason().contains("Duplicate"));
    }
}
