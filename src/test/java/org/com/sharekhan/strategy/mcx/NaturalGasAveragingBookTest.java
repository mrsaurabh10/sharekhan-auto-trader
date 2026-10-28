package org.com.sharekhan.strategy.mcx;

import java.time.*;
import java.util.*;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class NaturalGasAveragingBookTest {
    static final LocalDateTime NOW=LocalDateTime.of(2026,10,28,16,5,5);
    static TriggeredTradeSetupEntity leg(long id,String run,double premium) {
        return TriggeredTradeSetupEntity.builder().id(id).triggerRequestId(id).strategyRunId(run).appUserId(1L).brokerCredentialsId(2L)
                .source(NaturalGasAveragingStrategy.SOURCE).exchange("MX").scripCode(42).symbol("NATURALGAS").optionType("CE")
                .strikePrice(295d).expiry("20/11/2026").entryAction("BUY").lots(1).quantity(1250L).actualEntryPrice(premium)
                .entryAt(NOW.minusMinutes(30).plusSeconds(id)).status(TriggeredTradeStatus.EXECUTED).build();
    }
    static TriggeredTradeSetupEntity closed(long id,String run,double entry,double exit) {
        var t=leg(id,run,entry);t.setStatus(TriggeredTradeStatus.EXITED_SUCCESS);t.setExitPrice(exit);t.setExitedAt(NOW.minusMinutes(5));return t;
    }
    static NaturalGasAveragingBook.State state(List<TriggeredTradeSetupEntity> legs,double bid) {
        return NaturalGasAveragingBook.inspect(legs,1L,2L,NOW,Map.of(42,bid),.05);
    }
    @Test void firstFillAndEveryPrefixFloorAreReconstructedAfterRestart() {
        var one=state(List.of(leg(1,"B1",20)),19.9);var two=state(List.of(leg(1,"B1",20),leg(2,"B1",19)),18.9);
        assertThat(one.problem()).isNull();assertThat(one.current("B1").stopFloor()).isCloseTo(17.7,within(.00001));
        assertThat(two.current("B1").stopFloor()).isCloseTo(18.4,within(.00001));
        assertThat(two.current("B1").stopFloor()).isGreaterThan(one.current("B1").stopFloor());
        assertThat(two.current("B1").invested()).isEqualTo(48750);assertThat(two.current("B1").quantity()).isEqualTo(2500);
        var restart=state(List.of(leg(2,"B1",19),leg(1,"B1",20)),18.9);
        assertThat(restart.current("B1").stopFloor()).isEqualTo(two.current("B1").stopFloor());
    }
    @Test void sixtyThousandDoesNotForceFourExpensiveAtmLots() {
        var s=state(List.of(leg(1,"B1",20),leg(2,"B1",19)),18.9);
        assertThat(NaturalGasAveragingBook.admit(s,"B1",18.95,18.9,1250,.05)).contains("₹60,000");
    }
    @Test void fourCheapLotsAllowedAndFifthNeverAllowed() {
        var legs=List.of(leg(1,"B1",10),leg(2,"B1",9.9),leg(3,"B1",9.8));
        assertThat(NaturalGasAveragingBook.admit(state(legs,9.7),"B1",9.75,9.7,1250,.05)).isNull();
        var all=new ArrayList<>(legs);all.add(leg(4,"B1",9.75));
        assertThat(NaturalGasAveragingBook.admit(state(all,9.7),"B1",9.75,9.7,1250,.05)).contains("four");
    }
    @Test void averagingDoesNotEraseTheExistingBasketLoss() {
        var s=state(List.of(leg(1,"B1",10),leg(2,"B1",9.6),leg(3,"B1",9.2)),8.8);
        assertThat(s.current("B1").gross()).isCloseTo(-3000,within(.00001));
        assertThat(s.dailyUsed()).isCloseTo(3300,within(.00001));
        assertThat(NaturalGasAveragingBook.admit(s,"B1",8.85,8.8,1250,.05)).contains("exit condition");
    }
    @Test void targetIsWholeBasketAfterCostsNotEachLegGross() {
        var s=state(List.of(leg(1,"B1",10),leg(2,"B1",9.8)),10.5);
        assertThat(s.current("B1").gross()).isCloseTo(1500,within(.00001));
        assertThat(s.current("B1").net()).isCloseTo(1300,within(.00001));
        assertThat(s.current("B1").targetBid()).isCloseTo(10.5,within(.00001));
        assertThat(NaturalGasAveragingBook.admit(s,"B1",10.55,10.5,1250,.05)).contains("exit condition");
    }
    @Test void costsAreReservedForEveryFillAndHigherRecordedCostsTakePrecedence() {
        var t=leg(1,"B1",10);t.setTradeCost(180d);
        var s=state(List.of(t),10);assertThat(s.dailyUsed()).isEqualTo(180);assertThat(s.current("B1").net()).isEqualTo(-180);
    }
    @Test void priorLossesRemainSpentAcrossBasketsAndProfitsDoNotRefillAllowance() {
        var loss=closed(1,"OLD1",10,8.4);var win=closed(2,"OLD2",8,10);
        var s=state(List.of(loss,win),9);
        assertThat(s.closedSpent()).isCloseTo(2200,within(.00001));
        var opened=new ArrayList<>(List.of(loss,win));opened.add(leg(3,"B3",10));
        var next=state(opened,9.9);assertThat(next.current("B3").stopFloor()).isCloseTo(9.45,within(.00001));
    }
    @Test void foreignAccountsNeverChangeBudget() {
        var foreign=closed(1,"OTHER",20,1);foreign.setAppUserId(99L);
        assertThat(state(List.of(foreign),10).dailyUsed()).isZero();
    }
    @Test void otherOpenBasketAndPartialExitPreventAdditionalLot() {
        var s=state(List.of(leg(1,"OTHER",10)),9.9);
        assertThat(NaturalGasAveragingBook.admit(s,"B1",10,9.9,1250,.05)).contains("Another");
        var partial=state(List.of(closed(1,"B1",10,10.2),leg(2,"B1",9.9)),9.8);
        assertThat(NaturalGasAveragingBook.admit(partial,"B1",9.85,9.8,1250,.05)).contains("reconciliation");
    }
    @Test void staleMarksCannotBecomeInventedBreakEvenPrices() {
        var s=NaturalGasAveragingBook.inspect(List.of(leg(1,"B1",10)),1L,2L,NOW,Map.of(),.05);
        assertThat(s.current("B1").marked()).isFalse();assertThat(s.dailyUsed()).isNaN();
        assertThat(NaturalGasAveragingBook.admit(s,"B1",10,9.9,1250,.05)).contains("marks required");
    }
    @Test void mixedContractsSidesBadClocksAndUnknownEntriesFailClosed() {
        var a=leg(1,"B1",10);var b=leg(2,"B1",9.9);b.setOptionType("PE");
        assertThat(state(List.of(a,b),10).problem()).isNotNull();
        b=leg(2,"B1",9.9);b.setScripCode(99);assertThat(state(List.of(a,b),10).problem()).isNotNull();
        b=leg(2,"B1",9.9);b.setEntryAt(NOW.plusSeconds(1));assertThat(state(List.of(b),10).problem()).isNotNull();
        b=leg(2,"B1",9.9);b.setReason("ENTRY_STATUS_UNCONFIRMED");assertThat(state(List.of(b),10).problem()).isNotNull();
    }
    @Test void boughtPutsUseTheSamePremiumRiskMathAsBoughtCalls() {
        var t=leg(1,"B1",10);t.setOptionType("PE");var s=state(List.of(t),11.1);
        assertThat(s.current("B1").net()).isCloseTo(1275,within(.00001));
    }
}
