package org.com.sharekhan.onboarding;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class AccountRateLimitFilterTest {
    @Test void blocksFloodAndAllowsRetryAfterWindow() {
        var filter=new AccountRateLimitFilter();
        for(int i=0;i<20;i++)assertThat(filter.allow("127.0.0.1",1000)).isTrue();
        assertThat(filter.allow("127.0.0.1",1001)).isFalse();
        assertThat(filter.allow("127.0.0.2",1001)).isTrue();
        assertThat(filter.allow("127.0.0.1",901000)).isTrue();
    }
}
