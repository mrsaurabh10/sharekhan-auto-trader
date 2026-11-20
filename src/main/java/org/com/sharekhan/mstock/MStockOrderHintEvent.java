package org.com.sharekhan.mstock;

/** Account-scoped wake-up only. Socket payloads never directly mutate trade state. */
public record MStockOrderHintEvent(Long credentialId) {}
