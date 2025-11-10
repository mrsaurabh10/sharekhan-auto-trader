package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.com.sharekhan.repository.TriggerTradeRequestRepository;
import org.com.sharekhan.repository.TriggeredTradeSetupRepository;
import org.com.sharekhan.service.TradeExecutionService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import org.springframework.web.bind.annotation.ResponseBody;
import org.com.sharekhan.entity.AdminUser;
import org.com.sharekhan.repository.AdminUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;

import java.util.stream.Collectors;
import org.com.sharekhan.entity.AppUser;
import org.com.sharekhan.util.CryptoService;
import org.com.sharekhan.entity.BrokerCredentialsEntity;
import org.com.sharekhan.repository.BrokerCredentialsRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.web.csrf.CsrfToken;
import java.util.Map;

@Controller
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminController {

    private final TriggerTradeRequestRepository requestRepository;
    private final TriggeredTradeSetupRepository setupRepository;
    private final TradeExecutionService tradeExecutionService;
    private final TriggerTradeRequestRepository triggerTradeRequestRepository;
    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final CryptoService cryptoService;
    private final BrokerCredentialsRepository brokerCredentialsRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @GetMapping("/login")
    public String loginPage(Model model, HttpServletRequest request) {
        // expose CSRF token for the login form
        Object csrf = request.getAttribute("_csrf");
        if (csrf != null) model.addAttribute("_csrf", csrf);
        return "admin-login";
    }

    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(name = "userId", required = false) Long userId, Model model, HttpServletRequest request) {
        List<TriggerTradeRequestEntity> requests = userId == null ? requestRepository.findTop10ByOrderByIdDesc() : requestRepository.findTop10ByCustomerIdOrderByIdDesc(userId);
        List<TriggeredTradeSetupEntity> setups = userId == null ? setupRepository.findTop10ByOrderByIdDesc() : setupRepository.findTop10ByCustomerIdOrderByIdDesc(userId);
        model.addAttribute("requests", requests);
        model.addAttribute("setups", setups);
        model.addAttribute("userId", userId);
        model.addAttribute("adminLogoutUrl", "/admin/logout");
        // expose CSRF token
        Object csrf = request.getAttribute("_csrf");
        if (csrf != null) model.addAttribute("_csrf", csrf);
        return "admin-dashboard";
    }

    @PostMapping("/trigger/{id}")
    public ResponseEntity<?> triggerForUser(@PathVariable Long id) {
        var opt = requestRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        TriggerTradeRequestEntity r = opt.get();
        tradeExecutionService.executeTradeFromEntity(r);
        return ResponseEntity.ok("triggered");
    }

    @PostMapping("/update-request/{id}")
    public ResponseEntity<?> adminUpdateRequest(@PathVariable Long id, @RequestBody org.com.sharekhan.dto.UpdateTargetsRequest update) {
        var opt = requestRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        TriggerTradeRequestEntity r = opt.get();
        boolean changed = false;
        if (update.getStopLoss() != null) { r.setStopLoss(update.getStopLoss()); changed = true; }
        if (update.getTarget1() != null) { r.setTarget1(update.getTarget1()); changed = true; }
        if (update.getTarget2() != null) { r.setTarget2(update.getTarget2()); changed = true; }
        if (update.getTarget3() != null) { r.setTarget3(update.getTarget3()); changed = true; }
        if (update.getQuantity() != null) { r.setQuantity(update.getQuantity()); changed = true; }
        if (update.getIntraday() != null) { r.setIntraday(update.getIntraday()); changed = true; }
        if (changed) {
            requestRepository.save(r);
            return ResponseEntity.ok(r);
        }
        return ResponseEntity.badRequest().body("no changes");
    }

    @PostMapping("/update-execution/{id}")
    public ResponseEntity<?> adminUpdateExecution(@PathVariable Long id, @RequestBody org.com.sharekhan.dto.UpdateTargetsRequest update) {
        var opt = setupRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        TriggeredTradeSetupEntity t = opt.get();
        boolean changed = false;
        if (update.getStopLoss() != null) { t.setStopLoss(update.getStopLoss()); changed = true; }
        if (update.getTarget1() != null) { t.setTarget1(update.getTarget1()); changed = true; }
        if (update.getTarget2() != null) { t.setTarget2(update.getTarget2()); changed = true; }
        if (update.getTarget3() != null) { t.setTarget3(update.getTarget3()); changed = true; }
        if (update.getIntraday() != null) { t.setIntraday(update.getIntraday()); changed = true; }
        if (changed) {
            setupRepository.save(t);
            return ResponseEntity.ok(t);
        }
        return ResponseEntity.badRequest().body("no changes");
    }

    @GetMapping("/users")
    @ResponseBody
    public Object getConfiguredUsers() {
        @SuppressWarnings("unchecked")
        var rows = entityManager.createQuery("SELECT a.id, a.username, a.customerId FROM AppUser a").getResultList();
        var list = rows.stream().map(r -> {
            Object[] cols = (Object[]) r;
            Long id = cols[0] == null ? null : ((Number) cols[0]).longValue();
            String username = cols[1] == null ? null : cols[1].toString();
            Long customerId = cols[2] == null ? null : ((Number) cols[2]).longValue();
            return java.util.Map.of("id", id, "username", username, "customerId", customerId);
        }).collect(Collectors.toList());
        return list;
    }

    // Admin user management endpoints
    @GetMapping("/admin-users")
    @ResponseBody
    public Object listAdminUsers() {
        return adminUserRepository.findAll().stream()
                .map(u -> java.util.Map.of("id", u.getId(), "username", u.getUsername(), "roles", u.getRoles()))
                .collect(Collectors.toList());
    }

    @PostMapping("/admin-users")
    @ResponseBody
    public Object createAdminUser(@RequestBody java.util.Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String roles = body.getOrDefault("roles", "ROLE_ADMIN");
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body("username and password required");
        }
        if (adminUserRepository.findByUsername(username).isPresent()) {
            return ResponseEntity.status(409).body("user exists");
        }
        AdminUser u = new AdminUser();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode(password));
        u.setRoles(roles);
        adminUserRepository.save(u);
        return ResponseEntity.ok(java.util.Map.of("id", u.getId(), "username", u.getUsername()));
    }

    @DeleteMapping("/admin-users/{id}")
    @ResponseBody
    public ResponseEntity<?> deleteAdminUser(@PathVariable Long id) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String current = auth != null ? auth.getName() : null;
        AdminUser u = adminUserRepository.findById(id).orElse(null);
        if (u == null) return ResponseEntity.notFound().build();
        if (u.getUsername().equals(current)) {
            return ResponseEntity.status(403).body("Cannot delete currently logged-in admin");
        }
        adminUserRepository.deleteById(id);
        return ResponseEntity.ok("deleted");
    }

    @PostMapping("/admin-users/{id}/password")
    @ResponseBody
    public ResponseEntity<?> changeAdminPassword(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        String newPw = body.get("password");
        if (newPw == null || newPw.isBlank()) return ResponseEntity.badRequest().body("password required");
        AdminUser u = adminUserRepository.findById(id).orElse(null);
        if (u == null) return ResponseEntity.notFound().build();
        u.setPassword(passwordEncoder.encode(newPw));
        adminUserRepository.save(u);
        return ResponseEntity.ok("updated");
    }

    // Regular app user management using EntityManager to avoid repository symbol issues
    @GetMapping("/app-users")
    @ResponseBody
    public Object listAppUsers() {
        // select scalar fields to avoid needing Lombok-generated getters in static analysis
        @SuppressWarnings("unchecked")
        var rows = entityManager.createQuery("SELECT a.id, a.username, a.customerId, a.notes FROM AppUser a").getResultList();
        return rows.stream().map(r -> {
            Object[] cols = (Object[]) r;
            Long id = cols[0] == null ? null : ((Number) cols[0]).longValue();
            String username = cols[1] == null ? null : cols[1].toString();
            Long customerId = cols[2] == null ? null : ((Number) cols[2]).longValue();
            String notes = cols[3] == null ? null : cols[3].toString();
            return java.util.Map.of("id", id, "username", username, "customerId", customerId, "notes", notes);
        }).collect(Collectors.toList());
    }

    @PostMapping("/app-users")
    @ResponseBody
    @Transactional
    public ResponseEntity<?> createAppUser(@RequestBody java.util.Map<String,Object> body) {
        String username = (String) body.get("username");
        Long customerId = body.get("customerId") == null ? null : Long.valueOf(body.get("customerId").toString());
        String apiKey = (String) body.get("brokerApiKey");
        String brokerUser = (String) body.get("brokerUsername");
        String brokerPw = (String) body.get("brokerPassword");
        String notes = (String) body.getOrDefault("notes","");
        if (username==null || username.isBlank()) return ResponseEntity.badRequest().body("username required");
        var exists = entityManager.createQuery("SELECT count(a) FROM AppUser a WHERE a.username = :u", Long.class)
                .setParameter("u", username).getSingleResult();
        if (exists != null && exists > 0) return ResponseEntity.status(409).body("user exists");
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setCustomerId(customerId);
        u.setBrokerApiKey(apiKey != null ? cryptoService.encrypt(apiKey) : null);
        u.setBrokerUsername(brokerUser != null ? cryptoService.encrypt(brokerUser) : null);
        u.setBrokerPassword(brokerPw != null ? cryptoService.encrypt(brokerPw) : null);
        u.setNotes(notes);
        entityManager.persist(u);
        return ResponseEntity.ok(java.util.Map.of("id", u.getId(), "username", u.getUsername(), "customerId", u.getCustomerId()));
    }

    @PutMapping("/app-users/{id}")
    @ResponseBody
    @Transactional
    public ResponseEntity<?> updateAppUser(@PathVariable Long id, @RequestBody java.util.Map<String,Object> body) {
        AppUser u = entityManager.find(AppUser.class, id);
        if (u == null) return ResponseEntity.notFound().build();
        if (body.containsKey("customerId")) u.setCustomerId(body.get("customerId") == null ? null : Long.valueOf(body.get("customerId").toString()));
        if (body.containsKey("brokerApiKey")) u.setBrokerApiKey(body.get("brokerApiKey") == null ? null : cryptoService.encrypt((String)body.get("brokerApiKey")));
        if (body.containsKey("brokerUsername")) u.setBrokerUsername(body.get("brokerUsername") == null ? null : cryptoService.encrypt((String)body.get("brokerUsername")));
        if (body.containsKey("brokerPassword")) u.setBrokerPassword(body.get("brokerPassword") == null ? null : cryptoService.encrypt((String)body.get("brokerPassword")));
        if (body.containsKey("notes")) u.setNotes((String)body.get("notes"));
        entityManager.merge(u);
        return ResponseEntity.ok("updated");
    }

    @DeleteMapping("/app-users/{id}")
    @ResponseBody
    @Transactional
    public ResponseEntity<?> deleteAppUser(@PathVariable Long id) {
        AppUser u = entityManager.find(AppUser.class, id);
        if (u == null) return ResponseEntity.notFound().build();
        entityManager.remove(u);
        return ResponseEntity.ok("deleted");
    }

    // Broker credentials management for app users
    @GetMapping("/app-users/{userId}/brokers")
    @ResponseBody
    public ResponseEntity<?> listBrokers(@PathVariable Long userId) {
        // resolve AppUser -> customerId (UI passes AppUser.id)
        AppUser app = entityManager.find(AppUser.class, userId);
        Long custId = app != null ? app.getCustomerId() : userId;
        var list = brokerCredentialsRepository.findByCustomerId(custId).stream()
                .map(b -> java.util.Map.of(
                        "id", b.getId(),
                        "brokerName", b.getBrokerName(),
                        "customerId", b.getCustomerId(),
                        "appUserId", b.getAppUserId(),
                        "clientCode", b.getClientCode(),
                        "hasApiKey", b.getApiKey() != null && !b.getApiKey().isBlank(),
                        "active", b.getActive() != null ? b.getActive() : Boolean.FALSE
                )).collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }

    @PostMapping("/app-users/{userId}/brokers")
    @ResponseBody
    @Transactional
    public ResponseEntity<?> createBrokerForUser(@PathVariable Long userId, @RequestBody java.util.Map<String,Object> body) {
        String brokerName = (String) body.get("brokerName");
        String apiKey = (String) body.get("apiKey");
        String brokerUser = (String) body.get("brokerUsername");
        String brokerPw = (String) body.get("brokerPassword");
        String clientCode = (String) body.get("clientCode");
        String totp = (String) body.get("totpSecret");
        String secret = (String) body.get("secretKey");
        Boolean active = body.containsKey("active") ? Boolean.valueOf(String.valueOf(body.get("active"))) : Boolean.TRUE;
        if (brokerName == null || brokerName.isBlank()) return ResponseEntity.badRequest().body("brokerName required");
        // resolve AppUser id to customerId
        AppUser app = entityManager.find(AppUser.class, userId);
        Long custId = app != null ? app.getCustomerId() : userId;
        BrokerCredentialsEntity b = new BrokerCredentialsEntity();
        b.setBrokerName(brokerName);
        b.setCustomerId(custId);
        b.setApiKey(apiKey != null ? cryptoService.encrypt(apiKey) : null);
        b.setBrokerUsername(brokerUser != null ? cryptoService.encrypt(brokerUser) : null);
        b.setBrokerPassword(brokerPw != null ? cryptoService.encrypt(brokerPw) : null);
        b.setClientCode(clientCode != null ? cryptoService.encrypt(clientCode) : null);
        b.setTotpSecret(totp != null ? cryptoService.encrypt(totp) : null);
        b.setSecretKey(secret != null ? cryptoService.encrypt(secret) : null);
        b.setActive(active != null ? active : Boolean.TRUE);
        if (app != null) b.setAppUserId(app.getId());
        // if this new broker is marked active, deactivate other brokers for same user+brokerName
        if (b.getActive()) {
            var others = brokerCredentialsRepository.findByCustomerId(custId);
            for (var o : others) {
                if (o.getBrokerName() != null && o.getBrokerName().equalsIgnoreCase(brokerName) && !o.getId().equals(b.getId())) {
                    o.setActive(false);
                    brokerCredentialsRepository.save(o);
                }
            }
        }
        brokerCredentialsRepository.save(b);
        return ResponseEntity.ok(java.util.Map.of("id", b.getId(), "brokerName", b.getBrokerName(), "appUserId", b.getAppUserId()));
    }

    @PutMapping("/admin/brokers/{id}")
    @ResponseBody
    @Transactional
    public ResponseEntity<?> updateBroker(@PathVariable Long id, @RequestBody java.util.Map<String,Object> body) {
        var opt = brokerCredentialsRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        BrokerCredentialsEntity b = opt.get();
        if (body.containsKey("brokerName")) b.setBrokerName((String)body.get("brokerName"));
        if (body.containsKey("apiKey")) b.setApiKey(body.get("apiKey") == null ? null : cryptoService.encrypt((String)body.get("apiKey")));
        if (body.containsKey("brokerUsername")) b.setBrokerUsername(body.get("brokerUsername") == null ? null : cryptoService.encrypt((String)body.get("brokerUsername")));
        if (body.containsKey("brokerPassword")) b.setBrokerPassword(body.get("brokerPassword") == null ? null : cryptoService.encrypt((String)body.get("brokerPassword")));
        if (body.containsKey("clientCode")) b.setClientCode(body.get("clientCode") == null ? null : cryptoService.encrypt((String)body.get("clientCode")));
        if (body.containsKey("totpSecret")) b.setTotpSecret(body.get("totpSecret") == null ? null : cryptoService.encrypt((String)body.get("totpSecret")));
        if (body.containsKey("secretKey")) b.setSecretKey(body.get("secretKey") == null ? null : cryptoService.encrypt((String)body.get("secretKey")));
        if (body.containsKey("active")) {
            Boolean newActive = body.get("active") == null ? null : Boolean.valueOf(String.valueOf(body.get("active")));
            if (newActive != null && newActive) {
                // deactivate other brokers for same user and brokerName
                var others = brokerCredentialsRepository.findByCustomerId(b.getCustomerId());
                for (var o : others) {
                    if (o.getBrokerName() != null && o.getBrokerName().equalsIgnoreCase(b.getBrokerName()) && !o.getId().equals(b.getId())) {
                        if (o.getActive() != null && o.getActive()) {
                            o.setActive(false);
                            brokerCredentialsRepository.save(o);
                        }
                    }
                }
            }
            b.setActive(newActive);
        }
        if (body.containsKey("appUserId")) b.setAppUserId(body.get("appUserId") == null ? null : Long.valueOf(String.valueOf(body.get("appUserId"))));
        brokerCredentialsRepository.save(b);
        return ResponseEntity.ok("updated");
    }

    @DeleteMapping("/admin/brokers/{id}")
    @ResponseBody
    @Transactional
    public ResponseEntity<?> deleteBroker(@PathVariable Long id) {
        if (!brokerCredentialsRepository.existsById(id)) return ResponseEntity.notFound().build();
        brokerCredentialsRepository.deleteById(id);
        return ResponseEntity.ok("deleted");
    }

    @GetMapping("/logout")
    public String logoutGet(HttpServletRequest request) {
        try {
            var session = request.getSession(false);
            if (session != null) session.invalidate();
        } catch (Exception ignored) {}
        SecurityContextHolder.clearContext();
        return "redirect:/admin/login?logout";
    }

    @GetMapping("/csrf-token")
    @ResponseBody
    public Map<String, String> csrfToken(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute("_csrf");
        if (token == null) return Map.of();
        return Map.of("header", token.getHeaderName(), "parameter", token.getParameterName(), "token", token.getToken());
    }

}
