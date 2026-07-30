package org.com.sharekhan.enums;

public enum TriggeredTradeStatus {
    PLACED_PENDING_CONFIRMATION,
    EXECUTED,
    REJECTED,// Waiting for entry price
    TRIGGERED,    // Order placed
    /** Entry ownership was persisted and a broker submission may be in flight. */
    ENTRY_SUBMITTING,
    COMPLETED,    // SL or target hit
    CANCELLED,    // Manually cancelled before entry
    FAILED,
    EXIT_TRIGGERED, // newly added: SL/target hit and exit flow started
    TARGET_ORDER_PLACED, // Dedicated exit order for target is active
    EXIT_ORDER_PLACED,
    EXITED_SUCCESS,
    EXIT_FAILED// Failed to place order
}
