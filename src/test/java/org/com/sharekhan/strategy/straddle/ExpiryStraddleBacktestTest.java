package org.com.sharekhan.strategy.straddle;
import org.com.sharekhan.backtest.straddle.*;
import org.com.sharekhan.strategy.StrategyCandle;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ExpiryStraddleBacktestTest {
    final LocalDate date=LocalDate.of(2026,10,13);
    List<StrategyCandle> candles() {
        var bars=new ArrayList<StrategyCandle>();
        for(var slot:ExpiryStraddleRules.SESSIONS) for(int i=0;i<=12;i++) {
            double open=25000+i*(slot.id().equals("AM")?2:-1);
            // Extreme exit-bar range must never be included in the holding window.
            bars.add(new StrategyCandle(date,slot.entry().plusMinutes(5L*i),open,i==12?30000:open+3,i==12?20000:open-3,open,10L));
        }
        return bars;
    }
    ExpiryStraddleBacktest.Report run(List<StrategyCandle> bars,int lots) {
        return new ExpiryStraddleBacktest().run(null,lots,bars,List.of(date),"test explicit expiry");
    }
    @Test void usesExactBoundaryOpenAndSignedMoveAbsoluteMoveAndHoldingRange() {
        var report=run(candles(),1);
        assertThat(report.observations()).hasSize(2);var am=report.observations().getFirst();var pm=report.observations().getLast();
        assertThat(am.signedPoints()).isEqualTo(24);assertThat(pm.signedPoints()).isEqualTo(-12);
        assertThat(pm.absolutePoints()).isEqualTo(12);assertThat(am.rangePoints()).isEqualTo(28);
        assertThat(am.high()).isEqualTo(25025);assertThat(am.low()).isEqualTo(24997);
        assertThat(report.summaries().getLast().meanAbsolutePoints()).isEqualTo(18);
        assertThat(report.summaries().getLast().meanSignedPoints()).isEqualTo(6);
        assertThat(report.unit()).contains("not option P&L");
    }
    @Test void lotsDoNotMultiplySpotPoints() {
        assertThat(run(candles(),10).observations()).isEqualTo(run(candles(),1).observations());
    }
    @Test void missingExactEndpointIsSkippedWithoutUsingAdjacentPrice() {
        var bars=candles();bars.removeIf(b->b.time().equals(LocalTime.of(10,30)));
        var report=run(bars,1);assertThat(report.observations()).hasSize(1);assertThat(report.skipped()).hasSize(1);
        assertThat(report.skipped().getFirst().session()).isEqualTo("AM");
    }
    @Test void interiorGapKeepsEndpointMoveButWithholdsRange() {
        var bars=candles();bars.removeIf(b->b.time().equals(LocalTime.of(10,0)));
        var am=run(bars,1).observations().getFirst();assertThat(am.signedPoints()).isEqualTo(24);
        assertThat(am.completeWindow()).isFalse();assertThat(am.rangePoints()).isNull();
    }
    @Test void neverUsesNonExpiryOrNextDayCandlesAndDeduplicatesExpiryInput() {
        var report=new ExpiryStraddleBacktest().run("NIFTY",1,candles(),List.of(date.plusDays(1)),"explicit");
        assertThat(report.observations()).isEmpty();
        report=new ExpiryStraddleBacktest().run("NIFTY",1,candles(),List.of(date,date),"explicit");assertThat(report.observations()).hasSize(2);
    }
    @Test void refusesInvalidDataAndDuplicateTimestamps() {
        var bars=candles();bars.add(bars.getFirst());assertThatThrownBy(()->run(bars,1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->run(List.of(new StrategyCandle(date,LocalTime.of(9,30),Double.NaN,1,1,1,null)),1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void monthlyThenWeeklyThenTuesdayScheduleWithoutInventingHolidayShifts() {
        assertThat(NiftyExpiryCalendar.nominal(LocalDate.of(2015,1,1),LocalDate.of(2015,1,31))).containsExactly(LocalDate.of(2015,1,29));
        assertThat(NiftyExpiryCalendar.nominal(LocalDate.of(2019,2,1),LocalDate.of(2019,2,28))).containsExactly(LocalDate.of(2019,2,14),LocalDate.of(2019,2,21),LocalDate.of(2019,2,28));
        assertThat(NiftyExpiryCalendar.nominal(LocalDate.of(2025,8,25),LocalDate.of(2025,9,10))).containsExactly(LocalDate.of(2025,8,28),LocalDate.of(2025,9,2),LocalDate.of(2025,9,9));
    }
}
