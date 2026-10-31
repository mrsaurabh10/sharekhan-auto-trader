package org.com.sharekhan.exception;

/** A pre-placement wait: no broker order has been submitted and a later signal may retry. */
public class EntryNotReadyException extends InvalidTradeRequestException {
    public static final String NO_ORDER_REASON = "ENTRY_NOT_READY_NO_ORDER";
    /** Exact application outcomes only. Ambiguous failures and arbitrary broker text are not retryable. */
    public static boolean isNoOrderReason(String reason) {
        return NO_ORDER_REASON.equals(reason)
                || "ENTRY_WINDOW_ELAPSED: no order submitted".equals(reason);
    }
    public EntryNotReadyException(String message) { super(message); }
}
