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
    private BrokerCredentialRequest input(String id,String password,String broker) {
        return new BrokerCredentialRequest(broker,id,"test-api",null,password,"test-client","test-totp","test-secret",false);
    }
}
