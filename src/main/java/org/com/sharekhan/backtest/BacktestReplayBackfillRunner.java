package org.com.sharekhan.backtest;
import lombok.RequiredArgsConstructor;import lombok.extern.slf4j.Slf4j;import org.com.sharekhan.entity.*;import org.com.sharekhan.repository.*;import org.springframework.boot.*;import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;import org.springframework.data.domain.*;import org.springframework.stereotype.Component;
@Component @Slf4j @RequiredArgsConstructor @ConditionalOnExpression("'${app.backtest.postgres.enabled:false}' == 'true' and '${app.backtest.postgres.backfill-on-startup:false}' == 'true'")
public class BacktestReplayBackfillRunner implements ApplicationRunner{
 private final BacktestReplayResultRepository results;private final BacktestReplayEventRepository events;private final PostgresBacktestReplayStore target;
 public void run(ApplicationArguments a){copyResults();copyEvents();target.syncSequences();long hr=results.count(),he=events.count(),pr=target.countResults(),pe=target.countEvents();if(pr<hr||pe<he)throw new IllegalStateException("Backtest backfill incomplete H2="+hr+"/"+he+" PostgreSQL="+pr+"/"+pe);log.info("PostgreSQL backtest backfill complete: results={} events={}",pr,pe);}
 private void copyResults(){for(Pageable p=PageRequest.of(0,500,Sort.by("id"));;){Page<BacktestReplayResultEntity>x=results.findAll(p);target.copyResults(x.getContent());if(!x.hasNext())return;p=x.nextPageable();}}
 private void copyEvents(){for(Pageable p=PageRequest.of(0,500,Sort.by("id"));;){Page<BacktestReplayEventEntity>x=events.findAll(p);target.copyEvents(x.getContent());if(!x.hasNext())return;p=x.nextPageable();}}
}
