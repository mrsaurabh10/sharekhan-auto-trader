package org.com.sharekhan.cache;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class LtpPriceObservationTest {
    @Test void observationKeepsPriceAndTimestampFromOneUpdate() {
        var cache=new LtpCacheService();var at=LocalDateTime.of(2026,11,6,10,0);
        cache.updateLtpAt(1,100,at);var old=cache.getPriceObservation(1);
        cache.updateLtpAt(1,101,at.plusSeconds(1));var next=cache.getPriceObservation(1);
        assertThat(old.price()).isEqualTo(100);assertThat(old.observedAt()).isEqualTo(at);
        assertThat(next.price()).isEqualTo(101);assertThat(next.observedAt()).isEqualTo(at.plusSeconds(1));
    }
    @Test void untimestampedUpdateCannotBorrowAnOlderTimestamp() {
        var cache=new LtpCacheService();cache.updateLtpAt(1,100,LocalDateTime.of(2026,11,6,10,0));
        cache.updateLtpAt(1,101,null);
        assertThat(cache.getPriceObservation(1).observedAt()).isNull();
    }
    @Test void websocketAndGeneralUpdatesPublishAndRemovalClearsObservations() {
        var cache=new LtpCacheService();var at=LocalDateTime.of(2026,11,6,10,0);
        cache.updateSharekhanWebSocketLtpAt(1,100,at);
        assertThat(cache.getPriceObservation(1).price()).isEqualTo(100);
        cache.removeLtp(1);assertThat(cache.getPriceObservation(1)).isNull();
        cache.updateLtpAt(1,101,at);cache.updateLtpAt(2,200,at);
        cache.clearAll();assertThat(cache.getPriceObservation(1)).isNull();assertThat(cache.getPriceObservation(2)).isNull();
    }
}
