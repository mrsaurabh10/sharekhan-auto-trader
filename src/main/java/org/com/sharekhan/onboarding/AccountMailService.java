package org.com.sharekhan.onboarding;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Service @RequiredArgsConstructor
public class AccountMailService {
    private final ObjectProvider<JavaMailSender> senders;
    @Value("${app.onboarding.base-url:}") private String baseUrl;
    @Value("${app.onboarding.mail-from:}") private String from;
    public boolean configured() {
        if (senders.getIfAvailable() == null || from.isBlank() || from.indexOf('\r') >= 0 || from.indexOf('\n') >= 0) return false;
        try {
            var uri = java.net.URI.create(baseUrl);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    && (uri.getPath().isEmpty() || "/".equals(uri.getPath()));
        } catch (IllegalArgumentException ex) { return false; }
    }
    public void send(String email, String token, String purpose) {
        if (!configured()) throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Account email delivery is not configured");
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject(purpose.equals("VERIFY") ? "Verify your AutoTrader account" : "Reset your AutoTrader password");
        message.setText("Open this link to " + (purpose.equals("VERIFY") ? "verify your email" : "reset your password")
            + ":\n" + baseUrl.replaceAll("/+$", "") + "/account?mode="
            + (purpose.equals("VERIFY") ? "verify" : "reset") + "#token=" + token
            + "\nThis link expires in 30 minutes and can be used once. If you did not request it, ignore this email.");
        try { senders.getObject().send(message); }
        catch (org.springframework.mail.MailException ex) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Email delivery is unavailable. Please try again later");
        }
    }
}
