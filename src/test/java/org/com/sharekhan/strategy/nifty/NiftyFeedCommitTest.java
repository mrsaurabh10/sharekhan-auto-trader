package org.com.sharekhan.strategy.nifty;

import org.com.sharekhan.entity.*;
import org.com.sharekhan.repository.*;
import org.com.sharekhan.strategy.*;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.*;
import java.util.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(NiftyFeedCommitTest.Config.class)
class NiftyFeedCommitTest {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean StrategySubscriptionRepository subscriptions(){return mock(StrategySubscriptionRepository.class);}
        @Bean TriggeredTradeSetupRepository trades(){return mock(TriggeredTradeSetupRepository.class);}
        @Bean ScriptMasterRepository scripts(){return mock(ScriptMasterRepository.class);}
        @Bean StrategySupport support(){return mock(StrategySupport.class);}
        @Bean SharedMarketDecisions sharedDecisions(){return mock(SharedMarketDecisions.class);}
        @Bean WebSocketSubscriptionService feeds(){return mock(WebSocketSubscriptionService.class);}
        @Bean DataSourceTransactionManager transactionManager(){return new DataSourceTransactionManager(new DriverManagerDataSource("jdbc:h2:mem:nifty_feed_commit;DB_CLOSE_DELAY=-1","sa",""));}
        @Bean NiftyThreeCandleFeedLifecycle lifecycle(StrategySubscriptionRepository s,TriggeredTradeSetupRepository t,StrategySupport support,ScriptMasterRepository scripts,WebSocketSubscriptionService feeds){
            return new NiftyThreeCandleFeedLifecycle(s,t,support,scripts,feeds);
        }
    }
    @Autowired ApplicationEventPublisher events;
    @Autowired DataSourceTransactionManager transactionManager;
    @Autowired StrategySubscriptionRepository subscriptions;
    @Autowired TriggeredTradeSetupRepository trades;
    @Autowired StrategySupport support;
    @Autowired WebSocketSubscriptionService feeds;
    @BeforeEach void setup(){
        clearInvocations(feeds);when(feeds.retainLtpFeed(anyString(),anyString())).thenReturn(true);
        when(subscriptions.findByStatusInOrderByIdDesc(anyList())).thenReturn(List.of(StrategySubscriptionEntity.builder().id(188L).templateId(NiftyThreeCandleRules.TEMPLATE).status("ACTIVE").build()));
        when(trades.findBySource(anyString())).thenReturn(List.of());
        when(support.resolveSpotScript("NIFTY")).thenReturn(ScriptMasterEntity.builder().scripCode(20000).exchange("NC").build());
    }
    @Test void committedActivationAcquiresFeedOnlyAfterTheSchedulingTransactionCommits(){
        new TransactionTemplate(transactionManager).executeWithoutResult(tx->{
            events.publishEvent(new StrategyFeedChanged(NiftyThreeCandleRules.TEMPLATE));verifyNoInteractions(feeds);
        });
        verify(feeds).retainLtpFeed("NIFTY_3C_SUB:188","NC20000");
    }
    @Test void rolledBackActivationNeverAcquiresAFeed(){
        new TransactionTemplate(transactionManager).executeWithoutResult(tx->{
            events.publishEvent(new StrategyFeedChanged(NiftyThreeCandleRules.TEMPLATE));tx.setRollbackOnly();
        });
        verifyNoInteractions(feeds);
    }
}
