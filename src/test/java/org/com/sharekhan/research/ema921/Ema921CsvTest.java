package org.com.sharekhan.research.ema921;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import static org.junit.jupiter.api.Assertions.*;
class Ema921CsvTest {
 @Test void exactIsoMinuteAndContractMetadata()throws Exception{
  var bars=Ema921Csv.spot(new StringReader("\ufefftimestamp,open,high,low,close\n2026-01-05T09:15:00,100,102,99,101\n"));assertEquals(1,bars.size());assertEquals(101,bars.get(0).close());
  var options=Ema921Csv.options(new StringReader("timestamp,contractId,listedOn,expiry,strike,right,lotSize,open,high,low,close\n2026-01-05T09:15:00,NIFTY-C1,2025-12-01,2026-01-13,23000,CE,65,100,102,99,101\n"));assertEquals(65,options.get(0).contract().lotSize());
 }
 @Test void malformedHeadersOffsetsAndOhlcRejected(){
  assertThrows(IllegalArgumentException.class,()->Ema921Csv.spot(new StringReader("date,open,high,low,close\n")));
  assertThrows(IllegalArgumentException.class,()->Ema921Csv.spot(new StringReader("timestamp,open,high,low,close\n2026-01-05T09:15:00+05:30,100,102,99,101\n")));
  assertThrows(IllegalArgumentException.class,()->Ema921Csv.spot(new StringReader("timestamp,open,high,low,close\n2026-01-05T09:15:00,100,NaN,99,101\n")));
 }
}
