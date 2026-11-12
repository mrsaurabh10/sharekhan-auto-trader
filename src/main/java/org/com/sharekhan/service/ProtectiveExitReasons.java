package org.com.sharekhan.service;

/** Whole-position protective exits share the existing claim, reprice and recovery workflow. */
final class ProtectiveExitReasons {
    private ProtectiveExitReasons() { }
    static boolean contains(String reason) {
        return "TIME_EXIT".equals(reason) || FnoMoverExitPolicyService.isExitReason(reason) || NaturalGasScalpProfitLockService.REASON.equals(reason);
    }
}
