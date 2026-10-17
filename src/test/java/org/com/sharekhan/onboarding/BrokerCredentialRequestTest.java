package org.com.sharekhan.onboarding;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class BrokerCredentialRequestTest {
    @Test void requiresIntegerCustomerIdAndCompleteSupportedBrokerCredentials() {
        for (String customer : new String[]{null,"", "0","-1", "1.5", "not-a-number"})
            assertThatThrownBy(() -> input(customer,"password","Sharekhan").fields(true)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> input("12345","password","Unknown").fields(true)).isInstanceOf(ResponseStatusException.class);
        assertThat(input("12345","password","sharekhan").fields(true).get("customerId")).isEqualTo(12345L);
    }
    @Test void blankEditsPreserveSecretsAndRejectOversizedUtf8Input() {
        var empty = new BrokerCredentialRequest(null,null,"",null,"",null,"",null,null);
        assertThat(empty.fields(false)).isEmpty();
        assertThatThrownBy(() -> input("12345","€".repeat(200),"Sharekhan").fields(true))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(input("12345","private-password","Sharekhan").toString()).doesNotContain("private-password");
    }
    @Test void dhanRequiresClientIdAndOneCompleteAuthenticationMethod() {
        assertThat(new BrokerCredentialRequest("Dhan",null,null,null,"012345","1000000001","JBSWY3DPEHPK3PXP",null,false).fields(true)).containsKey("brokerPassword");
        assertThat(new BrokerCredentialRequest("Dhan",null,"key",null,null,"1000000001",null,"secret",false).fields(true)).containsKey("apiKey");
        for (String pin : new String[]{"12345", "abcdef", "1234567"})
            assertThatThrownBy(()->new BrokerCredentialRequest("Dhan",null,null,null,pin,"1000000001","JBSWY3DPEHPK3PXP",null,false).fields(true)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->new BrokerCredentialRequest("Dhan",null,"key",null,null,"1000000001",null,null,false).fields(true)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->new BrokerCredentialRequest("Dhan",null,null,null,"123456","1000000001","123456",null,false).fields(true)).isInstanceOf(ResponseStatusException.class);
    }
    private BrokerCredentialRequest input(String id,String password,String broker) {
        return new BrokerCredentialRequest(broker,id,"test-api",null,password,"test-client","test-totp","test-secret",false);
    }
}
