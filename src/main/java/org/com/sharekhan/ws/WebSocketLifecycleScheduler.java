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
    private static final LocalTime START_TIME = LocalTime.of(9, 10);
    private static final LocalTime END_TIME = LocalTime.of(23, 30);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Scheduled(fixedRate = 30_000) // every 30 seconds
    public void manageWebSocketConnection() {
        LocalTime now = LocalTime.now(IST);
        boolean inWindow = !now.isBefore(START_TIME) && !now.isAfter(END_TIME);

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