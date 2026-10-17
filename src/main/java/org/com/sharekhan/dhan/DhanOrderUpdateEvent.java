package org.com.sharekhan.dhan;

/** Internal validated stream message; no authentication material is retained here. */
public record DhanOrderUpdateEvent(Long credentialId, String orderId, String correlationId, String payload) {
    @Override public String toString() { return "DhanOrderUpdate[credential="+credentialId+", redacted]"; }
}
