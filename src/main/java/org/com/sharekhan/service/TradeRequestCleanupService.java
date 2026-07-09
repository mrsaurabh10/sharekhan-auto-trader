package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.ws.WebSocketSubscriptionService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeRequestCleanupService {

    private static final List<TriggeredTradeStatus> CANCELLABLE_REQUEST_STATUSES = List.of(
            TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION
    );

    private final TriggerTradeRequestRepository triggerTradeRequestRepository;
    private final ScriptMasterRepository scriptMasterRepository;
    private final WebSocketSubscriptionService webSocketSubscriptionService;

    public CleanupResult cancelPendingRequestsBySource(String source) {
        if (!StringUtils.hasText(source)) {
            return new CleanupResult("", 0, 0, 0);
        }

        String normalizedSource = source.trim();
        List<TriggerTradeRequestEntity> requests = triggerTradeRequestRepository
                .findBySourceIgnoreCaseAndStatusIn(normalizedSource, CANCELLABLE_REQUEST_STATUSES);

        int cancelled = 0;
        int unsubscribeErrors = 0;
        int errors = 0;

        for (TriggerTradeRequestEntity request : requests) {
            try {
                triggerTradeRequestRepository.delete(request);
                cancelled++;
            } catch (Exception e) {
                errors++;
                log.warn("Failed cancelling {} request {}: {}", normalizedSource, request.getId(), e.getMessage(), e);
                continue;
            }

            try {
                unsubscribeRequestFeeds(request);
            } catch (Exception e) {
                unsubscribeErrors++;
                log.warn("Cancelled {} request {} but failed to unsubscribe one or more feeds: {}",
                        normalizedSource, request.getId(), e.getMessage(), e);
            }
        }

        if (cancelled > 0 || errors > 0 || unsubscribeErrors > 0) {
            log.info("Cancelled pending trade requests by source={} cancelled={} unsubscribeErrors={} errors={}",
                    normalizedSource, cancelled, unsubscribeErrors, errors);
        } else {
            log.info("No pending trade requests found for source={}", normalizedSource);
        }

        return new CleanupResult(normalizedSource, cancelled, unsubscribeErrors, errors);
    }

    private void unsubscribeRequestFeeds(TriggerTradeRequestEntity request) {
        unsubscribeFullFeed(request.getExchange(), request.getScripCode());
        unsubscribeSpotFeed(request.getSpotScripCode());
    }

    private void unsubscribeFullFeed(String exchange, Integer scripCode) {
        if (!StringUtils.hasText(exchange) || scripCode == null) {
            return;
        }
        webSocketSubscriptionService.unsubscribeFromScrip(exchange.trim() + scripCode);
    }

    private void unsubscribeSpotFeed(Integer spotScripCode) {
        if (spotScripCode == null) {
            return;
        }

        ScriptMasterEntity spotScript = scriptMasterRepository.findByScripCode(spotScripCode);
        if (spotScript == null || !StringUtils.hasText(spotScript.getExchange())) {
            log.warn("Unable to unsubscribe spot feed for scrip {} because script master row is missing", spotScripCode);
            return;
        }

        String spotKey = spotScript.getExchange().trim() + spotScript.getScripCode();
        if (isSharekhanIndexSpot(spotScript)) {
            webSocketSubscriptionService.unsubscribeFromScripLtp(spotKey);
        } else {
            webSocketSubscriptionService.unsubscribeFromScrip(spotKey);
        }
    }

    private boolean isSharekhanIndexSpot(ScriptMasterEntity spotScript) {
        Integer scripCode = spotScript.getScripCode();
        return "NC".equalsIgnoreCase(spotScript.getExchange())
                && (Integer.valueOf(20000).equals(scripCode) || Integer.valueOf(26009).equals(scripCode));
    }

    public record CleanupResult(String source, int cancelled, int unsubscribeErrors, int errors) {
    }
}
