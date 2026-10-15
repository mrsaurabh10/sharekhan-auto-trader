package org.com.sharekhan.onboarding;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountMailServiceTest {
    private final JavaMailSender sender=mock(JavaMailSender.class);
    private AccountMailService service(String origin) {
        var factory=new DefaultListableBeanFactory();factory.registerSingleton("mail",sender);
        var service=new AccountMailService(factory.getBeanProvider(JavaMailSender.class));
        ReflectionTestUtils.setField(service,"baseUrl",origin);
        ReflectionTestUtils.setField(service,"from","noreply@example.test");return service;
    }
    @Test void configurationRequiresAnHttpsOriginWithoutUserInfoQueryOrFragment() {
        for(String origin:new String[]{"http://example.test","https://","https://user:password@example.test","https://example.test?next=other","https://example.test/#other","https://example.test/nested"})
            assertThat(service(origin).configured()).isFalse();
        assertThat(service("https://example.test/").configured()).isTrue();
    }
    @Test void mailUsesConfiguredOriginAndFragmentToken() {
        service("https://example.test/").send("alex@example.test","test-token","VERIFY");
        var message=org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);verify(sender).send(message.capture());
        assertThat(message.getValue().getText()).contains("https://example.test/account?mode=verify#token=test-token");
    }
    @Test void providerErrorsAreSanitized() {
        doThrow(new MailSendException("sensitive-provider-detail")).when(sender).send(any(SimpleMailMessage.class));
        assertThatThrownBy(()->service("https://example.test").send("alex@example.test","test-token","RESET"))
            .isInstanceOf(ResponseStatusException.class).hasMessageNotContaining("sensitive-provider-detail");
    }
}
