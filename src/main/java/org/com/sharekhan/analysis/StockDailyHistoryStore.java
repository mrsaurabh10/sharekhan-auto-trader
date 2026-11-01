package org.com.sharekhan.analysis;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.com.sharekhan.service.SharekhanHistoricalService.HistoricalCandle;

/** Persistent market data only. No credentials, HTTP or order dependencies. */
@Service
public class StockDailyHistoryStore {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    public StockDailyHistoryStore(JdbcTemplate db,PlatformTransactionManager manager) {this.db=db;this.tx=new TransactionTemplate(manager);}
    @PostConstruct public void initialize(){new ResourceDatabasePopulator(new ClassPathResource("db/postgresql/stock-daily-history-schema.sql")).execute(Objects.requireNonNull(db.getDataSource()));}
    public record Member(String symbol,String isin,LocalDate snapshotDate) { }
    public record Saved(String symbol,String securityId,BigDecimal tick,Instant capturedAt,List<HistoricalCandle> candles) {
        public LocalDate latest(){return candles.isEmpty()?null:candles.getLast().date();}
    }
    public record Update(LocalDate requiredSession,Instant startedAt,Instant completedAt,String status,int updated,int skipped,int failed,String problem) { }
    public List<Member> members() {
        return db.query("SELECT symbol,isin,snapshot_date FROM stock_index_constituents WHERE index_name='NIFTY 200' AND snapshot_date=(SELECT max(snapshot_date) FROM stock_index_constituents WHERE index_name='NIFTY 200') ORDER BY symbol",
            (r,n)->new Member(r.getString(1),r.getString(2),r.getDate(3).toLocalDate()));
    }
    public Saved read(String symbol,LocalDate through) {
        var rows=db.query("SELECT security_id,tick_size,captured_at,candle_date,open_price,high_price,low_price,close_price,volume FROM stock_daily_candles WHERE provider='DHAN' AND exchange_segment='NSE_EQ' AND symbol=? AND candle_date<=? ORDER BY candle_date DESC LIMIT 500",
            (r,n)->new Object[]{r.getString(1),r.getBigDecimal(2),r.getTimestamp(3).toInstant(),new HistoricalCandle(r.getDate(4).toLocalDate(),LocalTime.MIDNIGHT,r.getDouble(5),r.getDouble(6),r.getDouble(7),r.getDouble(8),r.getLong(9))},symbol,through);
        if(rows.isEmpty())return new Saved(symbol,null,null,null,List.of());
        String id=(String)rows.getFirst()[0];if(rows.stream().anyMatch(r->!id.equals(r[0])))throw new IllegalStateException("Ambiguous saved instrument");
        var candles=new ArrayList<HistoricalCandle>();for(var row:rows) candles.add((HistoricalCandle)row[3]);Collections.reverse(candles);
        return new Saved(symbol,id,(BigDecimal)rows.getFirst()[1],(Instant)rows.getFirst()[2],List.copyOf(candles));
    }
    public Map<String,LocalDate> latestDates(LocalDate through) {
        var dates=new HashMap<String,LocalDate>();db.query("SELECT symbol,max(candle_date) FROM stock_daily_candles WHERE provider='DHAN' AND exchange_segment='NSE_EQ' AND candle_date<=? GROUP BY symbol",r->{dates.put(r.getString(1),r.getDate(2).toLocalDate());},through);return dates;
    }
    public Set<String> currentSymbols(LocalDate day,SwingSessions calendar) {
        var dates=new HashMap<String,Set<LocalDate>>();db.query("SELECT symbol,candle_date FROM stock_daily_candles WHERE provider='DHAN' AND exchange_segment='NSE_EQ' AND candle_date>=? AND candle_date<=?",r->{dates.computeIfAbsent(r.getString(1),k->new HashSet<>()).add(r.getDate(2).toLocalDate());},day.minusDays(60),day);
        var current=new HashSet<String>();dates.forEach((symbol,days)->{if(hasRequiredSessions(days,day,calendar))current.add(symbol);});return current;
    }
    static boolean hasRequiredSessions(Collection<LocalDate> dates,LocalDate day,SwingSessions calendar) {
        var days=new HashSet<>(dates);int count=0;for(LocalDate d=day;count<20&&calendar.supported(d);d=d.minusDays(1))if(calendar.regular(d)){if(!days.contains(d))return false;count++;}return count==20;
    }
    public Optional<Update> latestUpdate() {
        return db.query("SELECT required_session,started_at,completed_at,status,updated_count,skipped_count,failed_count,problem FROM stock_history_update_runs ORDER BY started_at DESC LIMIT 1",
            (r,n)->new Update(r.getDate(1).toLocalDate(),r.getTimestamp(2).toInstant(),r.getTimestamp(3)==null?null:r.getTimestamp(3).toInstant(),r.getString(4),r.getInt(5),r.getInt(6),r.getInt(7),r.getString(8))).stream().findFirst();
    }
    public boolean claim(LocalDate day,Instant now) {
        try {return Boolean.TRUE.equals(tx.execute(s->{
            var rows=db.query("SELECT status,started_at FROM stock_history_update_runs WHERE required_session=? FOR UPDATE",(r,n)->Map.entry(r.getString(1),r.getTimestamp(2).toInstant()),day);
            if(!rows.isEmpty()) {
                var row=rows.getFirst();if(row.getValue().plusSeconds(1800).isAfter(now))return false;
                db.update("UPDATE stock_history_update_runs SET started_at=?,completed_at=NULL,status='RUNNING',updated_count=0,skipped_count=0,failed_count=0,problem=NULL WHERE required_session=?",Timestamp.from(now),day);
            }else db.update("INSERT INTO stock_history_update_runs(required_session,started_at,status) VALUES (?,?,'RUNNING')",day,Timestamp.from(now));
            return true;
        }));}catch(org.springframework.dao.DuplicateKeyException concurrent){return false;}
    }
    public void finish(LocalDate day,Instant claim,Instant now,String status,int updated,int skipped,int failed,String problem) {
        db.update("UPDATE stock_history_update_runs SET completed_at=?,status=?,updated_count=?,skipped_count=?,failed_count=?,problem=? WHERE required_session=? AND started_at=?",Timestamp.from(now),status,updated,skipped,failed,problem,day,Timestamp.from(claim));
    }
    /** Commit one validated source response atomically. Overlap corrections keep original rows if validation/write fails. */
    public void save(Member member,String securityId,BigDecimal tick,LocalDate from,LocalDate through,List<DhanDailyBars.Candle> bars,Instant now) {
        if(bars.isEmpty()||!bars.getLast().date().equals(through))throw new IllegalArgumentException("Incomplete daily response");
        tx.executeWithoutResult(status->{
            String batch="DAILY_"+UUID.randomUUID();
            db.update("INSERT INTO stock_history_imports(id,provider,requested_from,requested_to_exclusive,required_session,created_at,instrument_count,candle_count,manifest_sha256) VALUES (?,'DHAN',?,?,?,?,1,?,?)",batch,from,through.plusDays(1),through,Timestamp.from(now),bars.size(),digest(bars.toString()));
            for(var b:bars) {
                int exists=db.queryForObject("SELECT count(*) FROM stock_daily_candles WHERE provider='DHAN' AND exchange_segment='NSE_EQ' AND security_id=? AND candle_date=?",Integer.class,securityId,b.date());
                if(exists>0) db.update("UPDATE stock_daily_candles SET symbol=?,candle_epoch=?,open_price=?,high_price=?,low_price=?,close_price=?,volume=?,tick_size=?,captured_at=?,import_id=? WHERE provider='DHAN' AND exchange_segment='NSE_EQ' AND security_id=? AND candle_date=?",member.symbol(),b.epoch(),b.open(),b.high(),b.low(),b.close(),b.volume(),tick,Timestamp.from(now),batch,securityId,b.date());
                else db.update("INSERT INTO stock_daily_candles(provider,exchange_segment,security_id,symbol,candle_date,candle_epoch,open_price,high_price,low_price,close_price,volume,tick_size,captured_at,import_id) VALUES ('DHAN','NSE_EQ',?,?,?,?,?,?,?,?,?,?,?,?)",securityId,member.symbol(),b.date(),b.epoch(),b.open(),b.high(),b.low(),b.close(),b.volume(),tick,Timestamp.from(now),batch);
            }
        });
    }
    static String digest(String text){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException();}}
}
