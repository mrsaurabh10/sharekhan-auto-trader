package org.com.sharekhan.strategy.adxdmi;

import org.com.sharekhan.backtest.csv.CsvCandleReader;
import org.com.sharekhan.strategy.StrategyCandle;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** No Spring context, broker, database, login or order execution. */
public final class AdxDmiBacktestMain {
    private AdxDmiBacktestMain() { }
    public static void main(String[] args)throws Exception{
        var options=new HashMap<String,String>();
        for(int i=0;i<args.length;i+=2){if(i+1>=args.length || !args[i].startsWith("--"))throw new IllegalArgumentException("Expected --key value pairs");options.put(args[i].substring(2),args[i+1]);}
        Path input=Path.of(required(options,"file")),output=Path.of(required(options,"output-dir"));
        int minutes=Integer.parseInt(options.getOrDefault("input-minutes","1"));
        CsvCandleReader.Dataset dataset;
        try(var reader=Files.newBufferedReader(input)){dataset=new CsvCandleReader().read(reader,minutes);}
        var config=new AdxDmiReplay.Config(options.getOrDefault("symbol","NIFTY"),LocalDate.parse(required(options,"from")),LocalDate.parse(required(options,"to")),Boolean.parseBoolean(options.getOrDefault("morning-only","true")),!"continuous".equals(options.getOrDefault("indicator-history","rolling250")));
        String execution=options.getOrDefault("execution","tv5");
        if(!Set.of("tv5","minute").contains(execution))throw new IllegalArgumentException("execution must be tv5 or minute");
        Map<LocalDateTime,List<StrategyCandle>> sub=new HashMap<>();
        if("minute".equals(execution)){
            if(minutes!=1)throw new IllegalArgumentException("Minute execution requires one-minute input");
            try(var lines=Files.lines(input)){
                lines.skip(1).filter(s->!s.isBlank()).forEach(line->{
                    String[] f=line.replace("\"","").split(",");
                    var at=LocalDateTime.parse(f[0].replace(' ','T')).withSecond(0).withNano(0);
                    if(at.toLocalTime().isBefore(LocalTime.of(9,15)) || !at.toLocalTime().isBefore(LocalTime.of(15,30)))return;
                    var b=new StrategyCandle(at.toLocalDate(),at.toLocalTime(),Double.parseDouble(f[1]),Double.parseDouble(f[2]),Double.parseDouble(f[3]),Double.parseDouble(f[4]),Long.parseLong(f[5]));
                    sub.computeIfAbsent(at.minusMinutes(at.getMinute()%5),unused->new ArrayList<>()).add(b);
                });
            }
            for(var b:dataset.candles()){
                var at=AdxDmiRules.start(b);var group=sub.get(at);
                if(group==null || group.size()!=5)throw new IllegalArgumentException("Incomplete minute execution group "+at);
                group.sort(Comparator.comparing(StrategyCandle::time));
                for(int i=0;i<5;i++)if(!group.get(i).time().equals(at.toLocalTime().plusMinutes(i)))throw new IllegalArgumentException("Missing minute execution slot");
            }
        }
        var report=new AdxDmiReplay().run(dataset.candles(),config,sub);Files.createDirectories(output);
        new ObjectMapper().registerModule(new JavaTimeModule()).disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writerWithDefaultPrettyPrinter().writeValue(output.resolve("replay.json").toFile(),report);
        try(var writer=Files.newBufferedWriter(output.resolve("exits.csv"))){
            writer.write("cycle,side,signal_at,entry_bar,exit_bar,leg,entry,stop,target,exit,reason,points,R\n");
            for(var e:report.exits())writer.write(String.format(Locale.ROOT,"%d,%s,%s,%s,%s,%d,%.5f,%.5f,%.5f,%.5f,%s,%.5f,%.6f%n",e.cycle(),e.side(),e.signalAt(),e.entryBar(),e.exitBar(),e.leg(),e.entry(),e.stop(),e.target(),e.exit(),e.reason(),e.points(),e.r()));
        }
        Files.writeString(output.resolve("data-quality.txt"),dataset.toString().replaceAll("candles=\\[.*?], rows=","rows=")+"\nexecution="+execution+"\n");
        System.out.println(report.summary());
    }
    private static String required(Map<String,String> options,String key){String value=options.get(key);if(value==null || value.isBlank())throw new IllegalArgumentException("Missing --"+key);return value;}
}
