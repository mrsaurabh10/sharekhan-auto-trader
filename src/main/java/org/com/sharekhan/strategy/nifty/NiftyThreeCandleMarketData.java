package org.com.sharekhan.strategy.nifty;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.strategy.*;
import org.com.sharekhan.service.TradeExecutionService;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class NiftyThreeCandleMarketData {
    private final StrategySupport support;
    private final TradeExecutionService feeds;
    private List<StrategyCandle> cached=List.of();
    private LocalDateTime refreshed;
    public synchronized List<StrategyCandle> candles(ScriptMasterEntity spot,LocalDateTime now){
        feeds.warmUpSpotLtp(spot,"Nifty three-candle spot feed");
        var bucket=now.withMinute(now.getMinute()/5*5).withSecond(0).withNano(0);
        boolean newBar=cached.isEmpty() || LocalDateTime.of(cached.getLast().date(),cached.getLast().time()).plusMinutes(5).isBefore(bucket);
        if(refreshed==null || refreshed.isAfter(now) || (newBar && !refreshed.plusSeconds(5).isAfter(now))){
            var load=support.loadCompletedIndicatorCandles(spot,200,now);
            cached=load.candles();refreshed=now;
        }
        return List.copyOf(cached);
    }
}
