package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.AppUser;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.enums.Broker;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.service.CurrentUserService;
import org.com.sharekhan.service.UserConfigService;
import org.com.sharekhan.util.CryptoService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final CurrentUserService currentUserService;
    private final BrokerCredentialsRepository brokerCredentialsRepository;
    private final CryptoService cryptoService;
    private final PasswordEncoder passwordEncoder;
    private final UserConfigService userConfigService;
    private final org.com.sharekhan.onboarding.UserTradingAccess tradingAccess;
    private final org.com.sharekhan.onboarding.BrokerConnectionRepository connections;
    private final org.com.sharekhan.onboarding.BrokerConnectionService connectionService;
    private final org.com.sharekhan.onboarding.AccountProfileRepository profiles;
    private final org.com.sharekhan.onboarding.AccountService accounts;

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = currentUserService.isAdmin();
        Map<String, Object> body = new HashMap<>();
        body.put("username", auth != null ? auth.getName() : null);
        body.put("admin", admin);
        body.put("roles", auth == null ? java.util.List.of() : auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .collect(Collectors.toList()));
        currentUserService.currentAppUser().ifPresent(u -> {
            body.put("id", u.getId());
            body.put("customerId", u.getCustomerId());
            profiles.findById(u.getId()).ifPresent(p -> {
                body.put("displayName", p.getDisplayName()); body.put("email", p.getEmail());
                body.put("emailVerified", p.getVerifiedAt() != null);
            });
        });
        return ResponseEntity.ok(body);
    }

    @GetMapping("/brokers")
    public ResponseEntity<?> listMyBrokers() {
        Long userId = requireUserId();
        var list = brokerCredentialsRepository.findByAppUserId(userId).stream()
                .map(b -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("id", b.getId());
                    row.put("brokerName", value(b.getBrokerName()));
                    row.put("customerId", b.getCustomerId());
                    row.put("appUserId", b.getAppUserId());
                    row.put("clientCode", safeDecrypt(b.getClientCode()));
                    row.put("hasApiKey", b.getApiKey() != null && !b.getApiKey().isBlank());
                    row.put("active", b.getActive() != null ? b.getActive() : Boolean.FALSE);
                    row.put("tradingEnabled", effectiveTradingEnabled(b));
                    row.put("defaultForOrders", Boolean.TRUE.equals(b.getDefaultForOrders()));
                    row.put("tradingSupported", org.com.sharekhan.onboarding.BrokerCatalog.tradingSupported(b.getBrokerName()));
                    row.put("hasTotpSecret", b.getTotpSecret() != null && !b.getTotpSecret().isBlank());
                    row.put("hasApiSecret", b.getSecretKey() != null && !b.getSecretKey().isBlank());
                    row.put("connected", tradingAccess.connected(b.getId()));
                    row.put("verifiedUntil", connections.findById(b.getId()).map(c -> c.getVerifiedUntil()).orElse(null));
                    return row;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }

    @PostMapping("/brokers")
    @Transactional
    public ResponseEntity<?> createMyBroker(@RequestBody org.com.sharekhan.onboarding.BrokerCredentialRequest input) {
        Map<String, Object> body = input.fields(true);
        Long userId = requireUserId();
        String brokerName = text(body.get("brokerName"));
        brokerName = org.com.sharekhan.onboarding.BrokerCatalog.requireSupported(brokerName);
        if (!cryptoService.hasPersistentKey()) return ResponseEntity.status(503).body("Broker encryption is not configured");
        for (String field : org.com.sharekhan.onboarding.BrokerCatalog.requiredFields(brokerName)) {
            if (text(body.get(field)) == null || text(body.get(field)).isBlank()) return ResponseEntity.badRequest().body("Complete all required credentials");
        }
        BrokerCredentialsEntity b = new BrokerCredentialsEntity();
        b.setAppUserId(userId);
        b.setBrokerName(brokerName);
        b.setCustomerId(longValue(body.get("customerId")));
        updateSensitiveFields(b, body);
        b.setActive(false);
        b.setTradingEnabled(false);
        b.setDefaultForOrders(false);
        deactivateOtherActiveBrokers(userId, b.getBrokerName(), null, b.getActive());
        brokerCredentialsRepository.save(b);
        return ResponseEntity.ok(Map.of("id", b.getId(), "brokerName", b.getBrokerName(), "appUserId", b.getAppUserId()));
    }

    @GetMapping("/brokers/{id}")
    public ResponseEntity<?> getMyBroker(@PathVariable Long id) {
        Long userId = requireUserId();
        BrokerCredentialsEntity b = brokerCredentialsRepository.findById(id).orElse(null);
        if (b == null || !userId.equals(b.getAppUserId())) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> resp = new HashMap<>();
        resp.put("id", b.getId());
        resp.put("brokerName", b.getBrokerName());
        resp.put("customerId", b.getCustomerId());
        resp.put("appUserId", b.getAppUserId());
        resp.put("active", b.getActive() != null ? b.getActive() : Boolean.FALSE);
        resp.put("hasApiKey", b.getApiKey() != null);
        resp.put("brokerUsername", safeDecrypt(b.getBrokerUsername()));
        resp.put("hasBrokerPassword", b.getBrokerPassword() != null);
        resp.put("clientCode", safeDecrypt(b.getClientCode()));
        resp.put("hasTotpSecret", b.getTotpSecret() != null);
        resp.put("hasSecretKey", b.getSecretKey() != null);
        resp.put("tradingEnabled", effectiveTradingEnabled(b));
        resp.put("defaultForOrders", Boolean.TRUE.equals(b.getDefaultForOrders()));
        return ResponseEntity.ok(resp);
    }

    @PutMapping("/brokers/{id}")
    @Transactional
    public ResponseEntity<?> updateMyBroker(@PathVariable Long id, @RequestBody org.com.sharekhan.onboarding.BrokerCredentialRequest input) {
        Long userId = requireUserId();
        BrokerCredentialsEntity b = brokerCredentialsRepository.findById(id).orElse(null);
        if (b == null || !userId.equals(b.getAppUserId())) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> body = new org.com.sharekhan.onboarding.BrokerCredentialRequest(
            input.brokerName() == null ? b.getBrokerName() : input.brokerName(), input.customerId(), input.apiKey(),
            input.brokerUsername(), input.brokerPassword(), input.clientCode(), input.totpSecret(), input.secretKey(), input.defaultForOrders()).fields(false);
        if (body.containsKey("brokerName") && !b.getBrokerName().equalsIgnoreCase(text(body.get("brokerName"))))
            return ResponseEntity.badRequest().body("Broker type cannot be changed");
        if (!cryptoService.hasPersistentKey()) return ResponseEntity.status(503).body("Broker encryption is not configured");
        // Only supplied, nonempty secrets replace stored values. Saving invalidates verification.
        boolean changing = java.util.List.of("apiKey", "brokerUsername", "brokerPassword", "clientCode", "totpSecret", "secretKey", "customerId")
            .stream().anyMatch(key -> body.get(key) != null && !text(body.get(key)).isBlank());
        if (changing) {
            connectionService.disconnect(id);
            if (body.containsKey("customerId")) b.setCustomerId(longValue(body.get("customerId")));
            updateSensitiveFields(b, body);
        }
        // Trading activation only happens through the explicit consent endpoint.
        if (Boolean.TRUE.equals(body.get("defaultForOrders"))) {
            tradingAccess.requireReady(userId, id);
            clearDefaultOrderBrokerForUser(userId, id); b.setDefaultForOrders(true);
        }
        brokerCredentialsRepository.save(b);
        return ResponseEntity.ok("updated");
    }

    @DeleteMapping("/brokers/{id}")
    @Transactional
    public ResponseEntity<?> deleteMyBroker(@PathVariable Long id) {
        Long userId = requireUserId();
        BrokerCredentialsEntity b = brokerCredentialsRepository.findById(id).orElse(null);
        if (b == null || !userId.equals(b.getAppUserId())) {
            return ResponseEntity.notFound().build();
        }
        connectionService.disconnect(id);
        return ResponseEntity.ok("disconnected");
    }

    @PostMapping("/password")
    @Transactional
    public ResponseEntity<?> changeMyPassword(@RequestBody Map<String, String> body) {
        String newPassword = body.get("password");
        if (newPassword == null || newPassword.isBlank()) {
            return ResponseEntity.badRequest().body("password required");
        }
        org.com.sharekhan.onboarding.AccountService.validatePassword(newPassword);
        AppUser user = currentUserService.currentAppUser()
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("App user login required"));
        if (!passwordEncoder.matches(body.getOrDefault("currentPassword", ""), user.getPassword()))
            return ResponseEntity.badRequest().body("Current password is incorrect");
        user.setPassword(passwordEncoder.encode(newPassword));
        accounts.expireSessions(user.getUsername());
        return ResponseEntity.ok("updated");
    }

    @GetMapping("/configs")
    public ResponseEntity<?> listMyConfigs() {
        return ResponseEntity.ok(userConfigService.getConfigs(requireUserId()));
    }

    @PostMapping("/configs")
    public ResponseEntity<?> createMyConfig(@RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(userConfigService.createConfig(
                    requireUserId(),
                    text(body.get("keyName")),
                    text(body.get("value")),
                    !body.containsKey("enabled") || Boolean.parseBoolean(String.valueOf(body.get("enabled")))
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/configs/{configId}")
    public ResponseEntity<?> updateMyConfig(@PathVariable Long configId, @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(userConfigService.updateConfig(
                    requireUserId(),
                    configId,
                    text(body.get("keyName")),
                    text(body.get("value")),
                    !body.containsKey("enabled") || Boolean.parseBoolean(String.valueOf(body.get("enabled")))
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Long requireUserId() {
        AppUser user = currentUserService.currentAppUser()
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("App user login required"));
        return user.getId();
    }

    private void updateSensitiveFields(BrokerCredentialsEntity b, Map<String, Object> body) {
        if (body.get("apiKey") != null && !text(body.get("apiKey")).isBlank()) b.setApiKey(encryptNullable(text(body.get("apiKey"))));
        if (body.get("brokerUsername") != null && !text(body.get("brokerUsername")).isBlank()) b.setBrokerUsername(encryptNullable(text(body.get("brokerUsername"))));
        if (body.get("brokerPassword") != null && !text(body.get("brokerPassword")).isBlank()) b.setBrokerPassword(encryptNullable(text(body.get("brokerPassword"))));
        if (body.get("clientCode") != null && !text(body.get("clientCode")).isBlank()) b.setClientCode(encryptNullable(text(body.get("clientCode"))));
        if (body.get("totpSecret") != null && !text(body.get("totpSecret")).isBlank()) b.setTotpSecret(encryptNullable(text(body.get("totpSecret"))));
        if (body.get("secretKey") != null && !text(body.get("secretKey")).isBlank()) b.setSecretKey(encryptNullable(text(body.get("secretKey"))));
    }

    private void deactivateOtherActiveBrokers(Long userId, String brokerName, Long currentBrokerId, Boolean newActive) {
        if (!Boolean.TRUE.equals(newActive) || brokerName == null) {
            return;
        }
        for (BrokerCredentialsEntity other : brokerCredentialsRepository.findByAppUserId(userId)) {
            if (other.getId() != null && other.getId().equals(currentBrokerId)) {
                continue;
            }
            if (brokerName.equalsIgnoreCase(other.getBrokerName()) && Boolean.TRUE.equals(other.getActive())) {
                other.setActive(false);
                brokerCredentialsRepository.save(other);
            }
        }
    }

    private void clearDefaultOrderBrokerForUser(Long userId, Long exceptBrokerCredentialsId) {
        if (userId == null) {
            return;
        }
        for (BrokerCredentialsEntity other : brokerCredentialsRepository.findByAppUserId(userId)) {
            if (other == null || !Boolean.TRUE.equals(other.getDefaultForOrders())) {
                continue;
            }
            if (exceptBrokerCredentialsId != null && exceptBrokerCredentialsId.equals(other.getId())) {
                continue;
            }
            other.setDefaultForOrders(false);
            brokerCredentialsRepository.save(other);
        }
    }

    private boolean effectiveTradingEnabled(BrokerCredentialsEntity brokerCredentials) {
        if (brokerCredentials == null || Boolean.FALSE.equals(brokerCredentials.getActive())) {
            return false;
        }
        if (brokerCredentials.getTradingEnabled() != null) {
            return brokerCredentials.getTradingEnabled();
        }
        try {
            Broker broker = Broker.fromDisplayName(brokerCredentials.getBrokerName());
            return broker == Broker.SHAREKHAN || broker == Broker.SIMULATOR;
        } catch (Exception e) {
            return false;
        }
    }

    private String encryptNullable(String value) {
        return value == null || value.isBlank() ? null : cryptoService.encrypt(value);
    }

    private String safeDecrypt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return cryptoService.decrypt(value);
        } catch (Exception e) {
            return value;
        }
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private Long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return Long.valueOf(String.valueOf(value));
    }
}
