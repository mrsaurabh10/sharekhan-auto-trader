package org.com.sharekhan.trade;

import org.assertj.core.api.Assertions;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.service.TradeExecutionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

@SpringBootTest
@ActiveProfiles("live") // Use a safe "live" profile with actual creds
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TradeExecutionServiceLiveIT {

    @Autowired
    private TradeExecutionService tradeExecutionService;

    @Autowired
    private TriggeredTradeSetupRepository triggeredTradeRepo;

    @Autowired
    private TokenStoreService tokenStoreService;

    private TriggerTradeRequestEntity trigger;

    @BeforeAll
    void prepareTrigger() {
        // Ensure DB is clean
        //triggeredTradeRepo.deleteAll();

        trigger = TriggerTradeRequestEntity.builder()
                .symbol("DMART")
                .scripCode(87136)                         // Ensure correct scripCode
                .exchange("NF")
                .instrumentType("OS")
                .strikePrice(4300.0)
                .optionType("CE")
                .expiry("26/06/2025")                    // Use a valid expiry
                .quantity(150L)                            // Match lot size
                .entryPrice(99.0)
                .stopLoss(88.0)
                .target1(109.0)
                .target2(115.0)
                .target3(120.0)
                .trailingSl(4.0)
                .status(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION)
                .createdAt(LocalDateTime.now())
                .build();
    }

//    @Test
//    void testLiveTradeExecution() {
//        String token = tokenStoreService.getAccessToken();
//        Assertions.assertThat(token).isNotNull().isNotEmpty();
//
//        // 👇 Actually place the order via Sharekhan
//        tradeExecutionService.execute(trigger, 99.05);
//
//        List<TriggeredTradeSetupEntity> trades = triggeredTradeRepo.findAll();
//        Assertions.assertThat(trades).hasSize(1);
//
//        TriggeredTradeSetupEntity saved = trades.get(0);
//        Assertions.assertThat(saved.getSymbol()).contains("DMART");
//        Assertions.assertThat(saved.getStatus()).isEqualTo(TriggeredTradeStatus.TRIGGERED);
//
//        System.out.println("✅ LIVE ORDER placed and saved: " + saved.getId());
//    }
}
