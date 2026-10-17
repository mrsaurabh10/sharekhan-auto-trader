package org.com.sharekhan.dhan;

import java.io.*;
import java.time.*;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DhanInstrumentResolverTest {
    LocalDate day=LocalDate.now(ZoneId.of("Asia/Kolkata"));
    String header="EXCH_ID,SEGMENT,SECURITY_ID,INSTRUMENT,UNDERLYING_SYMBOL,SYMBOL_NAME,SERIES,LOT_SIZE,SM_EXPIRY_DATE,STRIKE_PRICE,OPTION_TYPE,TICK_SIZE,SM_FREEZE_QTY\n";
    String cash="NSE,E,11536,EQUITY,TCS,\"Tata, Consultancy\",EQ,1.0,,-1,XX,10.0000,100000\n";
    TriggeredTradeSetupEntity trade(String symbol,String exchange) { return TriggeredTradeSetupEntity.builder().symbol(symbol).exchange(exchange).build(); }
    @Test void usesUnderlyingSymbolNotCompanyNameAndConvertsPaiseToRupees() throws Exception {
        var resolver=new DhanInstrumentResolver();resolver.load(new StringReader(header+cash),day);
        var instrument=resolver.resolve(trade("TCS","NC"));assertThat(instrument.securityId()).isEqualTo("11536");
        assertThat(instrument.tickSize()).isEqualByComparingTo("0.10");
        assertThatThrownBy(()->resolver.resolve(trade("TATA CONSULTANCY","NC"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void requiresExactOptionExpiryStrikeTypeAndRejectsAmbiguousIds() throws Exception {
        var resolver=new DhanInstrumentResolver();String expiry=day.plusDays(10).toString();
        String option="NSE,D,999,OPTSTK,TCS,TCS-option,NA,25.0,"+expiry+",4000,CE,5.0,1001\n";
        resolver.load(new StringReader(header+cash+option),day);
        var trade=trade("TCS","NF");trade.setExpiry(expiry);trade.setStrikePrice(4000d);trade.setOptionType("CE");
        assertThat(resolver.resolve(trade).securityId()).isEqualTo("999");
        trade.setOptionType("PE");assertThatThrownBy(()->resolver.resolve(trade)).isInstanceOf(IllegalArgumentException.class);
        trade.setOptionType("CE");resolver.load(new StringReader(header+cash+option+option.replace(",999,",",998,")),day);
        assertThatThrownBy(()->resolver.resolve(trade)).hasMessageContaining("ambiguous");
        trade.setExpiry(day.minusDays(1).toString());assertThatThrownBy(()->resolver.resolve(trade)).hasMessageContaining("Expired");
    }
    @Test void bseCashUsesItsOwnSecurityIdAndHandlesUnspecifiedFreeze() throws Exception {
        var resolver=new DhanInstrumentResolver();resolver.load(new StringReader(header+cash+"BSE,E,532540,EQUITY,TCS,TATA CONSULTANCY,A,1.0,,-1,XX,5.0,-1\n"),day);
        var instrument=resolver.resolve(trade("TCS","BC"));assertThat(instrument.securityId()).isEqualTo("532540");
        assertThat(instrument.segment()).isEqualTo("BSE_EQ");assertThat(instrument.freezeQuantity()).isEqualTo(Integer.MAX_VALUE);
    }
    @Test void malformedRefreshNeverReplacesTheUsableSnapshot() throws Exception {
        var resolver=new DhanInstrumentResolver();resolver.load(new StringReader(header+cash),day);
        assertThatThrownBy(()->resolver.load(new StringReader("invalid\ndata"),day)).isInstanceOf(IOException.class);
        assertThat(resolver.resolve(trade("TCS","NC")).securityId()).isEqualTo("11536");
    }
}
