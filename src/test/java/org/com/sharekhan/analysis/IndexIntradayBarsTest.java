package org.com.sharekhan.analysis;
import org.junit.jupiter.api.Test;import org.json.*;import java.time.*;import java.util.*;import static org.assertj.core.api.Assertions.*;
class IndexIntradayBarsTest {
 static final LocalDate DAY=LocalDate.of(2026,10,30);
 static IndexHistorySessions.Window window(){return new IndexHistorySessions.Window(DAY,LocalTime.of(9,15),LocalTime.of(15,30));}
 static String data(int interval,IndexHistorySessions.Window w){
  var j=new JSONObject();for(String k:List.of("timestamp","open","high","low","close","volume"))j.put(k,new JSONArray());
  for(int i=0;i<w.minutes();i+=interval){j.getJSONArray("timestamp").put(w.day().atTime(w.start()).plusMinutes(i).atZone(StockAnalysisService.IST).toEpochSecond());j.getJSONArray("open").put(100);j.getJSONArray("high").put(102);j.getJSONArray("low").put(98);j.getJSONArray("close").put(101);j.getJSONArray("volume").put(0);}
  return j.toString();
 }
 @Test void validatesNativePairWithZeroIndexVolumeAndFullSourcePrecision(){var one=IndexIntradayBars.parse(data(1,window()),1,window());var five=IndexIntradayBars.parse(data(5,window()),5,window());IndexIntradayBars.verifyPair(one,five);assertThat(one).hasSize(375);assertThat(five).hasSize(75);var j=new JSONObject(data(1,window()));j.getJSONArray("open").put(0,new java.math.BigDecimal("100.150390625"));assertThat(IndexIntradayBars.parse(j.toString(),1,window()).getFirst().open()).isEqualByComparingTo("100.150390625");}
 @Test void partialMissingConflictingMisalignedOrMalformedDataCannotBecomeComplete(){
  for(String mutation:List.of("missing","ohlc","length","time","duplicate","volume")){
   var j=new JSONObject(data(1,window()));switch(mutation){case "missing" -> {for(String k:List.of("timestamp","open","high","low","close","volume"))j.getJSONArray(k).remove(0);}case "ohlc" -> j.getJSONArray("high").put(0,99);case "length" -> j.getJSONArray("low").remove(0);case "time" -> j.getJSONArray("timestamp").put(0,j.getJSONArray("timestamp").getLong(0)+1);case "duplicate" -> j.getJSONArray("timestamp").put(1,j.getJSONArray("timestamp").getLong(0));case "volume" -> j.getJSONArray("volume").put(0,-1);}
   assertThatThrownBy(()->IndexIntradayBars.parse(j.toString(),1,window())).isInstanceOf(RuntimeException.class);
  }
 }
 @Test void missingVolumeAcceptedAndDuplicatesMustAgree(){var j=new JSONObject(data(1,window()));j.remove("volume");assertThat(IndexIntradayBars.parse(j.toString(),1,window()).getFirst().volume()).isNull();var d=new JSONObject(data(1,window()));for(String k:List.of("timestamp","open","high","low","close","volume"))d.getJSONArray(k).put(d.getJSONArray(k).get(0));assertThat(IndexIntradayBars.parse(d.toString(),1,window())).hasSize(375);d.getJSONArray("close").put(375,100);assertThatThrownBy(()->IndexIntradayBars.parse(d.toString(),1,window())).hasMessageContaining("Conflicting");}
 @Test void nativeIntervalPriceDisagreementFailsWithoutResampling(){var j=new JSONObject(data(5,window()));j.getJSONArray("high").put(0,105);var five=IndexIntradayBars.parse(j.toString(),5,window());var one=IndexIntradayBars.parse(data(1,window()),1,window());assertThatThrownBy(()->IndexIntradayBars.verifyPair(one,five)).hasMessageContaining("disagree");}
 @Test void knownShortSpecialSessionIsValidatedAgainstItsOwnWindow(){var w=new IndexHistorySessions.Window(LocalDate.of(2026,11,8),LocalTime.of(14,20),LocalTime.of(15,30));var one=IndexIntradayBars.parse(data(1,w),1,w);var five=IndexIntradayBars.parse(data(5,w),5,w);IndexIntradayBars.verifyPair(one,five);assertThat(one).hasSize(70);assertThat(five).hasSize(14);}
}
