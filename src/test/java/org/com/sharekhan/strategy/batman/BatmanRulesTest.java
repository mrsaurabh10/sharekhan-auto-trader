package org.com.sharekhan.strategy.batman;
import org.junit.jupiter.api.Test;
import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.entity.*;
import org.com.sharekhan.enums.TriggeredTradeStatus;
import java.math.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class BatmanRulesTest{
    BatmanRules.Option o(String side,double strike,double delta){return new BatmanRules.Option(side,strike,delta,99,100,10000,10000,"123");}
    List<BatmanRules.Option> chain(){return List.of(o("CE",24000,.5),o("CE",24500,.201),o("CE",24600,.16),o("PE",24000,-.5),o("PE",23500,-.198),o("PE",23400,-.161));}
    @Test void weeklyExpiriesQualifyWithoutMonthlyRestriction(){
        var day=LocalDate.of(2026,9,1);
        assertThat(BatmanRules.expiry(List.of(LocalDate.of(2026,10,13),LocalDate.of(2026,10,27)),day,d->true)).contains(LocalDate.of(2026,10,13));
    }
    @Test void selectsNearestLiquidStrikesWithoutWideningDeltaTolerance(){
        var rows=new ArrayList<>(chain());rows.set(2,new BatmanRules.Option("CE",24600,.16,99,100,65,65,"123"));
        rows.set(5,new BatmanRules.Option("PE",23400,-.161,99,100,65,65,"123"));
        rows.add(o("CE",24700,.1545));rows.add(o("PE",23300,-.1545));
        var legs=BatmanRules.select(rows,24000,65);
        assertThat(legs).extracting(x->x.option().strike()).containsExactly(24500d,23500d,24700d,23300d);
        assertThat(legs).extracting(BatmanRules.Leg::ratio).containsExactly(1,1,2,2);
        rows.removeIf(o->Math.abs(o.delta())==.1545);rows.add(o("CE",24700,.14));rows.add(o("PE",23300,-.14));
        assertThatThrownBy(()->BatmanRules.select(rows,24000,65)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void baseLotsScaleDisplayedQuantityChecks(){assertThatThrownBy(()->BatmanRules.select(chain(),24000,10001)).isInstanceOf(IllegalArgumentException.class);}
    @Test void selectsOtmActualDeltaRatioAndLongsFirst(){
        var legs=BatmanRules.select(chain(),24000);assertThat(legs).extracting(BatmanRules.Leg::action).containsExactly("BUY","BUY","SELL","SELL");
        assertThat(legs).extracting(BatmanRules.Leg::ratio).containsExactly(1,1,2,2);
        assertThat(legs).extracting(x->x.option().strike()).containsExactly(24500d,23500d,24600d,23400d);
    }
    @Test void missingOrWrongSignedDeltaCannotSubstitutePercentStrikes(){
        assertThatThrownBy(()->BatmanRules.select(List.of(o("CE",24500,.4)),24000)).isInstanceOf(IllegalArgumentException.class);
        var invalid=new ArrayList<>(chain());invalid.remove(5);invalid.add(o("PE",23400,.16));assertThatThrownBy(()->BatmanRules.select(invalid,24000)).isInstanceOf(IllegalArgumentException.class);
        invalid.set(5,o("PE",23400,Double.NaN));assertThatThrownBy(()->BatmanRules.select(invalid,24000)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void weeklyAndMonthlyExpirySelectionHonorsTradingCalendar(){
        var expiry=LocalDate.of(2026,10,27);java.util.function.Predicate<LocalDate> trading=d->d.getDayOfWeek().getValue()<6&&!d.equals(LocalDate.of(2026,9,14));
        assertThat(BatmanRules.expiry(List.of(expiry),LocalDate.of(2026,9,14),trading)).isEmpty();
        assertThat(BatmanRules.expiry(List.of(LocalDate.of(2026,10,6),LocalDate.of(2026,10,13),LocalDate.of(2026,10,20),expiry),LocalDate.of(2026,9,15),trading)).contains(expiry);
        assertThat(BatmanRules.expiry(List.of(expiry),LocalDate.of(2026,9,16),trading)).contains(expiry);
        assertThat(BatmanRules.entryWindow(LocalDateTime.of(2026,9,15,9,20))).isTrue();assertThat(BatmanRules.entryWindow(LocalDateTime.of(2026,9,15,9,25))).isTrue();
    }
    @Test void carryForwardAndNoCustomTargetsAreForced(){
        var r=new StrategyApplyRequest();r.setSymbol(" nifty ");r.setIntraday(true);BatmanRules.validate(r);assertThat(r.getSymbol()).isEqualTo("NIFTY");assertThat(r.getIntraday()).isFalse();assertThat(r.getLots()).isEqualTo(1);
        r.setRiskSettings(new org.com.sharekhan.strategy.run.StrategyRiskSettings());BatmanRules.validate(r);assertThat(r.getRiskSettings()).isNull();
        r.setProfitTargetPerLot(BigDecimal.TEN);assertThatThrownBy(()->BatmanRules.validate(r)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void actualFillsDetermineRupeeCenterProfitAndEqualStop(){
        var trades=List.of(TriggeredTradeSetupEntity.builder().entryAction("BUY").actualEntryPrice(100d).quantity(65L).build(),TriggeredTradeSetupEntity.builder().entryAction("BUY").actualEntryPrice(110d).quantity(65L).build(),TriggeredTradeSetupEntity.builder().entryAction("SELL").actualEntryPrice(70d).quantity(130L).build(),TriggeredTradeSetupEntity.builder().entryAction("SELL").actualEntryPrice(75d).quantity(130L).build());
        assertThat(BatmanRules.credit(trades)).isEqualByComparingTo("5200.00");trades.getLast().setActualEntryPrice(1d);assertThatThrownBy(()->BatmanRules.credit(trades)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void partialOrCorruptMembershipCannotArmCredit(){
        var plan=new org.json.JSONArray();var actual=new ArrayList<TriggeredTradeSetupEntity>();
        for(int i=0;i<4;i++){
            boolean shortSide=i>=2;String side=i%2==0?"CE":"PE";double strike=shortSide?24600:24500;long qty=shortSide?130:65;
            plan.put(new org.json.JSONObject().put("code",i+1).put("exchange","NF").put("side",side).put("strike",strike).put("action",shortSide?"SELL":"BUY").put("quantity",qty));
            actual.add(TriggeredTradeSetupEntity.builder().id((long)i).scripCode(i+1).exchange("NF").optionType(side).strikePrice(strike).entryAction(shortSide?"SELL":"BUY").quantity(qty).actualEntryPrice(100d).appUserId(1L).brokerCredentialsId(2L).symbol("NIFTY").expiry("27/10/2026").strategyRunId("run").source("batman:b:"+i).status(TriggeredTradeStatus.EXECUTED).intraday(false).build());
        }
        var b=BatmanBasket.builder().id("b").appUserId(1L).brokerCredentialsId(2L).symbol("NIFTY").strategyRunId("run").expiry(LocalDate.of(2026,10,27)).planJson(plan.toString()).build();
        assertThat(BatmanStrategy.matches(b,actual,true)).isTrue();assertThat(BatmanStrategy.matches(b,actual.subList(0,2),true)).isFalse();assertThat(BatmanStrategy.matches(b,actual.subList(0,2),false)).isTrue();
        actual.get(3).setQuantity(65L);assertThat(BatmanStrategy.matches(b,actual,true)).isFalse();actual.get(3).setQuantity(130L);actual.get(0).setAppUserId(9L);assertThat(BatmanStrategy.matches(b,actual,true)).isFalse();
    }
    @Test void listedWeeklyOrMonthlyUsesInclusive1To45Range(){
        var day=LocalDate.of(2026,11,12);
        assertThat(BatmanRules.expiry(List.of(day.plusDays(39),day.plusDays(40),day.plusDays(44),day.plusDays(47),day.plusDays(51)),day,d->true)).contains(day.plusDays(44));
        for(int d:List.of(1,40,45))assertThat(BatmanRules.expiry(List.of(day.plusDays(d)),day,x->true)).contains(day.plusDays(d));
        assertThat(BatmanRules.expiry(List.of(day.minusDays(1),day,day.plusDays(46)),day,d->true)).isEmpty();
        assertThat(BatmanRules.expiry(List.of(day.plusDays(45)),day,d->false)).isEmpty();
    }
    @Test void automaticEntryRetriesThroughSessionButNotBefore0920OrAfter1500(){
        var day=LocalDate.of(2026,11,16);
        assertThat(BatmanRules.entryWindow(day.atTime(9,19,59))).isFalse();
        assertThat(BatmanRules.entryWindow(day.atTime(9,20))).isTrue();
        assertThat(BatmanRules.entryWindow(day.atTime(14,59,59))).isTrue();
        assertThat(BatmanRules.entryWindow(day.atTime(15,0))).isFalse();
    }
    @Test void shorterDatedExpiryFillsGapsWithoutInventingOrSelectingToday(){
        var day=LocalDate.of(2026,11,16);
        assertThat(BatmanRules.expiry(List.of(day.plusDays(8),day.plusDays(31),day.plusDays(46)),day,d->true)).contains(day.plusDays(31));
        assertThat(BatmanRules.expiry(List.of(day,day.plusDays(1)),day,d->true)).contains(day.plusDays(1));
    }
}
