package org.com.sharekhan.strategy.straddle;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ExpiryStraddleRulesTest {
    @Test void exactOneMinuteEntryWindowsAndSeparateExits() {
        var am=ExpiryStraddleRules.SESSIONS.getFirst();var pm=ExpiryStraddleRules.SESSIONS.getLast();
        assertThat(am.entryWindow(LocalTime.of(9,29,59))).isFalse();
        assertThat(am.entryWindow(LocalTime.of(9,30))).isTrue();
        assertThat(am.entryWindow(LocalTime.of(9,30,59))).isTrue();
        assertThat(am.entryWindow(LocalTime.of(9,31))).isFalse();
        assertThat(am.exit()).isEqualTo(LocalTime.of(10,30));
        assertThat(pm.entryWindow(LocalTime.of(13,30))).isTrue();
        assertThat(pm.exit()).isEqualTo(LocalTime.of(14,30));
    }
    @Test void validatesInputsAndUsesCommonAtmWithDeterministicTies() {
        assertThat(ExpiryStraddleRules.symbol(null)).isEqualTo("NIFTY");
        assertThat(ExpiryStraddleRules.symbol(" banknifty ")).isEqualTo("BANKNIFTY");
        assertThat(ExpiryStraddleRules.lots(null)).isOne();
        assertThatThrownBy(()->ExpiryStraddleRules.lots(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ExpiryStraddleRules.symbol("NIFTY,BANKNIFTY")).isInstanceOf(IllegalArgumentException.class);
        assertThat(ExpiryStraddleRules.atm(List.of(25050d,25000d),25025)).isEqualTo(25000);
        assertThatThrownBy(()->ExpiryStraddleRules.atm(List.of(),25000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ExpiryStraddleRules.atm(List.of(25000d),Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }
}
