package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.strategy.mcx.NaturalGasScalpingStrategy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Position-owned demand/recovery works even when the entry subscription is paused or a run is missing. */
@Service @RequiredArgsConstructor @Slf4j
public class NaturalGasScalpSessionCloseMonitor {
    private final TriggeredTradeSetupRepository trades;
    private final NaturalGasScalpSessionCloseService close;
    private final TradeExecutionService execution;
    @Scheduled(fixedDelayString="${app.strategy.ngas-session-close.poll-ms:5000}")
    public void scheduled(){monitor(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));}
    void monitor(LocalDateTime now) {
        var active=new HashSet<Long>();
        for(var t:trades.findBySourceAndStatusIn(NaturalGasScalpingStrategy.SOURCE,NaturalGasScalpSessionCloseService.ACTIVE)) {
            if(!NaturalGasScalpSessionCloseService.owns(t))continue;
            active.add(t.getId());
            try {
                close.retain(t,now.atZone(ZoneId.of("Asia/Kolkata")).toInstant());
                if(NaturalGasScalpSessionCloseService.due(t,now))execution.closeNaturalGasScalpSessionTrade(t.getId());
            } catch(RuntimeException failure) {
                log.warn("MCX_SCALP_SESSION_CLOSE_RECOVERY | trade={} | failure={}",t.getId(),failure.getClass().getSimpleName());
            }
        }
        close.releaseFlat(active);
    }
}
