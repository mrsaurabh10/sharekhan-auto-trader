package org.com.sharekhan.analysis;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.sql.*;import java.time.*;import java.util.*;

/** Existing candle table plus a small per-session retry/checkpoint ledger. */
@Service
public class IndexHistoryDailyStore {
    private final JdbcTemplate db;private final TransactionTemplate tx;
    public IndexHistoryDailyStore(JdbcTemplate db,PlatformTransactionManager manager){this.db=db;tx=new TransactionTemplate(manager);}
    @PostConstruct public void initialize(){new ResourceDatabasePopulator(new ClassPathResource("db/postgresql/index-history-updates-schema.sql")).execute(Objects.requireNonNull(db.getDataSource()));}
    public record Job(String symbol,LocalDate day) { public Job {if(!Set.of("NIFTY","BANKNIFTY").contains(symbol)||day==null)throw new IllegalArgumentException("Unsupported index job");} }
    public record Checkpoints(String one,boolean oneValid,String five,boolean fiveValid) { }
    public boolean complete(String symbol,IndexHistorySessions.Window w){
        for(int interval:List.of(1,5)) {
            var times=new HashSet<LocalDateTime>();db.query("SELECT candle_time,quality_status FROM market_history_candles WHERE symbol=? AND interval_minutes=? AND candle_time>=? AND candle_time<?",r->{
                String q=r.getString(2);if(!"CROSS_INTERVAL_REVIEW".equals(q)&&!"NONSTANDARD_5M_GRID_REVIEW".equals(q))times.add(r.getTimestamp(1).toLocalDateTime());
            },symbol,interval,w.day().atTime(w.start()),w.day().atTime(w.end()));
            if(times.size()!=w.minutes()/interval)return false;
            for(int i=0;i<w.minutes()/interval;i++)if(!times.contains(w.day().atTime(w.start()).plusMinutes((long)i*interval)))return false;
        }return true;
    }
    public LocalDate latestCommon(String symbol){
        var days=new ArrayList<LocalDate>();
        for(int interval:List.of(1,5)) {
            Timestamp latest=db.queryForObject("SELECT max(candle_time) FROM market_history_candles WHERE symbol=? AND interval_minutes=?",Timestamp.class,symbol,interval);
            if(latest==null)return null;days.add(latest.toLocalDateTime().toLocalDate());
        }
        return Collections.min(days);
    }
    public List<Job> pending(LocalDate through){return db.query("SELECT symbol,session_date FROM market_history_update_sessions WHERE status<>'COMPLETE' AND session_date<=? ORDER BY CASE WHEN status='PENDING' THEN 0 ELSE 1 END,started_at NULLS FIRST,session_date",(r,n)->new Job(r.getString(1),r.getDate(2).toLocalDate()),through);}
    public void plan(Job j){db.update("INSERT INTO market_history_update_sessions(symbol,session_date,status) VALUES (?,?,'PENDING') ON CONFLICT DO NOTHING",j.symbol(),j.day());}
    public void markCurrent(Job j,IndexHistorySessions.Window w,Instant now){db.update("UPDATE market_history_update_sessions SET status='COMPLETE',completed_at=?,one_count=?,five_count=?,problem=NULL WHERE symbol=? AND session_date=? AND status<>'COMPLETE' AND (status<>'RUNNING' OR started_at<?)",Timestamp.from(now),w.minutes(),w.minutes()/5,j.symbol(),j.day(),Timestamp.from(now.minusSeconds(1800)));}
    public boolean claim(Job j,Instant now){
        return db.update("UPDATE market_history_update_sessions SET status='RUNNING',started_at=?,completed_at=NULL,problem=NULL WHERE symbol=? AND session_date=? AND (started_at IS NULL OR started_at<=?)",Timestamp.from(now),j.symbol(),j.day(),Timestamp.from(now.minusSeconds(1800)))==1;
    }
    public Checkpoints checkpoints(Job j){return db.queryForObject("SELECT raw_one,one_valid,raw_five,five_valid FROM market_history_update_sessions WHERE symbol=? AND session_date=?",(r,n)->new Checkpoints(r.getString(1),r.getBoolean(2),r.getString(3),r.getBoolean(4)),j.symbol(),j.day());}
    public void checkpoint(Job j,Instant claim,int interval,String raw,boolean valid){
        String col=interval==1?"one":interval==5?"five":null;if(col==null)throw new IllegalArgumentException();
        int n=db.update("UPDATE market_history_update_sessions SET raw_"+col+"=?,"+col+"_valid=? WHERE symbol=? AND session_date=? AND started_at=? AND status='RUNNING'",raw,valid,j.symbol(),j.day(),Timestamp.from(claim));if(n!=1)throw new IllegalStateException("Index update lease lost");
    }
    public void invalidatePair(Job j,Instant claim){db.update("UPDATE market_history_update_sessions SET one_valid=FALSE,five_valid=FALSE WHERE symbol=? AND session_date=? AND started_at=?",j.symbol(),j.day(),Timestamp.from(claim));}
    public void fail(Job j,Instant claim,Instant now,String problem){db.update("UPDATE market_history_update_sessions SET status='RETRY',completed_at=?,problem=? WHERE symbol=? AND session_date=? AND started_at=? AND status='RUNNING'",Timestamp.from(now),problem,j.symbol(),j.day(),Timestamp.from(claim));}
    public void save(Job j,IndexHistorySessions.Window w,List<IndexIntradayBars.Candle> one,List<IndexIntradayBars.Candle> five,Instant claim,Instant now){
        tx.executeWithoutResult(status->{
            var leases=db.queryForList("SELECT status FROM market_history_update_sessions WHERE symbol=? AND session_date=? AND started_at=? FOR UPDATE",String.class,j.symbol(),j.day(),Timestamp.from(claim));
            if(leases.size()!=1||!"RUNNING".equals(leases.getFirst()))throw new IllegalStateException("Index update lease lost");
            for(int interval:List.of(5,1))for(var b:interval==1?one:five) {
                db.update("INSERT INTO market_history_candles(id,symbol,candle_time,interval_minutes,open_price,high_price,low_price,close_price,volume,provider,captured_at,source_segment,source_security_id,source_epoch,source_interval_minutes,source_bar_count,expected_source_bars,quality_status,import_chunk_key) VALUES (?,?,?,?,?,?,?,?,?,'DHAN',?,'IDX_I',?,?,?,1,1,?,?) ON CONFLICT DO NOTHING",
                    j.symbol()+"@"+b.time()+(interval==5?"":"@1"),j.symbol(),b.time(),interval,b.open(),b.high(),b.low(),b.close(),b.volume(),LocalDateTime.ofInstant(now,StockAnalysisService.IST),j.symbol().equals("NIFTY")?"13":"25",b.epoch(),interval,w.special()?"VERIFIED_SPECIAL_SESSION":"NATIVE_PAIR_VALIDATED","DAILY_"+j.symbol()+"_"+j.day());
            }
            if(!complete(j.symbol(),w))throw new IllegalStateException("Saved index session remains incomplete or under review");
            db.update("UPDATE market_history_update_sessions SET status='COMPLETE',completed_at=?,one_count=?,five_count=?,problem=NULL WHERE symbol=? AND session_date=? AND started_at=?",Timestamp.from(now),one.size(),five.size(),j.symbol(),j.day(),Timestamp.from(claim));
        });
    }
}
