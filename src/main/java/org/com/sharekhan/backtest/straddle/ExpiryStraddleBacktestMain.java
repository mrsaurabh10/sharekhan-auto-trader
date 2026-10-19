package org.com.sharekhan.backtest.straddle;

import org.com.sharekhan.strategy.StrategyCandle;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Run exported stored OHLC without starting Spring or accessing a broker. */
public final class ExpiryStraddleBacktestMain {
    public static void main(String[] args) throws Exception {
        if(args.length<2 || args.length>3) throw new IllegalArgumentException("Usage: INPUT_CSV OUTPUT_DIRECTORY [VERIFIED_EXTRA_EXPIRY_DATE]");
        var candles=new ArrayList<StrategyCandle>();
        try(var lines=Files.lines(Path.of(args[0]))) {
            lines.skip(1).filter(s->!s.isBlank()).forEach(line->{
                var c=line.split(",");var time=LocalDateTime.parse(c[0].replace(' ','T'));
                candles.add(new StrategyCandle(time.toLocalDate(),time.toLocalTime(),Double.parseDouble(c[1]),Double.parseDouble(c[2]),Double.parseDouble(c[3]),Double.parseDouble(c[4]),null));
            });
        }
        var from=candles.stream().map(StrategyCandle::date).min(Comparator.naturalOrder()).orElseThrow();
        var to=candles.stream().map(StrategyCandle::date).max(Comparator.naturalOrder()).orElseThrow();
        var expiryDates=new ArrayList<>(NiftyExpiryCalendar.nominal(from,to));
        if(args.length==3) expiryDates.add(LocalDate.parse(args[2]));
        var report=new ExpiryStraddleBacktest().run("NIFTY",1,candles,expiryDates,
                "Nominal NSE schedule: monthly last Thursday before 2019-02-11, weekly Thursday through 2025-08-31, Tuesday thereafter. Holiday-shifted dates are excluded unless explicitly supplied; missing nominal days are skipped. Full historical contract-calendar verification unavailable."+(args.length==3?" Additional verified expiry: "+args[2]:""));
        var out=Path.of(args[1]);Files.createDirectories(out);
        try(var writer=Files.newBufferedWriter(out.resolve("spot-sessions.csv"))) {
            writer.write("date,session,entry_spot,exit_spot,signed_points,absolute_points,high,low,range_points,max_up_from_entry,max_down_from_entry,complete_window\n");
            for(var r:report.observations()) writer.write(String.format(Locale.ROOT,"%s,%s,%.4f,%.4f,%.4f,%.4f,%s,%s,%s,%s,%s,%s%n",r.date(),r.session(),r.entrySpot(),r.exitSpot(),r.signedPoints(),r.absolutePoints(),cell(r.high()),cell(r.low()),cell(r.rangePoints()),cell(r.maxUpFromEntry()),cell(r.maxDownFromEntry()),r.completeWindow()));
        }
        try(var writer=Files.newBufferedWriter(out.resolve("spot-summary.csv"))) {
            writer.write("session,observations,mean_signed_points,mean_absolute_points,median_absolute_points,max_absolute_points,complete_windows,mean_range_points\n");
            for(var r:report.summaries()) writer.write(String.format(Locale.ROOT,"%s,%d,%.4f,%.4f,%.4f,%.4f,%d,%s%n",r.session(),r.observations(),r.meanSignedPoints(),r.meanAbsolutePoints(),r.medianAbsolutePoints(),r.maxAbsolutePoints(),r.completeWindows(),cell(r.meanRangePoints())));
        }
        try(var writer=Files.newBufferedWriter(out.resolve("skipped-sessions.csv"))) {
            writer.write("date,session,reason\n");for(var r:report.skipped()) writer.write(r.date()+","+r.session()+","+r.reason()+"\n");
        }
        Files.writeString(out.resolve("assumptions.txt"),report.unit()+"\n"+report.calendarPolicy()+"\nCoverage: "+from+" to "+to+"; candles="+candles.size()+"\nExact timestamp candle OPEN at entry and exit; five-minute holding-window range excludes exit candle high/low. Missing endpoint sessions skipped; incomplete ranges withheld. No volatility, premiums, spreads, slippage, fees or returns modeled. Lots do not scale spot index points.\nSkipped sessions="+report.skipped().size()+"\n");
        report.summaries().forEach(System.out::println);
        System.out.println("Skipped sessions="+report.skipped().size());
    }
    private static String cell(Double value) { return value==null?"":String.format(Locale.ROOT,"%.4f",value); }
}
