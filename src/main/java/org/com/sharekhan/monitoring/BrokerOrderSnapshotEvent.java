package org.com.sharekhan.monitoring;

/** A scoped REST response already obtained by a chase worker. */
public record BrokerOrderSnapshotEvent(Long tradeId, Long credentialId, String orderId, String payload) {
    @Override public String toString() { return "BrokerOrderSnapshotEvent[redacted]"; }
}
