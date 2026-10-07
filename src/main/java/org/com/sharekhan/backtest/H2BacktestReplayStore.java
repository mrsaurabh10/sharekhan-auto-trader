package org.com.sharekhan.backtest;
import lombok.RequiredArgsConstructor; import org.com.sharekhan.entity.*; import org.com.sharekhan.repository.*; import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty; import org.springframework.stereotype.Repository; import java.util.*;
@Repository @RequiredArgsConstructor @ConditionalOnProperty(prefix="app.backtest.postgres",name="enabled",havingValue="false",matchIfMissing=true)
public class H2BacktestReplayStore implements BacktestReplayStore {
 private final BacktestReplayResultRepository results; private final BacktestReplayEventRepository events;
 public Optional<BacktestReplayResultEntity> findResult(Long id,String i,String p,String s){return results.findByTradeSetupIdAndIntervalAndTriggerPricePolicyAndSquareOffTime(id,i,p,s);}
 public BacktestReplayResultEntity saveResult(BacktestReplayResultEntity r){return results.save(r);}
 public void replaceEvents(Long id,List<BacktestReplayEventEntity> e){events.deleteByResultId(id); if(!e.isEmpty())events.saveAll(e);}
}
