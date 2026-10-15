package org.com.sharekhan.onboarding;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.AppUser;
import org.com.sharekhan.repository.AppUserRepository;
import org.com.sharekhan.repository.AdminUserRepository;
import org.com.sharekhan.util.CryptoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import static org.springframework.http.HttpStatus.*;

@Service @RequiredArgsConstructor
public class AccountService {
    private final AppUserRepository users;
    private final AdminUserRepository admins;
    private final AccountProfileRepository profiles;
    private final PasswordEncoder passwords;
    private final AccountMailService mail;
    private final CryptoService crypto;
    private final SessionRegistry sessions;
    @Value("${app.onboarding.enabled:false}") private boolean enabled;
    public record Registration(String displayName, String email, String password) {}
    public record EmailRequest(String email) {}
    public record TokenRequest(String token, String password) {}
    public boolean available() { return enabled && mail.configured() && crypto.hasPersistentKey(); }
    private void requireAvailable() {
        if (!available()) throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Registration and recovery are currently unavailable");
    }
    @Transactional
    public void register(Registration input) {
        requireAvailable();
        String email = normalizeEmail(input.email());
        validatePassword(input.password());
        if (input.displayName() == null || input.displayName().isBlank() || input.displayName().trim().length() > 100)
            throw new ResponseStatusException(BAD_REQUEST, "Enter your name (up to 100 characters)");
        if (users.existsByUsernameIgnoreCase(email) || admins.existsByUsernameIgnoreCase(email) || profiles.findByEmail(email).isPresent())
            return; // Same response as success; recovery/resend handles existing accounts.
        AppUser user = new AppUser();
        user.setUsername(email);
        user.setPassword(passwords.encode(input.password()));
        users.saveAndFlush(user);
        AccountProfile profile = new AccountProfile();
        profile.setUserId(user.getId()); profile.setEmail(email);
        profile.setDisplayName(input.displayName().trim()); profile.setCreatedAt(Instant.now());
        issue(profile, "VERIFY");
    }
    @Transactional
    public void requestEmail(String rawEmail, boolean reset) {
        requireAvailable();
        String email = normalizeEmail(rawEmail);
        profiles.findByEmail(email).ifPresent(profile -> {
            // A cooldown also limits repeated email to one recipient behind different peers.
            if (profile.getTokenExpiresAt() != null && profile.getTokenExpiresAt().isAfter(Instant.now().plusSeconds(1740))) return;
            if (reset && profile.getVerifiedAt() != null) issue(profile, "RESET");
            if (!reset && profile.getVerifiedAt() == null) issue(profile, "VERIFY");
        });
    }
    private void issue(AccountProfile profile, String purpose) {
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        profile.setTokenHash(hash(token)); profile.setTokenPurpose(purpose);
        profile.setTokenExpiresAt(Instant.now().plusSeconds(1800));
        profiles.saveAndFlush(profile);
        mail.send(profile.getEmail(), token, purpose);
    }
    @Transactional
    public void redeem(TokenRequest input, boolean reset) {
        if (input.token() == null || input.token().length() != 43) invalidToken();
        AccountProfile p = profiles.findByTokenHash(hash(input.token())).orElseThrow(() ->
            new ResponseStatusException(BAD_REQUEST, "This link is invalid or expired"));
        if (!(reset ? "RESET" : "VERIFY").equals(p.getTokenPurpose()) || p.getTokenExpiresAt() == null
                || !p.getTokenExpiresAt().isAfter(Instant.now())) invalidToken();
        if (reset) {
            validatePassword(input.password());
            AppUser user = users.findById(p.getUserId()).orElseThrow();
            user.setPassword(passwords.encode(input.password())); users.save(user);
            expireSessions(user.getUsername());
        } else { p.setVerifiedAt(Instant.now()); }
        p.setTokenHash(null); p.setTokenPurpose(null); p.setTokenExpiresAt(null); profiles.save(p);
    }
    public void expireSessions(String username) {
        for (Object principal : sessions.getAllPrincipals()) {
            if (principal instanceof UserDetails details && details.getUsername().equals(username))
                sessions.getAllSessions(principal, false).forEach(s -> s.expireNow());
        }
    }
    private static void invalidToken() { throw new ResponseStatusException(BAD_REQUEST, "This link is invalid or expired"); }
    public static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ResponseStatusException(BAD_REQUEST, "Use a password with at least 12 characters and at most 72 UTF-8 bytes");
    }
    static String normalizeEmail(String email) {
        if (email == null || email.length() > 254 || email.indexOf('\r') >= 0 || email.indexOf('\n') >= 0 || !email.trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            throw new ResponseStatusException(BAD_REQUEST, "Enter a valid email address");
        return email.trim().toLowerCase(Locale.ROOT);
    }
    static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
