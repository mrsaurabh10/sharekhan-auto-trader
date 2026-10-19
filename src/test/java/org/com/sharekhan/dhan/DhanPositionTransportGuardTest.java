package org.com.sharekhan.dhan;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class DhanPositionTransportGuardTest {
    @Test void positionFallbackNeverAllowsMutationsOrRequestBodies() {
        var client=new DhanOrderClient();
        for(String method:new String[]{"POST","PUT","DELETE","PATCH"})
            assertThatThrownBy(()->client.exchange(method,"/positions","sample",null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->client.exchange("GET","/positions","sample",new JSONObject()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
