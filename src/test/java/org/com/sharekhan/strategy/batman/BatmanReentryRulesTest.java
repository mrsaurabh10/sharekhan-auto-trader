package org.com.sharekhan.strategy.batman;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class BatmanReentryRulesTest{
 final LocalDate day=LocalDate.of(2026,11,16);
 BatmanBasket basket(){return BatmanBasket.builder().id("prior").appUserId(1L).brokerCredentialsId(2L).symbol("NIFTY").strategyRunId("r").claimedAt(day.minusDays(3).atTime(9,20)).expiry(day.plusDays(43)).build();}
 StrategyRun run(String reason){return StrategyRun.builder().id("r").appUserId(1L).brokerCredentialsId(2L).symbol("NIFTY").templateId(BatmanRules.TEMPLATE).status("CLOSED").exitReason(reason).closedAt(day.minusDays(3).atTime(14,0)).build();}
 TriggeredTradeSetupEntity trade(){return TriggeredTradeSetupEntity.builder().strategyRunId("r").appUserId(1L).brokerCredentialsId(2L).triggerRequestId(10L).status(TriggeredTradeStatus.EXITED_SUCCESS).build();}
 TriggerTradeRequestEntity request(){return TriggerTradeRequestEntity.builder().id(10L).strategyRunId("r").appUserId(1L).brokerCredentialsId(2L).status(TriggeredTradeStatus.EXECUTED).build();}
 @Test void bothTargetAndStopPermitNextTradingDayWithFreshBasket(){for(String reason:List.of("STRATEGY_PROFIT_TARGET","STRATEGY_MAX_LOSS")){var r=run(reason);assertThat(BatmanReentryRules.confirmedFlat(r,List.of(trade()),List.of(request()))).isTrue();assertThat(BatmanReentryRules.cooldown(basket(),r,day)).isNull();}}
 @Test void actualCloseTodayBlocksEvenWhenTargetWasTriggeredYesterday(){var r=run("STRATEGY_PROFIT_TARGET");r.setClosedAt(day.atTime(9,19));assertThat(BatmanReentryRules.cooldown(basket(),r,day)).isNotNull();}
 @Test void sameDayEntryCannotRepeatAfterFastExit(){var b=basket();b.setClaimedAt(day.atTime(9,20));assertThat(BatmanReentryRules.cooldown(b,run("STRATEGY_MAX_LOSS"),day)).isNotNull();}
 @Test void pendingPartialLateFillOrUnknownRequestIsNeverFlat(){var r=run("STRATEGY_MAX_LOSS");var t=trade();t.setStatus(TriggeredTradeStatus.EXECUTED);assertThat(BatmanReentryRules.confirmedFlat(r,List.of(t),List.of(request()))).isFalse();t.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);var q=request();q.setReason("ENTRY_SUBMISSION_STATE_UNKNOWN");assertThat(BatmanReentryRules.confirmedFlat(r,List.of(t),List.of(q))).isFalse();q.setReason(null);q.setStatus(TriggeredTradeStatus.PLACED_PENDING_CONFIRMATION);assertThat(BatmanReentryRules.confirmedFlat(r,List.of(t),List.of(q))).isFalse();}
 @Test void missingRunClosingRunOrExecutedRequestWithoutTradeBlocks(){var r=run("STRATEGY_MAX_LOSS");assertThat(BatmanReentryRules.confirmedFlat(null,List.of(),List.of())).isFalse();r.setStatus("CLOSING");assertThat(BatmanReentryRules.confirmedFlat(r,List.of(trade()),List.of(request()))).isFalse();r.setStatus("CLOSED");assertThat(BatmanReentryRules.confirmedFlat(r,List.of(),List.of(request()))).isFalse();}
 @Test void foreignOwnerOrBrokerCannotSatisfyFlatProof(){var t=trade();t.setBrokerCredentialsId(9L);assertThat(BatmanReentryRules.confirmedFlat(run("STRATEGY_PROFIT_TARGET"),List.of(t),List.of(request()))).isFalse();}
 @Test void anyConfirmedExitPermitsNextDayBeforeOriginalExpiry(){for(String reason:List.of("STRATEGY_MANUAL_CLOSE","BATMAN_ENTRY_ABORT","STRATEGY_CLOSE_ALL","BATMAN_EXPIRY_EXIT"))assertThat(BatmanReentryRules.cooldown(basket(),run(reason),day)).isNull();}
 @Test void oldExpiredCycleDoesNotBlockNewInitialEntry(){var b=basket();b.setExpiry(day.minusDays(1));assertThat(BatmanReentryRules.cooldown(b,run("BATMAN_EXPIRY_EXIT"),day)).isNull();}
}
