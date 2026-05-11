package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.AppUser;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import org.com.sharekhan.service.CurrentUserService;
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
            body.put("notes", u.getNotes());
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
                    return row;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }

    @PostMapping("/brokers")
    @Transactional
    public ResponseEntity<?> createMyBroker(@RequestBody Map<String, Object> body) {
        Long userId = requireUserId();
        String brokerName = text(body.get("brokerName"));
        if (brokerName == null || brokerName.isBlank()) {
            return ResponseEntity.badRequest().body("brokerName required");
        }
        BrokerCredentialsEntity b = new BrokerCredentialsEntity();
        b.setAppUserId(userId);
        b.setBrokerName(brokerName);
        b.setCustomerId(longValue(body.get("customerId")));
        updateSensitiveFields(b, body);
        b.setActive(body.containsKey("active") ? Boolean.valueOf(String.valueOf(body.get("active"))) : Boolean.TRUE);
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
        resp.put("apiKey", safeDecrypt(b.getApiKey()));
        resp.put("brokerUsername", safeDecrypt(b.getBrokerUsername()));
        resp.put("brokerPassword", safeDecrypt(b.getBrokerPassword()));
        resp.put("clientCode", safeDecrypt(b.getClientCode()));
        resp.put("totpSecret", safeDecrypt(b.getTotpSecret()));
        resp.put("secretKey", safeDecrypt(b.getSecretKey()));
        return ResponseEntity.ok(resp);
    }

    @PutMapping("/brokers/{id}")
    @Transactional
    public ResponseEntity<?> updateMyBroker(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long userId = requireUserId();
        BrokerCredentialsEntity b = brokerCredentialsRepository.findById(id).orElse(null);
        if (b == null || !userId.equals(b.getAppUserId())) {
            return ResponseEntity.notFound().build();
        }
        if (body.containsKey("brokerName")) {
            b.setBrokerName(text(body.get("brokerName")));
        }
        if (body.containsKey("customerId")) {
            b.setCustomerId(longValue(body.get("customerId")));
        }
        updateSensitiveFields(b, body);
        if (body.containsKey("active")) {
            b.setActive(body.get("active") == null ? null : Boolean.valueOf(String.valueOf(body.get("active"))));
        }
        deactivateOtherActiveBrokers(userId, b.getBrokerName(), b.getId(), b.getActive());
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
        brokerCredentialsRepository.delete(b);
        return ResponseEntity.ok("deleted");
    }

    @PostMapping("/password")
    @Transactional
    public ResponseEntity<?> changeMyPassword(@RequestBody Map<String, String> body) {
        String newPassword = body.get("password");
        if (newPassword == null || newPassword.isBlank()) {
            return ResponseEntity.badRequest().body("password required");
        }
        AppUser user = currentUserService.currentAppUser()
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("App user login required"));
        user.setPassword(passwordEncoder.encode(newPassword));
        return ResponseEntity.ok("updated");
    }

    private Long requireUserId() {
        AppUser user = currentUserService.currentAppUser()
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("App user login required"));
        return user.getId();
    }

    private void updateSensitiveFields(BrokerCredentialsEntity b, Map<String, Object> body) {
        if (body.containsKey("apiKey")) b.setApiKey(encryptNullable(text(body.get("apiKey"))));
        if (body.containsKey("brokerUsername")) b.setBrokerUsername(encryptNullable(text(body.get("brokerUsername"))));
        if (body.containsKey("brokerPassword")) b.setBrokerPassword(encryptNullable(text(body.get("brokerPassword"))));
        if (body.containsKey("clientCode")) b.setClientCode(encryptNullable(text(body.get("clientCode"))));
        if (body.containsKey("totpSecret")) b.setTotpSecret(encryptNullable(text(body.get("totpSecret"))));
        if (body.containsKey("secretKey")) b.setSecretKey(encryptNullable(text(body.get("secretKey"))));
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
