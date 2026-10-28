package org.com.sharekhan.strategy.mcx;

import java.time.*;
import java.util.*;
import org.com.sharekhan.strategy.StrategyCandle;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class NaturalGasAveragingSignalsTest {
    static final LocalDateTime NOW=LocalDateTime.of(2026,10,28,11,5,5);
    static List<StrategyCandle> bars(boolean bull) {
        var rs=new ArrayList<StrategyCandle>();
        for(int i=0;i<80;i++)rs.add(bar(NOW.minusDays(1).toLocalDate().atTime(9,0).plusMinutes(5L*i),300+i*.03));
        for(int i=0;i<25;i++)rs.add(bar(NOW.toLocalDate().atTime(9,0).plusMinutes(5L*i),304+i*.1));
        if(bull)return rs;
        return rs.stream().map(c->new StrategyCandle(c.date(),c.time(),600-c.open(),600-c.low(),600-c.high(),600-c.close(),c.volume())).toList();
    }
    static StrategyCandle bar(LocalDateTime t,double c){return new StrategyCandle(t.toLocalDate(),t.toLocalTime(),c-.05,c+.2,c-.2,c,100L);}
    @Test void aggregatesOnlyThreeContiguousClosedFiveMinuteBars() {
        var rs=bars(true);var grouped=NaturalGasAveragingSignals.aggregate(rs);
        var last=grouped.getLast();assertThat(last.time()).isEqualTo(LocalTime.of(10,45));
        assertThat(last.close()).isCloseTo(306.3,within(.00001));
        var missing=new ArrayList<>(rs);missing.removeIf(c->c.date().equals(NOW.toLocalDate())&&c.time().equals(LocalTime.of(10,50)));
        assertThat(NaturalGasAveragingSignals.aggregate(missing).getLast().time()).isEqualTo(LocalTime.of(10,30));
    }
    @Test void fifteenMinuteTrendChoosesCallsAndPutsSymmetrically() {
        var ce=NaturalGasAveragingSignals.inspect(bars(true),NOW);var pe=NaturalGasAveragingSignals.inspect(bars(false),NOW);
        assertThat(ce.trendReady()).isTrue();assertThat(ce.healthy()).isTrue();assertThat(ce.side()).isEqualTo("CE");assertThat(ce.alignedFive()).isTrue();
        assertThat(pe.healthy()).isTrue();assertThat(pe.side()).isEqualTo("PE");assertThat(pe.alignedFive()).isTrue();
        assertThat(ce.reversed("PE")).isTrue();assertThat(pe.reversed("CE")).isTrue();assertThat(ce.reversed("CE")).isFalse();
    }
    @Test void priorSessionSeedAllowsFirstCompleteFifteenMinuteDecision() {
        var rs=bars(true).stream().filter(c->c.date().isBefore(NOW.toLocalDate())||c.time().isBefore(LocalTime.of(9,15))).toList();
        var snap=NaturalGasAveragingSignals.inspect(rs,NOW.withHour(9).withMinute(15));
        assertThat(snap.trendReady()).isTrue();assertThat(snap.alignedFive()).isTrue();
    }
    @Test void formingFiveMinuteSpikeCannotChangeSignalOrTrend() {
        var rs=new ArrayList<>(bars(true));rs.add(bar(NOW.toLocalDate().atTime(11,5),900));
        var baseline=NaturalGasAveragingSignals.inspect(bars(true),NOW);var actual=NaturalGasAveragingSignals.inspect(rs,NOW);
        assertThat(actual.context()).isEqualTo(baseline.context());assertThat(actual.close()).isEqualTo(baseline.close());
    }
    @Test void missingLatestFifteenMinuteBucketCannotBeBridgedOrCalledReversal() {
        var rs=bars(true).stream().filter(c->!c.date().equals(NOW.toLocalDate())||!c.time().equals(LocalTime.of(10,50))).toList();
        var snapshot=NaturalGasAveragingSignals.inspect(rs,NOW);
        assertThat(snapshot.trendReady()).isFalse();assertThat(snapshot.reversed("CE")).isFalse();
    }
    @Test void missingLatestFiveMinuteBarPausesEntriesWithoutDiscardingCompletedTrend() {
        var rs=new ArrayList<>(bars(true));rs.removeLast();var s=NaturalGasAveragingSignals.inspect(rs,NOW);
        assertThat(s.trendReady()).isTrue();assertThat(s.alignedFive()).isFalse();assertThat(s.side()).isEqualTo("CE");
    }
    @Test void actualPullbackAndPreviousFillAnchorAreAvailableWithoutFutureData() {
        var rs=new ArrayList<>(bars(true));var t=NOW.toLocalDate().atTime(11,0);
        rs.set(rs.size()-1,new StrategyCandle(t.toLocalDate(),t.toLocalTime(),306.35,306.4,305.7,306d,100L));
        var s=NaturalGasAveragingSignals.inspect(rs,NOW);
        assertThat(s.healthy()).isTrue();assertThat(s.pullback()).isTrue();
        assertThat(s.previousFillClose(NOW.withMinute(0)).orElseThrow()).isCloseTo(306.3,within(.00001));
    }
    @Test void completedCandleFreshnessAndInvalidRowsAreRejected() {
        var s=NaturalGasAveragingSignals.inspect(bars(true),NOW);
        assertThat(s.fresh(NOW)).isTrue();assertThat(s.fresh(NOW.plusSeconds(80))).isFalse();
        var rs=new ArrayList<>(bars(true));rs.add(rs.getFirst());assertThat(NaturalGasAveragingSignals.inspect(rs,NOW).trendReady()).isFalse();
        rs=new ArrayList<>(bars(true));rs.add(bar(NOW.plusMinutes(10),300));assertThat(NaturalGasAveragingSignals.inspect(rs,NOW).trendReady()).isFalse();
    }
}
