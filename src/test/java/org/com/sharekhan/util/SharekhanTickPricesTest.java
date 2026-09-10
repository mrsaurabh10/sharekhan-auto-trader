package org.com.sharekhan.util;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SharekhanTickPricesTest {
    @Test
    void preservesFineTicksAndHandlesLargerInstrumentTicks() {
        assertThat(SharekhanTickPrices.round(1038.75, new BigDecimal("0.1"))).isEqualTo(1038.8);
        assertThat(SharekhanTickPrices.round(83.123, new BigDecimal("0.0025"))).isEqualTo(83.1225);
        assertThat(SharekhanTickPrices.round(5123.24, new BigDecimal("0.5"))).isEqualTo(5123.0);
        assertThat(SharekhanTickPrices.round(null, null, null, 0)).isZero();
    }
}
