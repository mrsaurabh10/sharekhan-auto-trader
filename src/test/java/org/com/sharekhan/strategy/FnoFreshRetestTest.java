package org.com.sharekhan.strategy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.com.sharekhan.strategy.Fno925EntryQualificationService.*;

class FnoFreshRetestTest {
    private final LocalDate day = LocalDate.of(2026, 10, 29);
    private StrategyCandle bar(int minute, double o, double h, double l, double c) {
        return new StrategyCandle(day, LocalTime.of(10, minute), o, h, l, c, 100L);
    }
    private final String key = "NC:123:ABC:CE";
    private StrategyCandle anchor() { return bar(0, 99, 102, 98, 101.5); }
    private StrategyCandle retest() { return bar(5, 101.5, 101.6, 100.1, 100.6); }
    private StrategyCandle confirm() { return bar(10, 100.6, 102, 100.5, 101.8); }
    private Qualification frame(List<StrategyCandle> bars, boolean riskOnly, String filter, double atr, boolean baselineQualified) {
        var last = bars.get(bars.size()-1);
        var at = day.atTime(last.time().plusMinutes(5));
        var f = new RetestFrame(at, bars, atr, 100, 90, 90, riskOnly, filter,
                baselineQualified, "ORB: structural risk exceeds cap; VWAP reclaim: not confirmed", 2, LocalTime.of(11,30));
        return new Qualification(baselineQualified ? new Signal(102, 99, 1, 100, 90, last, "MORNING_ORB") : null,
                baselineQualified ? null : f.baselineReason(), f);
    }
    private Qualification evaluate(FnoFreshRetest engine, List<StrategyCandle> bars, boolean rejected, String filter) {
        var q = frame(bars, rejected, filter, 1, false);
        return engine.evaluate(key, "CE", q, q.retestFrame().evaluatedAt());
    }
    private FnoFreshRetest seeded() {
        var engine = new FnoFreshRetest();
        assertThat(evaluate(engine, List.of(anchor()), true, null).qualified()).isFalse();
        return engine;
    }
    @Test void freshCeRetestPassesWithNewStopAndIsSharedForRepeatedAccounts() {
        var engine = seeded();
        assertThat(evaluate(engine, List.of(anchor(), retest()), false, null).qualified()).isFalse();
        var bars = List.of(anchor(), retest(), confirm());
        var result = evaluate(engine, bars, false, null);
        assertThat(result.qualified()).isTrue();
        assertThat(result.signal().setup()).isEqualTo("MORNING_ORB_FRESH_RETEST");
        assertThat(result.signal().entryPrice()).isEqualTo(101.8);
        assertThat(result.signal().stopLoss()).isEqualTo(100.1);
        assertThat(evaluate(engine, bars, false, null)).isSameAs(result);
    }
    @Test void peRetestIsMirroredAndKeepsStopAbovePullback() {
        var engine = new FnoFreshRetest();
        var a = bar(0, 91, 92, 88, 88.5);
        var r = bar(5, 88.5, 89.9, 88.4, 89.4);
        var c = bar(10, 89.4, 89.5, 88, 88.2);
        var f1 = new RetestFrame(day.atTime(10,5), List.of(a), 1, 100, 90, 100, true, null, false, "risk", 2, LocalTime.of(11,30));
        var f2 = new RetestFrame(day.atTime(10,15), List.of(a,r,c), 1, 100, 90, 100, false, null, false, "risk", 2, LocalTime.of(11,30));
        engine.evaluate("PE", "PE", new Qualification(null,"risk",f1), f1.evaluatedAt());
        var result = engine.evaluate("PE", "PE", new Qualification(null,"risk",f2), f2.evaluatedAt());
        assertThat(result.qualified()).isTrue();assertThat(result.signal().stopLoss()).isEqualTo(89.9);
    }
    @Test void historyAloneOrRestartCannotManufactureAnObservedRiskRejection() {
        assertThat(evaluate(new FnoFreshRetest(), List.of(anchor(),retest(),confirm()), false, null).qualified()).isFalse();
        var engine = new FnoFreshRetest();
        evaluate(engine, List.of(anchor()), false, null);
        assertThat(evaluate(engine, List.of(anchor(),retest(),confirm()), false, null).qualified()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"low volume", "wrong side of VWAP", "small body", "rejection wick"})
    void confirmationRetainsAllExistingOrbFilters(String failure) {
        assertThat(evaluate(seeded(), List.of(anchor(),retest(),confirm()), false, failure).qualified()).isFalse();
    }
    @Test void excessiveNewRiskCannotBeRescuedByRetest() {
        var engine=seeded();var q=frame(List.of(anchor(),retest(),confirm()),false,null,.5,false);
        assertThat(engine.evaluate(key,"CE",q,q.retestFrame().evaluatedAt()).qualified()).isFalse();
    }
    @Test void missingBarOrOriginalStopBreachInvalidatesAnchor() {
        var engine=seeded();
        assertThat(evaluate(engine,List.of(anchor(),confirm()),false,null).qualified()).isFalse();
        engine=seeded();
        var breached=bar(5,101.5,101.6,89,100.6);
        assertThat(evaluate(engine,List.of(anchor(),breached,confirm()),false,null).qualified()).isFalse();
    }
    @Test void noTouchOrNoPullbackOrNoConfirmationCannotEnter() {
        assertThat(evaluate(seeded(), List.of(anchor(),bar(5,101.5,102,100.8,101),confirm()),false,null).qualified()).isFalse();
        assertThat(evaluate(seeded(), List.of(anchor(),bar(5,101.5,102,100.1,101.6),confirm()),false,null).qualified()).isFalse();
        assertThat(evaluate(seeded(), List.of(anchor(),retest(),bar(10,101.8,102,100.5,101.7)),false,null).qualified()).isFalse();
    }
    @Test void staleFutureAndOutOfOrderFramesCannotReviveSignal() {
        var engine=seeded();var q=frame(List.of(anchor(),retest(),confirm()),false,null,1,false);
        assertThat(engine.evaluate(key,"CE",q,q.retestFrame().evaluatedAt().plusSeconds(31)).qualified()).isFalse();
        assertThat(engine.evaluate(key,"CE",q,q.retestFrame().evaluatedAt().minusSeconds(1)).qualified()).isFalse();
        assertThat(engine.evaluate(key,"CE",q,q.retestFrame().evaluatedAt().plusMinutes(5)).qualified()).isFalse();
        assertThat(engine.evaluate(key,"CE",q,q.retestFrame().evaluatedAt()).qualified()).isTrue();
        var old=frame(List.of(anchor(),retest()),false,null,1,false);
        assertThat(engine.evaluate(key,"CE",old,q.retestFrame().evaluatedAt()).qualified()).isFalse();
    }
    @Test void oldAnchorsExpireAndDoNotCarryToNextSessionOrOtherSymbol() {
        var engine=seeded();
        assertThat(engine.evaluate("OTHER","CE",frame(List.of(anchor(),retest(),confirm()),false,null,1,false),day.atTime(10,15)).qualified()).isFalse();
        var later=List.of(anchor(),retest(),bar(10,101,101.2,100.2,100.5),bar(15,100.5,101,100.1,100.6),bar(20,100.6,102,100.5,101.8));
        assertThat(evaluate(engine,later,false,null).qualified()).isFalse();
        var tomorrow=later.stream().map(c->new StrategyCandle(day.plusDays(1),c.time(),c.open(),c.high(),c.low(),c.close(),c.volume())).toList();
        var f=new RetestFrame(day.plusDays(1).atTime(10,25),tomorrow,1,100,90,90,false,null,false,"risk",2,LocalTime.of(11,30));
        assertThat(engine.evaluate(key,"CE",new Qualification(null,"risk",f),f.evaluatedAt()).qualified()).isFalse();
    }
    @Test void baselineQualificationTakesPrecedenceAndClearsAnchor() {
        var engine=seeded();var q=frame(List.of(anchor(),retest()),false,null,1,true);
        assertThat(engine.evaluate(key,"CE",q,q.retestFrame().evaluatedAt())).isSameAs(q);
        assertThat(evaluate(engine,List.of(anchor(),retest(),confirm()),false,null).qualified()).isFalse();
    }
    @Test void noEntryAfterMorningCutoff() {
        var engine=seeded();var bars=List.of(anchor(),retest(),new StrategyCandle(day,LocalTime.of(11,35),100.6,102,100.5,101.8,100L));
        var q=frame(bars,false,null,1,false);
        assertThat(engine.evaluate(key,"CE",q,q.retestFrame().evaluatedAt()).qualified()).isFalse();
    }
    @Test void nanAndInvalidOhlcFailClosed() {
        var bars=List.of(anchor(),retest(),bar(10,100.6,Double.NaN,100.5,101.8));
        assertThat(evaluate(seeded(),bars,false,null).qualified()).isFalse();
        assertThat(evaluate(seeded(),List.of(anchor(),retest(),bar(10,100.6,101,100.5,101.8)),false,null).qualified()).isFalse();
    }
}
