package org.com.sharekhan.service;
import org.junit.jupiter.api.Test;
import org.json.JSONObject;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
class ShoonyaSnapshotTimeTest {
 @Test void providerResponseTimeDefinesFreshnessOfCurrentRestSnapshot(){
  var result=ShoonyaQuoteService.snapshotTime(new JSONObject().put("request_time","11:31:15 13-11-2026"),Instant.parse("2026-11-13T05:00:00Z"));
  assertThat(result).isEqualTo(Instant.parse("2026-11-13T06:01:15Z"));
 }
 @Test void malformedResponseTimestampCannotFallBackToLastChange(){assertThat(ShoonyaQuoteService.snapshotTime(new JSONObject().put("request_time","bad time"),Instant.now())).isNull();}
 @Test void impossibleDateCannotProduceSnapshot(){assertThat(ShoonyaQuoteService.snapshotTime(new JSONObject().put("request_time","11:31:15 31-02-2026"),Instant.now())).isNull();}
 @Test void absentResponseTimeKeepsStrictLegacyLastChangeGuard(){var last=Instant.now();assertThat(ShoonyaQuoteService.snapshotTime(new JSONObject(),last)).isEqualTo(last);}
}
