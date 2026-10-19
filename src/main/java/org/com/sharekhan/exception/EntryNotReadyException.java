package org.com.sharekhan.exception;

/** A pre-placement wait: no broker order has been submitted and a later signal may retry. */
public class EntryNotReadyException extends InvalidTradeRequestException {
    public static final String NO_ORDER_REASON = "ENTRY_NOT_READY_NO_ORDER";
    public EntryNotReadyException(String message) { super(message); }
}
