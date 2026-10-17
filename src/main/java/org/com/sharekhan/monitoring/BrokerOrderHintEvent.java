package org.com.sharekhan.monitoring;

/** Socket ACKs require one authoritative status read; a null order means reconnect catch-up. */
public record BrokerOrderHintEvent(String broker, String orderId) {}
