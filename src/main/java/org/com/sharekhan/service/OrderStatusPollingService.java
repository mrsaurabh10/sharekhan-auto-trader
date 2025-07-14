package org.com.sharekhan.service;

import com.sharekhan.SharekhanConnect;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.ws.WebSocketClientService;
import org.com.sharekhan.ws.WebSocketConnector;
import org.com.sharekhan.ws.WebSocketSubscriptionHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.com.sharekhan.service.TradeExecutionService.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderStatusPollingService {

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    private final TriggeredTradeSetupRepository tradeRepo;
    private final SharekhanConnect sharekhanConnect;
    private final WebSocketClientService webSocketClientService;
    private final TradeExecutionService tradeExecutionService;
    private final WebSocketConnector webSocketConnector;
    private final WebSocketSubscriptionHelper webSocketSubscriptionHelper;

    public void monitorOrderStatus(TriggeredTradeSetupEntity trade) {
        Runnable pollTask = () -> {
            try {
                JSONObject response = sharekhanConnect.orderHistory("NF", trade.getCustomerId(), trade.getOrderId());
                JSONArray data = response.getJSONArray("data");

                TradeStatus tradeStatus = tradeExecutionService.evaluateOrderFinalStatus(trade,response);
                if(TradeStatus.FULLY_EXECUTED.equals(tradeStatus)) {
                    trade.setStatus(TriggeredTradeStatus.EXECUTED);
                    String feedKey = trade.getExchange() + trade.getScripCode();
                    webSocketSubscriptionHelper.subscribeToScrip(feedKey);
                    tradeRepo.save(trade);
                    //todo add executed price here
                    return;
                }else if (TradeStatus.REJECTED.equals(tradeStatus)) {
                    //stop polling stop ack
                    trade.setStatus(TriggeredTradeStatus.REJECTED);
                    tradeRepo.save(trade);
                    return;
                }
                log.info("⌛ Order still pending: {}", trade.getOrderId());

            } catch (Exception e) {
                log.error("❌ Error polling order status for {}: {}", trade.getOrderId(), e.getMessage());
            }
        };

        // Poll every 3 seconds, up to 2 minutes
        executor.scheduleAtFixedRate(pollTask, 0, 500, TimeUnit.MILLISECONDS);

        // Optional: Cancel polling after 2 minutes
        executor.schedule(() -> {
            log.warn("⚠️ Stopping polling after timeout for order {}", trade.getOrderId());
        }, 2, TimeUnit.MINUTES);
    }
}