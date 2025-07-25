package org.com.sharekhan.service;

import com.sharekhan.SharekhanConnect;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.auth.TokenLoginAutomationService;
import org.com.sharekhan.auth.TokenStoreService;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.ws.WebSocketClientService;
import org.com.sharekhan.ws.WebSocketConnector;
import org.com.sharekhan.ws.WebSocketSubscriptionHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.*;

import static org.com.sharekhan.service.TradeExecutionService.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderStatusPollingService {

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    private final TriggeredTradeSetupRepository tradeRepo;
    private final SharekhanConnect sharekhanConnect;
    private final Map<String, ScheduledFuture<?>> activePolls = new ConcurrentHashMap<>();
    @Lazy
    @Autowired
    private final WebSocketClientService webSocketClientService;
    private final TradeExecutionService tradeExecutionService;
    private final WebSocketConnector webSocketConnector;
    private final WebSocketSubscriptionHelper webSocketSubscriptionHelper;
    private final TokenStoreService   tokenStoreService;

    public void monitorOrderStatus(TriggeredTradeSetupEntity trade) {
        Runnable pollTask = () -> {
            try {

                String accessToken = tokenStoreService.getAccessToken(); // ✅ fetch fresh token


                SharekhanConnect sharekhanConnect = new SharekhanConnect(null, TokenLoginAutomationService.apiKey, accessToken);
                JSONObject response = sharekhanConnect.orderHistory(trade.getExchange(), trade.getCustomerId(), trade.getOrderId());
                JSONArray data = response.getJSONArray("data");

                TradeStatus tradeStatus = tradeExecutionService.evaluateOrderFinalStatus(trade,response);
                if(TradeStatus.FULLY_EXECUTED.equals(tradeStatus)) {
                    if(TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(trade.getStatus())) {
                        trade.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);
                    }else{
                        trade.setStatus(TriggeredTradeStatus.EXECUTED);
                    }


                    String feedKey = trade.getExchange() + trade.getScripCode();
                    webSocketSubscriptionHelper.subscribeToScrip(feedKey);
                    tradeRepo.save(trade);
                    //todo add executed price here
                    ScheduledFuture<?> future = activePolls.remove(trade.getOrderId());
                    if (future != null) {
                        future.cancel(true);
                        log.info("🛑 Polling stopped for order {}", trade.getOrderId());
                    }
                    return;
                }else if (TradeStatus.REJECTED.equals(tradeStatus)) {

                    if(TriggeredTradeStatus.EXIT_ORDER_PLACED.equals(trade.getStatus())) {
                        trade.setStatus(TriggeredTradeStatus.EXIT_FAILED);
                    }else{
                        trade.setStatus(TriggeredTradeStatus.REJECTED);
                    }


                    tradeRepo.save(trade);
                    ScheduledFuture<?> future = activePolls.remove(trade.getOrderId());
                    if (future != null) {
                        future.cancel(true);
                        log.info("🛑 Polling stopped for order {}", trade.getOrderId());
                    }
                    return;
                }
                log.info("⌛ Order still pending: {}", trade.getOrderId());
            } catch (Exception e) {
                log.error("❌ Error polling order status for {}: {}", trade.getOrderId(), e.getMessage());
            }
        };
        // Poll every 3 seconds, up to 2 minutes
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(pollTask, 0, 500, TimeUnit.MILLISECONDS);
        activePolls.put(trade.getOrderId(), future);

        // Optional: Cancel polling after 2 minutes
        executor.schedule(() -> {
            log.warn("⚠️ Stopping polling after timeout for order {}", trade.getOrderId());
        }, 2, TimeUnit.MINUTES);
    }
}