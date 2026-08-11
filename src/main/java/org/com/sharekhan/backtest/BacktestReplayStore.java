package org.com.sharekhan.backtest;
import org.com.sharekhan.entity.*; import java.util.*;
public interface BacktestReplayStore {
 Optional<BacktestReplayResultEntity> findResult(Long tradeSetupId,String interval,String policy,String squareOffTime);
 BacktestReplayResultEntity saveResult(BacktestReplayResultEntity result);
 void replaceEvents(Long resultId,List<BacktestReplayEventEntity> events);
}
