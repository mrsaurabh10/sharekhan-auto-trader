package org.com.sharekhan.ws;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketLifecycleScheduler {

    private final WebSocketClientService webSocketClientService;
    private final org.com.sharekhan.repository.StrategySubscriptionRepository subscriptions;
    private static final LocalTime START_TIME = LocalTime.of(9, 10);
    private static final LocalTime END_TIME = LocalTime.of(23, 30);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Scheduled(fixedRate = 30_000) // every 30 seconds
    public void manageWebSocketConnection() {
        manage(java.time.LocalDateTime.now(IST));
    }
    void manage(java.time.LocalDateTime dateTime) {
        LocalTime now = dateTime.toLocalTime();
        boolean inWindow = !now.isBefore(START_TIME) && !now.isAfter(END_TIME);
        // MCX session candles need a connection before 09:00; equity entry clocks stay unchanged.
        if(!inWindow && !now.isBefore(LocalTime.of(8,55)) && now.isBefore(START_TIME)
                && dateTime.getDayOfWeek()!=java.time.DayOfWeek.SATURDAY && dateTime.getDayOfWeek()!=java.time.DayOfWeek.SUNDAY) {
            inWindow=subscriptions.findByStatusInOrderByIdDesc(java.util.List.of("ACTIVE","TRIGGERED")).stream()
                    .anyMatch(s->org.com.sharekhan.strategy.mcx.NaturalGasSession.TEMPLATE.equals(s.getTemplateId()));
        }

        if (inWindow) {
            if (!webSocketClientService.isConnected()) {
                log.info("🔄 Within trading hours. Attempting to connect WebSocket...");
                try {
                    webSocketClientService.connect();  // your logic to initialize ws connection
                } catch (Exception e) {
                    log.error("❌ WebSocket reconnection failed", e);
                }
            }
        } else {
            if (webSocketClientService.isConnected()) {
                log.info("⛔ Outside trading hours. Closing WebSocket.");
                webSocketClientService.close();
            }
        }
    }
}