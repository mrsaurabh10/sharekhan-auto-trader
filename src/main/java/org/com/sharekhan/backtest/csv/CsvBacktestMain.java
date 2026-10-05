package org.com.sharekhan.backtest.csv;

import org.com.sharekhan.config.SupertrendStrategyProperties;
import org.com.sharekhan.strategy.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Offline entry point: no Spring context, database, tokens or broker connectivity. */
public class CsvBacktestMain {
    public static void main(String[] args) throws Exception {
        Map<String,String> options=new HashMap<>();
        for(int i=0;i<args.length;i+=2) {
            if(i+1>=args.length||!args[i].startsWith("--")) throw new IllegalArgumentException("Use --file PATH --symbol NIFTY|BANKNIFTY --input-minutes 1|5 --output-dir PATH [--from DATE --to DATE --target-r 2 --slippage-points 0 --cost-points 0]");
            options.put(args[i].substring(2),args[i+1]);
        }
        var props=new SupertrendStrategyProperties();props.validate();
        var config=new SupertrendCsvBacktest.Config(required(options,"symbol"),date(options.get("from")),date(options.get("to")),
                Double.parseDouble(options.getOrDefault("target-r","2")),Double.parseDouble(options.getOrDefault("slippage-points","0")),
                Double.parseDouble(options.getOrDefault("cost-points","0")),LocalTime.parse(options.getOrDefault("square-off","15:20")));
        CsvCandleReader.Dataset dataset;
        try(var reader=Files.newBufferedReader(Path.of(required(options,"file")))) {dataset=new CsvCandleReader().read(reader,Integer.parseInt(options.getOrDefault("input-minutes","5")));}
        var result=new SupertrendCsvBacktest(new IndicatorService(),new SupertrendSignalRules(props)).run(dataset,config);
        var output=Path.of(required(options,"output-dir"));Files.createDirectories(output);
        try(var writer=Files.newBufferedWriter(output.resolve("trades.csv"))) {
            writer.write("direction,signal_at,entry_at,exit_bar_at,entry,stop,target,exit,exit_reason,net_spot_points,net_R\n");
            for(var trade:result.trades()) writer.write(String.format(Locale.ROOT,"%s,%s,%s,%s,%.4f,%.4f,%.4f,%.4f,%s,%.4f,%.6f%n",
                    trade.direction(),trade.signalAt(),trade.entryAt(),trade.exitBarAt(),trade.entry(),trade.stop(),trade.target(),trade.exit(),trade.exitReason(),trade.netPoints(),trade.netR()));
        }
        try(var writer=Files.newBufferedWriter(output.resolve("summary.csv"))) {
            writer.write("direction,trades,wins,win_rate_pct,net_spot_points,average_R,profit_factor,max_drawdown_spot_points\n");
            for(var summary:result.summary()) writer.write(String.format(Locale.ROOT,"%s,%d,%d,%.4f,%.4f,%.6f,%s,%.4f%n",
                    summary.direction(),summary.trades(),summary.wins(),summary.winRate(),summary.netPoints(),summary.averageR(),summary.profitFactor()==null?"":summary.profitFactor(),summary.maxDrawdownPoints()));
        }
        Files.writeString(output.resolve("assumptions.txt"),"SPOT INDEX POINTS, not option P&L\n"+config+"\n"+result.dataQuality()+"\n"
                +"Indicator history: rolling 250 bars, minimum 50; RSI CE50-75 PE25-50, slope off; candle colour on; ADX NIFTY>20 BANKNIFTY>18.\n"
                +"First entry per direction per day; next contiguous open; fixed single target; stop first when both levels touched; no trailing stop.\n"
                +"Skipped entry gaps="+result.skippedGaps()+"; incomplete session exits="+result.incompleteSessionExits()+"\n");
        System.out.println(config.symbol()+" "+result.dataQuality());result.summary().forEach(System.out::println);
        System.out.println("Reports: "+output.toAbsolutePath());
    }
    private static String required(Map<String,String> options,String key) {
        String value=options.get(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing --"+key);return value;
    }
    private static LocalDate date(String value){return value==null?null:LocalDate.parse(value);}
}
