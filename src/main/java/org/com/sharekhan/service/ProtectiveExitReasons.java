package org.com.sharekhan.service;

/** Whole-position protective exits share the existing claim, reprice and recovery workflow. */
final class ProtectiveExitReasons {
    private ProtectiveExitReasons() { }
    static boolean contains(String reason,org.com.sharekhan.entity.TriggeredTradeSetupEntity trade) {
        return contains(reason) || (NaturalGasScalpSessionCloseService.REASON.equals(reason) && trade!=null
                && org.com.sharekhan.strategy.mcx.NaturalGasScalpingStrategy.SOURCE.equals(trade.getSource()));
    }
    static boolean contains(String reason) {
        return "TIME_EXIT".equals(reason) || FnoMoverExitPolicyService.isExitReason(reason) || NaturalGasScalpProfitLockService.REASON.equals(reason);
    }
}
