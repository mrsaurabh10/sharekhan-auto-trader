package org.com.sharekhan.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.AdminUser;
import org.com.sharekhan.repository.AdminUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminUserBootstrap {

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.user:admin}")
    private String adminUser;

    @Value("${app.admin.pw:admin}")
    private String adminPw;

    @PostConstruct
    public void ensureAdminExists() {
        adminUserRepository.findByUsername(adminUser).orElseGet(() -> {
            AdminUser u = new AdminUser();
            u.setUsername(adminUser);
            u.setPassword(passwordEncoder.encode(adminPw));
            u.setRoles("ROLE_ADMIN");
            return adminUserRepository.save(u);
        });
    }
}

