package org.com.sharekhan.analysis;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.*;

/** Same-source daily closing references derived from complete saved native-minute sessions. */
@Repository
public class NiftyDailyBenchmark {
    private final JdbcTemplate db;
    public NiftyDailyBenchmark(JdbcTemplate db){this.db=db;}
    public record Snapshot(String symbol,String provider,String securityId,Map<LocalDate,Double> closes,int rejectedSessions) { }
    public Snapshot read(LocalDate through) {
        if(through==null)return new Snapshot("NIFTY 50","DHAN","13",Map.of(),0);
        String sql="""
            SELECT CAST(candle_time AS DATE) session_date, COUNT(*) bars,
              COUNT(DISTINCT candle_time) unique_bars,
              MIN(candle_time) first_bar,MAX(candle_time) last_bar,
              MAX(CASE WHEN CAST(candle_time AS TIME)=TIME '15:29:00' THEN close_price END) daily_close
            FROM market_history_candles
            WHERE symbol='NIFTY' AND interval_minutes=1 AND provider='DHAN'
              AND source_segment='IDX_I' AND source_security_id='13' AND source_interval_minutes=1
              AND source_bar_count=1 AND expected_source_bars=1
              AND quality_status IN ('NATIVE_5M_WINDOW_CONFIRMED','NATIVE_PAIR_VALIDATED')
              AND candle_time>=? AND candle_time<?
              AND CAST(candle_time AS TIME)>=TIME '09:15:00' AND CAST(candle_time AS TIME)<=TIME '15:29:00'
              AND EXTRACT(SECOND FROM candle_time)=0
              AND open_price>0 AND low_price>0 AND close_price>0 AND high_price<1e12
              AND high_price>=open_price AND high_price>=close_price AND high_price>=low_price
              AND low_price<=open_price AND low_price<=close_price
            GROUP BY CAST(candle_time AS DATE) ORDER BY session_date
            """;
        var valid=new TreeMap<LocalDate,Double>();int[] rejected={0};var calendar=new SwingSessions();
        db.query(sql,r->{
            LocalDate day=r.getDate("session_date").toLocalDate();double close=r.getDouble("daily_close");
            boolean regular=day.getDayOfWeek().getValue()<6&&(!calendar.supported(day)||calendar.regular(day));
            if(regular&&r.getInt("bars")==375&&r.getInt("unique_bars")==375&&Double.isFinite(close)&&close>0
                &&r.getTimestamp("first_bar").toLocalDateTime().equals(day.atTime(9,15))
                &&r.getTimestamp("last_bar").toLocalDateTime().equals(day.atTime(15,29)))valid.put(day,close);
            else rejected[0]++;
        },through.minusDays(600).atStartOfDay(),through.plusDays(1).atStartOfDay());
        return new Snapshot("NIFTY 50","DHAN","13",Collections.unmodifiableMap(valid),rejected[0]);
    }
}
