package org.com.sharekhan.analysis;
import org.junit.jupiter.api.Test;
import org.json.*;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
class DhanDailyBarsTest {
    final LocalDate day=LocalDate.of(2026,10,30);
    JSONObject data(){return new JSONObject().put("timestamp",new JSONArray().put(day.atStartOfDay(StockAnalysisService.IST).toEpochSecond())).put("open",new JSONArray().put(100)).put("high",new JSONArray().put(102)).put("low",new JSONArray().put(99)).put("close",new JSONArray().put(101)).put("volume",new JSONArray().put(1000));}
    @Test void readsActualShortHistoryAndUsesIstDates(){var bars=DhanDailyBars.parse(data().toString(),day.minusDays(3),day);assertThat(bars).hasSize(1);assertThat(bars.getFirst().date()).isEqualTo(day);}
    @Test void identicalCopiesCollapseButConflictsAreRejected(){var d=data();for(String key:d.keySet())d.getJSONArray(key).put(d.getJSONArray(key).get(0));assertThat(DhanDailyBars.parse(d.toString(),day,day)).hasSize(1);d.getJSONArray("close").put(1,100);assertThatThrownBy(()->DhanDailyBars.parse(d.toString(),day,day)).isInstanceOf(IllegalArgumentException.class);}
    @Test void rejectsInvalidPricesVolumesDatesAndArrayLengths(){
        var d=data();d.getJSONArray("high").put(0,99);assertThatThrownBy(()->DhanDailyBars.parse(d.toString(),day,day)).isInstanceOf(IllegalArgumentException.class);
        var fractional=data();fractional.getJSONArray("volume").put(0,1.2);assertThatThrownBy(()->DhanDailyBars.parse(fractional.toString(),day,day)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(()->DhanDailyBars.parse(data().toString(),day.minusDays(1),day.plusDays(1))).isInstanceOf(IllegalArgumentException.class);
        var lengths=data();lengths.getJSONArray("open").put(99);assertThatThrownBy(()->DhanDailyBars.parse(lengths.toString(),day,day)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->DhanDailyBars.parse(data().toString(),day.plusDays(1),day.plusDays(1))).isInstanceOf(IllegalArgumentException.class);
    }
}
