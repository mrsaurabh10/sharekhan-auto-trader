package org.com.sharekhan.enums;

public enum TriggeredTradeStatus {
    PLACED_PENDING_CONFIRMATION,
    EXECUTED,
    REJECTED,// Waiting for entry price
    TRIGGERED,    // Order placed
    COMPLETED,    // SL or target hit
    CANCELLED,    // Manually cancelled before entry
    FAILED,
    EXIT_TRIGGERED, // newly added: SL/target hit and exit flow started
    EXIT_ORDER_PLACED,
    EXITED_SUCCESS,
    EXIT_FAILED// Failed to place order
}
