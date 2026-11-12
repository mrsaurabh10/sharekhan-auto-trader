package org.com.sharekhan.strategy.batman;
import org.junit.jupiter.api.Test;
import org.json.*;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
class DhanOptionChainClientTest{
    @Test void parsesNativeGreeksAndSecurityIdentityWithoutInventingMissingValues(){
        var call=new JSONObject().put("security_id",12345).put("top_bid_price",99).put("top_ask_price",100).put("top_bid_quantity",130).put("top_ask_quantity",65).put("greeks",new JSONObject().put("delta",.2));
        var raw=new JSONObject().put("status","success").put("data",new JSONObject().put("last_price",24000).put("oc",new JSONObject().put("24500.000000",new JSONObject().put("ce",call).put("pe",new JSONObject()))));
        var chain=DhanOptionChainClient.parse("NIFTY",LocalDate.of(2026,12,29),raw,Instant.now());
        assertThat(chain.options()).hasSize(1);assertThat(chain.options().getFirst().securityId()).isEqualTo("12345");assertThat(chain.options().getFirst().delta()).isEqualTo(.2);assertThat(chain.spot()).isEqualTo(24000);
    }
    @Test void failedProviderResponseDoesNotBecomeEmptySuccessfulChain(){assertThatThrownBy(()->DhanOptionChainClient.parse("NIFTY",LocalDate.now(),new JSONObject().put("status","failure"),Instant.now())).isInstanceOf(IllegalStateException.class);}
}
