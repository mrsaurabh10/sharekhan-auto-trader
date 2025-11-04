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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

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
    public List<Long> getConfiguredUsers() {
        return triggerTradeRequestRepository.findDistinctCustomerIds();
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
        return ResponseEntity.ok(java.util.Map.of("id", u.getId(), "username", u.getUsername()));
    }

    @PutMapping("/app-users/{id}")
    @ResponseBody
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
    public ResponseEntity<?> deleteAppUser(@PathVariable Long id) {
        AppUser u = entityManager.find(AppUser.class, id);
        if (u == null) return ResponseEntity.notFound().build();
        entityManager.remove(u);
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

}
