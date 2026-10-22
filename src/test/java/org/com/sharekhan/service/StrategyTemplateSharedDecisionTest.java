package org.com.sharekhan.service;
import org.com.sharekhan.dto.*;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.strategy.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class StrategyTemplateSharedDecisionTest {
    @Test void commonSignalIsAuditedAgainstEachSubscribersOwnRequestAndRun() {
        var now=LocalDateTime.of(2026,10,22,13,30,5);var count=new AtomicInteger();
        var shared=new SharedMarketDecisions(Clock.fixed(now.atZone(StrategySupport.MARKET_ZONE).toInstant(),StrategySupport.MARKET_ZONE));
        StrategyEvaluator evaluator=new StrategyEvaluator(){
            public StrategyMetadata metadata(){return new StrategyMetadata("TEST","Test","Test","CE");}
            public StrategyApplyResponse apply(StrategyApplyRequest request){
                shared.share("TEST","NIFTY","SAME_ENTRY_SETTINGS",now,count::incrementAndGet);
                var trade=TriggerTradeRequestEntity.builder().id(request.getUserId()+100).appUserId(request.getUserId())
                        .brokerCredentialsId(request.getBrokerCredentialsId()).source("subscriber:"+request.getUserId())
                        .strategyRunId("run:"+request.getUserId()).build();
                return StrategyApplyResponse.builder().status("triggered").symbol("NIFTY").tradeRequest(trade).build();
            }
        };
        var service=new StrategyTemplateService(List.of(evaluator));var audit=mock(TradeAuditService.class);
        ReflectionTestUtils.setField(service,"sharedDecisions",shared);ReflectionTestUtils.setField(service,"tradeAudit",audit);
        var a=new StrategyApplyRequest();a.setTemplateId("TEST");a.setSymbol("NIFTY");a.setUserId(1L);a.setBrokerCredentialsId(10L);a.setLots(1);
        var b=new StrategyApplyRequest();b.setTemplateId("TEST");b.setSymbol("NIFTY");b.setUserId(2L);b.setBrokerCredentialsId(20L);b.setLots(3);
        var first=service.apply(a);var second=service.apply(b);
        assertThat(count).hasValue(1);assertThat(first.getSharedSignalIds()).isEqualTo(second.getSharedSignalIds()).hasSize(1);
        assertThat(first.getTradeRequest().getBrokerCredentialsId()).isEqualTo(10L);
        assertThat(second.getTradeRequest().getBrokerCredentialsId()).isEqualTo(20L);
        var events=org.mockito.ArgumentCaptor.forClass(TradeAuditEventEntity.class);verify(audit,times(2)).record(events.capture());
        assertThat(events.getAllValues()).extracting(TradeAuditEventEntity::getAppUserId).containsExactly(1L,2L);
        assertThat(events.getAllValues()).extracting(TradeAuditEventEntity::getTriggerRequestId).containsExactly(101L,102L);
        assertThat(events.getAllValues()).extracting(TradeAuditEventEntity::getStrategyRunId).containsExactly("run:1","run:2");
        assertThat(events.getAllValues()).extracting(TradeAuditEventEntity::getSource).containsExactly("subscriber:1","subscriber:2");
    }
}
