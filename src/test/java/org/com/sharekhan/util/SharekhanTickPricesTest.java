package org.com.sharekhan.util;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.com.sharekhan.entity.ScriptMasterEntity;
import org.com.sharekhan.repository.ScriptMasterRepository;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class SharekhanTickPricesTest {
    @Test
    void preservesFineTicksAndHandlesLargerInstrumentTicks() {
        assertThat(SharekhanTickPrices.round(1038.75, new BigDecimal("0.1"))).isEqualTo(1038.8);
        assertThat(SharekhanTickPrices.round(83.123, new BigDecimal("0.0025"))).isEqualTo(83.1225);
        assertThat(SharekhanTickPrices.round(5123.24, new BigDecimal("0.5"))).isEqualTo(5123.0);
        assertThat(SharekhanTickPrices.round(null, null, null, 0)).isZero();
    }
    @Test
    void sensexOptionWithMissingTickUsesFivePaise() {
        var repository = mock(ScriptMasterRepository.class);
        when(repository.findById(872420)).thenReturn(Optional.of(ScriptMasterEntity.builder()
                .scripCode(872420).exchange("BF").tradingSymbol("SENSEX")
                .instrumentType("OI").optionType("PE").build()));
        assertThat(SharekhanTickPrices.tickSize(repository, 872420, "BF")).isEqualByComparingTo("0.05");
        assertThat(SharekhanTickPrices.round(repository, 872420, "BF", 492.0)).isEqualTo(492.0);
        assertThat(SharekhanTickPrices.round(repository, 872420, "BF", 492.03)).isEqualTo(492.05);
    }

    @Test
    void invalidOptionTicksUseFallbackButPublishedTicksTakePrecedence() {
        var repository = mock(ScriptMasterRepository.class);
        var script = ScriptMasterEntity.builder().exchange("NF").optionType("CE").build();
        when(repository.findById(1)).thenReturn(Optional.of(script));
        for (Double tick : new Double[]{null, 0d, -1d, Double.NaN, Double.POSITIVE_INFINITY}) {
            script.setTickSize(tick);
            assertThat(SharekhanTickPrices.tickSize(repository, 1, "NF")).isEqualByComparingTo("0.05");
        }
        script.setTickSize(0.1);
        assertThat(SharekhanTickPrices.tickSize(repository, 1, "NF")).isEqualByComparingTo("0.1");
    }

    @Test
    void missingCashOrFutureTicksAndUnknownInstrumentsStillFail() {
        var repository = mock(ScriptMasterRepository.class);
        for (String type : new String[]{"EQ", "FI"}) {
            when(repository.findById(1)).thenReturn(Optional.of(ScriptMasterEntity.builder()
                    .exchange("BF").instrumentType(type).build()));
            assertThatThrownBy(() -> SharekhanTickPrices.tickSize(repository, 1, "BF"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> SharekhanTickPrices.tickSize(repository, 2, "BF"))
                .isInstanceOf(IllegalArgumentException.class);
        when(repository.findById(1)).thenReturn(Optional.of(ScriptMasterEntity.builder()
                .exchange("NF").optionType("PE").build()));
        assertThatThrownBy(() -> SharekhanTickPrices.tickSize(repository, 1, "BF"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
