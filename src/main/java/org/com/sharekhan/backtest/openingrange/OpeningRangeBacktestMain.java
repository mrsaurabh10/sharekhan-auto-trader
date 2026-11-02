package org.com.sharekhan.backtest.openingrange;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.com.sharekhan.backtest.csv.CsvCandleReader;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Local CSV entry point; never starts Spring or connects to a broker/database. */
public class OpeningRangeBacktestMain {
    public static void main(String[] args) throws Exception {
        var options = new HashMap<String,String>();
        var known = Set.of("file","symbol","output-dir","input-minutes","from","to","st-period","st-multiplier","target-r","max-trades","slippage-points","cost-points","square-off");
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length || !args[i].startsWith("--") || !known.contains(args[i].substring(2))
                    || options.put(args[i].substring(2),args[i+1]) != null) throw new IllegalArgumentException("Unknown, duplicate or unpaired option");
        }
        var config = new OpeningRangeBacktest.Config(required(options,"symbol"),date(options.get("from")),date(options.get("to")),
                Integer.parseInt(options.getOrDefault("st-period","10")),Double.parseDouble(options.getOrDefault("st-multiplier","3")),
                Double.parseDouble(options.getOrDefault("target-r","2")),Integer.parseInt(options.getOrDefault("max-trades","2")),
                Double.parseDouble(options.getOrDefault("slippage-points","0")),Double.parseDouble(options.getOrDefault("cost-points","0")),
                LocalTime.parse(options.getOrDefault("square-off","15:20")));
        CsvCandleReader.Dataset dataset;
        try (var reader = Files.newBufferedReader(Path.of(required(options,"file")))) {
            dataset = new CsvCandleReader().read(reader,Integer.parseInt(options.getOrDefault("input-minutes","5")));
        }
        var report = new OpeningRangeBacktest().run(dataset,config);
        var out = Path.of(required(options,"output-dir")); Files.createDirectories(out);
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("report.json").toFile(),report);
        try (var writer = Files.newBufferedWriter(out.resolve("trades.csv"))) {
            writer.write("direction,signal_at,entry_at,exit_bar_at,range_high,range_low,ema10,ema20,supertrend,entry,stop,target,exit,reason,net_points,net_R\n");
            for (var t : report.trades()) writer.write(String.format(Locale.ROOT,
                    "%s,%s,%s,%s,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f,%s,%.8f,%.8f%n",
                    t.direction(),t.signalAt(),t.entryAt(),t.exitBarAt(),t.rangeHigh(),t.rangeLow(),t.ema10(),t.ema20(),t.supertrend(),
                    t.entry(),t.stop(),t.target(),t.exit(),t.reason(),t.netPoints(),t.netR()));
        }
        System.out.println(mapper.writeValueAsString(report.summary()));
    }
    private static String required(Map<String,String> options,String key) {
        var value = options.get(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing --"+key); return value;
    }
    private static LocalDate date(String text) { return text == null ? null : LocalDate.parse(text); }
}
