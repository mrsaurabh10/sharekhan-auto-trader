package org.com.sharekhan.strategy.adxdmi;

import java.util.Collection;
import java.util.HashSet;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;

/** Count persisted filled setup identities, independently of the number or outcome of their legs. */
final class AdxDmiDailySetupLimit {
    private AdxDmiDailySetupLimit() { }

    static void requireAvailable(Collection<TriggeredTradeSetupEntity> filledLegs,String candidateSignal) {
        var signals=new HashSet<String>();
        for(var leg:filledLegs)signals.add(identity(leg));
        // The second entered setup must still be able to place its remaining two legs.
        if(!signals.contains(candidateSignal) && signals.size()>=AdxDmiRules.MAX_DAILY_SETUPS)
            throw new IllegalStateException("ADX daily limit reached: two entered setups per symbol/account per IST day");
    }

    private static String identity(TriggeredTradeSetupEntity leg) {
        String metadata=AdxDmiEntryAdmission.decision(leg);
        String signal=AdxDmiEntryAdmission.field(metadata,"ADX_SIGNAL");
        if(signal.isBlank())signal=AdxDmiEntryAdmission.field(metadata,"signal");
        if(!signal.isBlank())return signal;
        var settings=leg.getExecutionSettings();
        if(settings!=null && settings.getTimeExitGroupId()!=null && !settings.getTimeExitGroupId().isBlank())
            return "group:"+settings.getTimeExitGroupId();
        // Outcome comments on older rows may have erased their identity. Do not reset the daily allowance.
        throw new IllegalStateException("Filled ADX setup identity unavailable; reconcile today's entries before a new setup");
    }
}
