package org.com.sharekhan.monitoring;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.service.OrderStatusPollingService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderPlacedEventListener {

    private final OrderStatusPollingService orderStatusPollingService;

    @EventListener
    public void onOrderPlaced(OrderPlacedEvent event) {
        orderStatusPollingService.monitorOrderStatus(event.trade());
    }
}