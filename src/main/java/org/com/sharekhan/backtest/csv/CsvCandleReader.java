package org.com.sharekhan.backtest.csv;

import org.com.sharekhan.strategy.StrategyCandle;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Index bars are start-stamped in IST. Incomplete one-minute groups never become five-minute bars. */
public class CsvCandleReader {
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
    public record Dataset(List<StrategyCandle> candles,long rows,long outsideSession,long incompleteGroups,long missingFiveMinuteSlots,long normalizedTimestamps) {
        public Dataset(List<StrategyCandle> candles,long rows,long outsideSession,long incompleteGroups,long missingFiveMinuteSlots) {
            this(candles,rows,outsideSession,incompleteGroups,missingFiveMinuteSlots,0);
        }
    }
    public Dataset read(Reader input,int inputMinutes) throws IOException {
        if (inputMinutes!=1 && inputMinutes!=5) throw new IllegalArgumentException("inputMinutes must be 1 or 5");
        var groups=new TreeMap<LocalDateTime,Group>();
        long rows=0,outside=0,incomplete=0,missing=0,normalized=0;
        var reader=new BufferedReader(input);
        String header=reader.readLine();
        if (header==null || !header.replace("\uFEFF", "").replace("\"", "").trim().equalsIgnoreCase("date,open,high,low,close,volume"))
            throw new IllegalArgumentException("Expected date,open,high,low,close,volume header");
        String line;
        while ((line=reader.readLine())!=null) {
            if (line.isBlank()) continue;
            rows++;
            try {
                String[] fields=line.replace("\"", "").split(",",-1);
                if (fields.length!=6) throw new IllegalArgumentException("Expected six fields");
                LocalDateTime timestamp=LocalDateTime.parse(fields[0].trim(),DATE);
                if (timestamp.getSecond()!=0 || timestamp.getNano()!=0) {
                    if (inputMinutes!=1) throw new IllegalArgumentException("Expected start-stamped five-minute timestamps");
                    timestamp=timestamp.withSecond(0).withNano(0);normalized++;
                }
                if (timestamp.toLocalTime().isBefore(LocalTime.of(9,15)) || !timestamp.toLocalTime().isBefore(LocalTime.of(15,30))) { outside++; continue; }
                if (inputMinutes==5 && timestamp.getMinute()%5!=0) throw new IllegalArgumentException("Five-minute timestamp is not aligned");
                double open=Double.parseDouble(fields[1]), high=Double.parseDouble(fields[2]),low=Double.parseDouble(fields[3]),close=Double.parseDouble(fields[4]);
                long volume=Long.parseLong(fields[5].trim());
                if (!Double.isFinite(open)||!Double.isFinite(high)||!Double.isFinite(low)||!Double.isFinite(close)
                        ||low<=0||high<Math.max(open,close)||low>Math.min(open,close)||volume<0) throw new IllegalArgumentException("Invalid OHLC/volume");
                LocalDateTime bucket=timestamp.minusMinutes(timestamp.getMinute()%5);
                var group=groups.computeIfAbsent(bucket,unused->new Group());
                int slot=inputMinutes==1 ? timestamp.getMinute()%5 : 0;
                if (group.bars[slot]!=null) throw new IllegalArgumentException("Duplicate timestamp");
                group.bars[slot]=new StrategyCandle(timestamp.toLocalDate(),timestamp.toLocalTime(),open,high,low,close,volume);
            } catch (RuntimeException e) { throw new IllegalArgumentException("CSV row "+(rows+1)+": "+e.getMessage(),e); }
        }
        var candles=new ArrayList<StrategyCandle>();
        LocalDateTime previous=null;
        for (var entry:groups.entrySet()) {
            var bars=entry.getValue().bars;
            if (inputMinutes==1 && Arrays.stream(bars).anyMatch(Objects::isNull)) { incomplete++; continue; }
            StrategyCandle first=bars[0],last=inputMinutes==1 ? bars[4] : first;
            double high=first.high(),low=first.low();long volume=0;
            for (var bar:bars) if (bar!=null) { high=Math.max(high,bar.high());low=Math.min(low,bar.low());volume=Math.addExact(volume,bar.volume()); }
            var timestamp=entry.getKey();
            if (previous!=null && timestamp.toLocalDate().equals(previous.toLocalDate())) missing+=Math.max(0,java.time.Duration.between(previous,timestamp).toMinutes()/5-1);
            candles.add(new StrategyCandle(timestamp.toLocalDate(),timestamp.toLocalTime(),first.open(),high,low,last.close(),volume));
            previous=timestamp;
        }
        if (candles.isEmpty()) throw new IllegalArgumentException("No usable regular-session candles");
        return new Dataset(List.copyOf(candles),rows,outside,incomplete,missing,normalized);
    }
    private static class Group { final StrategyCandle[] bars=new StrategyCandle[5]; }
}
