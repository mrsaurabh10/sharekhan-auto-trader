package org.com.sharekhan.dhan;
/** Socket login message sent; triggers REST catch-up, not a claim that broker authentication succeeded. */
public record DhanOrderStreamConnectedEvent(Long credentialId) {}
