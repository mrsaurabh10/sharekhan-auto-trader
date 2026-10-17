package org.com.sharekhan.onboarding;

import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.com.sharekhan.auth.DhanAuthProvider;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequiredArgsConstructor
public class DhanConnectionController {
    private static final String PENDING = DhanConnectionController.class.getName() + ".pending";
    private final UserTradingAccess access;
    private final DhanAuthProvider provider;
    private final BrokerConnectionService connections;

    // Ciphertexts bind the callback to unchanged credentials; no secrets enter a URL or browser response.
    private record Pending(Long id, Long owner, String client, String key, String secret, String pin, String totp, Instant expires) {
        boolean matches(BrokerCredentialsEntity b) {
            return owner.equals(b.getAppUserId()) && Objects.equals(client, b.getClientCode())
                && Objects.equals(key, b.getApiKey()) && Objects.equals(secret, b.getSecretKey())
                && Objects.equals(pin, b.getBrokerPassword()) && Objects.equals(totp, b.getTotpSecret());
        }
        @Override public String toString() { return "PendingDhanConsent[redacted]"; }
    }
    @PostMapping("/api/user/brokers/{id}/dhan/consent")
    public Map<String, String> begin(@PathVariable Long id, HttpSession session) {
        var broker = access.owned(id);
        if (!"Dhan".equalsIgnoreCase(broker.getBrokerName())) throw new ResponseStatusException(BAD_REQUEST, "Choose a Dhan account");
        synchronized (session) {
            session.removeAttribute(PENDING);
            try {
                String url = provider.beginConsent(broker);
                session.setAttribute(PENDING, new Pending(id, broker.getAppUserId(), broker.getClientCode(),
                    broker.getApiKey(), broker.getSecretKey(), broker.getBrokerPassword(), broker.getTotpSecret(), Instant.now().plusSeconds(600)));
                return Map.of("loginUrl", url);
            } catch (Exception ignored) { throw new ResponseStatusException(BAD_REQUEST, "Could not start Dhan login. Check your API key and secret."); }
        }
    }
    @GetMapping("/api/user/brokers/dhan/callback")
    public ResponseEntity<Void> callback(@RequestParam(required=false) String tokenId, HttpSession session) {
        Pending pending;
        synchronized (session) {
            pending = (Pending) session.getAttribute(PENDING);
            session.removeAttribute(PENDING); // One attempt per login session, including failures.
        }
        boolean success = false;
        try {
            if (pending == null || pending.expires().isBefore(Instant.now())) throw new IllegalArgumentException();
            var broker = access.owned(pending.id());
            if (!pending.matches(broker)) throw new IllegalArgumentException();
            connections.completeDhanConsent(pending.id(), tokenId);
            success = true;
        } catch (Exception ignored) { /* Never render broker messages, token IDs or credentials. */ }
        return ResponseEntity.status(303).header("Location", "/portal?dhan=" + (success ? "connected" : "failed") + "#brokers")
            .header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer").build();
    }
}
