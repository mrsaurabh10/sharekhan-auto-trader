package org.com.sharekhan.strategy.adxdmi;

import org.com.sharekhan.strategy.StrategyCandle;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AdxDmiReplayTest {
    static final LocalDate DAY=LocalDate.of(2026,6,2);
    static StrategyCandle bar(LocalDate day,String time,double o,double h,double l,double c){return new StrategyCandle(day,LocalTime.parse(time),o,h,l,c,0L);}
    static List<StrategyCandle> warmup(){
        var bars=new ArrayList<StrategyCandle>();
        for(int i=0;i<40;i++){double p=100+3*i;bars.add(new StrategyCandle(DAY.minusDays(1),LocalTime.of(9,15).plusMinutes(i*5L),p,p+1,p-1,p,0L));}
        return bars;
    }
    static AdxDmiReplay.Report run(List<StrategyCandle> bars){return new AdxDmiReplay().run(bars,new AdxDmiReplay.Config("NIFTY",DAY,DAY,true,false));}
    static List<StrategyCandle> entryBars(){var bars=warmup();bars.add(bar(DAY,"09:20",220,222,219,221));return bars;}
    @Test void wilderSeedAndGapTrueRange(){
        var dmi=new AdxDmiRules.Dmi();
        for(int i=0;i<40;i++){
            double p=100+3*i;var r=dmi.add(new StrategyCandle(DAY,LocalTime.of(9,15).plusMinutes(i*5L),p,p+1,p-1,p,0L));
            if(i<14)assertTrue(Double.isNaN(r.plus()));else assertEquals(75,r.plus(),1e-9);
            if(i<27)assertTrue(Double.isNaN(r.adx()));else assertEquals(100,r.adx(),1e-9);
        }
    }
    @Test void skipsOnlyFirstCandleWithoutSkippingTheSecond(){
        var reading=new AdxDmiRules.Reading(30,10,30);
        assertTrue(AdxDmiRules.signal(new StrategyCandle(DAY,LocalTime.of(9,15),100,102,99,101,0L),reading,true).isEmpty());
        assertEquals(DAY.atTime(9,25),AdxDmiRules.signal(new StrategyCandle(DAY,LocalTime.of(9,20),100,102,99,101,0L),reading,true).orElseThrow().end());
    }
    @Test void thresholdsAreStrict(){
        assertEquals(0,new AdxDmiRules.Reading(30,10,25).direction());
        assertEquals(0,new AdxDmiRules.Reading(25,10,26).direction());
        assertEquals(0,new AdxDmiRules.Reading(30,20,26).direction());
        assertEquals(-1,new AdxDmiRules.Reading(10,30,26).direction());
    }
    @Test void partialOneThenOriginalStopForTwo(){
        var bars=entryBars();bars.add(bar(DAY,"09:25",221,226,220,225));bars.add(bar(DAY,"09:30",224,224,218,219));
        var r=run(bars);assertEquals(1,r.summary().cycles());assertEquals(3,r.exits().size());
        assertEquals(List.of("T1","STOP","STOP"),r.exits().stream().map(AdxDmiReplay.Exit::reason).toList());
        assertEquals(-3.05,r.summary().points(),1e-7);r.exits().forEach(e->assertEquals(219,e.stop()));
    }
    @Test void twoPartialsThenStopReturnsTwoUnitR(){
        var bars=entryBars();bars.add(bar(DAY,"09:25",221,228.2,220,227));bars.add(bar(DAY,"09:30",227,227,218,219));
        var r=run(bars);assertEquals(List.of("T1","T2","STOP"),r.exits().stream().map(AdxDmiReplay.Exit::reason).toList());assertEquals(6.1,r.summary().points(),1e-7);
    }
    @Test void gapReanchorsAllTargetsToActualEntry(){
        var bars=entryBars();bars.add(bar(DAY,"09:25",224,240,223,239));var r=run(bars);
        assertEquals(3,r.exits().size());assertEquals(30,r.summary().points(),1e-7);
        for(var e:r.exits()){assertEquals(224,e.entry());assertEquals(224+5*e.leg(),e.target(),1e-8);}
    }
    @Test void shortMirrorsLong(){
        var bars=entryBars();bars.add(bar(DAY,"09:25",221,226,220,225));bars.add(bar(DAY,"09:30",224,224,218,219));
        var mirrored=bars.stream().map(b->new StrategyCandle(b.date(),b.time(),400-b.open(),400-b.low(),400-b.high(),400-b.close(),0L)).toList();
        var r=run(mirrored);assertEquals(-3.05,r.summary().points(),1e-7);r.exits().forEach(e->assertEquals("PE",e.side()));
    }
    @Test void signalExpiresAndCannotFillItsOwnCandle(){
        var bars=entryBars();bars.add(bar(DAY,"09:25",221,222,220,221));bars.add(bar(DAY,"09:30",221,224,220,223));
        var r=run(bars);assertEquals(1,r.summary().cycles());assertEquals(LocalDateTime.of(DAY,LocalTime.of(9,30)),r.exits().isEmpty()?r.decisions().getLast().bar():r.exits().getFirst().entryBar());
        assertEquals("PENDING",r.decisions().getFirst().state());
    }
    @Test void squareOffUses1515BarCloseAndNeverMovesStop(){
        var bars=entryBars();bars.add(bar(DAY,"09:25",221,224,220,223));bars.add(bar(DAY,"15:15",223,224,222,223.5));
        var r=run(bars);assertEquals(3,r.exits().size());r.exits().forEach(e->{assertEquals("SESSION_CLOSE",e.reason());assertEquals(223.5,e.exit());assertEquals(219,e.stop());});assertEquals(0,r.summary().openLegs());
    }
    @Test void cutoffAndNoOvernightSignal(){
        var bars=warmup();bars.add(bar(DAY,"12:00",220,222,219,221));bars.add(bar(DAY,"12:05",221,240,220,239));assertEquals(0,run(bars).summary().cycles());
        assertFalse(AdxDmiRules.entryWindow(DAY.atTime(14,50),false));assertTrue(AdxDmiRules.entryWindow(DAY.atTime(14,45),false));
    }
    @Test void rejectsDuplicateOrUnalignedCandles(){
        var bars=entryBars();bars.add(bars.getLast());assertThrows(IllegalArgumentException.class,()->run(bars));
        assertThrows(IllegalArgumentException.class,()->AdxDmiRules.validate(bar(DAY,"09:16",100,101,99,100)));
    }
    @Test void productionWarmupDoesNotTradeFromOnlyFortyBars(){
        var bars=entryBars();bars.add(bar(DAY,"09:25",221,240,220,239));
        assertEquals(0,new AdxDmiReplay().run(bars,new AdxDmiReplay.Config("NIFTY",DAY,DAY,true)).summary().cycles());
        assertTrue(AdxDmiRules.latestSignal(bars,DAY.atTime(9,30)).isEmpty());
    }
    @Test void futureCandlesCannotChangeEarlierFillsOrEnableFillBarReentry(){
        var bars=entryBars();bars.add(bar(DAY,"09:25",221,226,220,225));
        var prefix=run(bars);bars.add(bar(DAY,"09:30",224,224,218,219));var full=run(bars);
        assertEquals(prefix.exits(),full.exits().stream().filter(e->e.exitBar().isBefore(DAY.atTime(9,30))).toList());
        assertEquals("FLAT",full.decisions().getLast().state());
    }
    @Test void signalUsesSuppliedUnderlyingTickSizeRatherThanAnIndexTick(){
        var b=bar(DAY,"09:20",100,100.12,99.91,100.1);
        var s=AdxDmiRules.signal(b,new AdxDmiRules.Reading(30,10,30),true,.01).orElseThrow();
        assertEquals(100.13,s.trigger(),1e-9);assertEquals(.01,s.tickSize());
        assertEquals(100.35,s.target(100.13,1),1e-9);
    }
}
