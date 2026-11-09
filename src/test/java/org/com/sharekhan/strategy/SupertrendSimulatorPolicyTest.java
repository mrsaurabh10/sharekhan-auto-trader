package org.com.sharekhan.strategy;

import org.com.sharekhan.dto.StrategyApplyRequest;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.repository.ScriptMasterRepository;
import org.com.sharekhan.strategy.run.StrategyRiskSettings;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupertrendSimulatorPolicyTest {
    final BrokerCredentialsRepository brokers=mock(BrokerCredentialsRepository.class);
    final ScriptMasterRepository scripts=mock(ScriptMasterRepository.class);
    final SupertrendSimulatorPolicy policy=new SupertrendSimulatorPolicy(brokers,scripts,true);
    final LocalDate day=LocalDate.of(2026,10,27);
    StrategyApplyRequest request() {
        var r=new StrategyApplyRequest();r.setTemplateId("ST_RSI_EMA_ADX_CE");r.setUserId(3L);r.setBrokerCredentialsId(4L);r.setLots(3);
        when(brokers.findById(4L)).thenReturn(Optional.of(BrokerCredentialsEntity.builder().id(4L).appUserId(3L).brokerName("Simulator").build()));return r;
    }
    @Test void simulatorOnlyBothTemplatesTrimAndOwnerChecks() {
        var r=request();assertThat(policy.applies(r)).isTrue();r.setTemplateId(" st_rsi_ema_adx_pe ");assertThat(policy.applies(r)).isTrue();
        r.setUserId(99L);assertThatThrownBy(()->policy.applies(r)).hasMessageContaining("owner mismatch");
        r.setUserId(3L);brokers.findById(4L).orElseThrow().setBrokerName("Sharekhan");assertThat(policy.applies(r)).isFalse();
        r.setTemplateId("NIFTY_THREE_CANDLE_ATM");assertThat(policy.applies(r)).isFalse();
        assertThat(new SupertrendSimulatorPolicy(brokers,scripts,false).applies(request())).isFalse();
    }
    @Test void missingBrokerFailsClosedForTargetTemplate() {
        var r=request();r.setBrokerCredentialsId(20L);assertThatThrownBy(()->policy.applies(r)).hasMessageContaining("not found");
        r.setBrokerCredentialsId(null);assertThatThrownBy(()->policy.applies(r)).hasMessageContaining("Select a broker");
    }
    @Test void expiryDayNiftyUsesActualNextListingAndNormalDaysKeepNearest() {
        when(scripts.findAllOptionExpiriesByTradingSymbolAndOptionType("NIFTY","CE"))
                .thenReturn(Arrays.asList("27/10/2026","03/11/2026","09/11/2026",null,"garbage","31/02/2026"));
        assertThat(policy.expiry("NIFTY","CE",day)).isEqualTo("03/11/2026");
        assertThat(policy.expiry("NIFTY","CE",day.minusDays(1))).isEqualTo("27/10/2026");
    }
    @Test void bankExpiryDaySkipsAllSameMonthListingsAndCrossesYear() {
        when(scripts.findAllOptionExpiriesByTradingSymbolAndOptionType("BANKNIFTY","PE"))
                .thenReturn(List.of("27/10/2026","30/10/2026","03/11/2026","23/11/2026","29/12/2026"));
        assertThat(policy.expiry("BANKNIFTY","PE",day)).isEqualTo("23/11/2026");
        when(scripts.findAllOptionExpiriesByTradingSymbolAndOptionType("BANKNIFTY","PE"))
                .thenReturn(List.of("29/12/2026","25/01/2027"));
        assertThat(policy.expiry("BANKNIFTY","PE",LocalDate.of(2026,12,29))).isEqualTo("25/01/2027");
    }
    @Test void absentNextContractNeverFallsBackToExpiryDayOrSameMonthBankContract() {
        when(scripts.findAllOptionExpiriesByTradingSymbolAndOptionType("BANKNIFTY","CE"))
                .thenReturn(List.of("27/10/2026","30/10/2026"));
        assertThatThrownBy(()->policy.expiry("BANKNIFTY","CE",day)).hasMessageContaining("No later listed month");
        when(scripts.findAllOptionExpiriesByTradingSymbolAndOptionType("BANKNIFTY","CE"))
                .thenReturn(List.of("27/10/2026","29/12/2026"));
        assertThatThrownBy(()->policy.expiry("BANKNIFTY","CE",day)).hasMessageContaining("No later listed month");
        when(scripts.findAllOptionExpiriesByTradingSymbolAndOptionType("NIFTY","CE")).thenReturn(List.of("27/10/2026"));
        assertThatThrownBy(()->policy.expiry("NIFTY","CE",day)).hasMessageContaining("No later listed");
    }
    @Test void supportsIsoAndBrokerTextDatesWithoutInventingHolidayDates() {
        when(scripts.findAllOptionExpiriesByTradingSymbolAndOptionType("NIFTY","CE"))
                .thenReturn(List.of("2026-11-09","17-NOV-2026"));
        assertThat(policy.expiry("NIFTY","CE",LocalDate.of(2026,11,9))).isEqualTo("17/11/2026");
    }
    @Test void defaultCapScalesByOriginalLotsAndDoesNotMutateInput() {
        var r=request();var cap=policy.riskSettings(r);assertThat(cap.getAmountMode()).isEqualTo(StrategyRiskSettings.AmountMode.PER_LOT);
        assertThat(cap.scale(cap.getMaxLoss(),3)).isEqualByComparingTo("9000");assertThat(r.getRiskSettings()).isNull();
        r.setLots(null);assertThat(policy.riskSettings(r).getMaxLoss()).isEqualByComparingTo("3000");assertThat(r.getLots()).isOne();
        r.setLots(0);assertThatThrownBy(()->policy.riskSettings(r)).hasMessageContaining("positive");
    }
    @Test void stricterStopsAndOtherTotalControlsKeepTheirOriginalMeaning() {
        var r=request();var prior=new StrategyRiskSettings();prior.setAmountMode(StrategyRiskSettings.AmountMode.TOTAL);
        prior.setMaxLoss(new BigDecimal("15000"));prior.setProfitTarget(new BigDecimal("20000"));r.setRiskSettings(prior);
        var cap=policy.riskSettings(r);assertThat(cap.getMaxLoss()).isEqualByComparingTo("9000");
        assertThat(cap.getProfitTarget()).isEqualByComparingTo("20000");assertThat(prior.getMaxLoss()).isEqualByComparingTo("15000");
        prior.setMaxLoss(new BigDecimal("5000"));assertThat(policy.riskSettings(r).getMaxLoss()).isEqualByComparingTo("5000");
        prior.setAmountMode(StrategyRiskSettings.AmountMode.PER_LOT);prior.setMaxLoss(new BigDecimal("4000"));assertThat(policy.riskSettings(r).getMaxLoss()).isEqualByComparingTo("3000");
        prior.setMaxLoss(new BigDecimal("2000"));assertThat(policy.riskSettings(r).getMaxLoss()).isEqualByComparingTo("2000");
    }
    @Test void liveBrokerRiskSettingsAreNotGivenSimulatorDefaults() {
        var r=request();brokers.findById(4L).orElseThrow().setBrokerName("Dhan");assertThat(policy.riskSettings(r)).isNull();
    }
    List<StrategyCandle> bars() {
        var bars=new ArrayList<StrategyCandle>();
        for(int i=0;i<50;i++)bars.add(new StrategyCandle(day,LocalTime.of(9,15).plusMinutes(i*5L),100,110,90,100,0L));
        return bars;
    }
    @Test void cePeBoundaryAcceptsEqualityAndRejectsAboveWithoutVolumeRequirement() {
        var b=bars();var last=b.getLast();
        b.set(49,new StrategyCandle(day,last.time(),100,100,68.8,100,0L));assertThat(policy.stopFailure(b,"CE")).isNull();
        b.set(49,new StrategyCandle(day,last.time(),100,100,68.79,100,0L));assertThat(policy.stopFailure(b,"CE").rule()).isEqualTo("ATR_STOP_WIDTH");
        b=bars();b.set(49,new StrategyCandle(day,last.time(),100,131.2,100,100,0L));assertThat(policy.stopFailure(b,"PE")).isNull();
        b.set(49,new StrategyCandle(day,last.time(),100,131.21,100,100,0L));assertThat(policy.stopFailure(b,"PE").rule()).isEqualTo("ATR_STOP_WIDTH");
    }
    @Test void insufficientCorruptOrZeroRangeHistoryFailsClosed() {
        assertThat(policy.stopFailure(bars().subList(0,49),"CE").rule()).isEqualTo("ATR_STOP_DATA");
        var b=bars();b.set(2,new StrategyCandle(day,LocalTime.of(9,25),Double.NaN,110,90,100,0L));
        assertThat(policy.stopFailure(b,"CE").rule()).isEqualTo("ATR_STOP_DATA");
        b.replaceAll(c->new StrategyCandle(c.date(),c.time(),100,100,100,100,0L));
        assertThat(policy.stopFailure(b,"PE").rule()).isEqualTo("ATR_STOP_DATA");
    }
}
