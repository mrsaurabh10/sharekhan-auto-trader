package org.com.sharekhan.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.com.sharekhan.service.AdminUserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${app.admin.user:admin}")
    private String adminUser;

    @Value("${app.admin.pw:admin}")
    private String adminPw;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // Use DB-backed AdminUserDetailsService (provided as a bean)
    private final AdminUserDetailsService adminUserDetailsService;

    public SecurityConfig(AdminUserDetailsService adminUserDetailsService) {
        this.adminUserDetailsService = adminUserDetailsService;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // allow H2 console and static resources publicly
                        .requestMatchers("/h2-console/**").permitAll()
                        .requestMatchers("/admin/login", "/css/**", "/js/**", "/images/**").permitAll()
                        // admin endpoints require ROLE_ADMIN
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/auth/**").permitAll() // keep token endpoints accessible via admin UI via login
                        .anyRequest().permitAll()
                )
                .userDetailsService(adminUserDetailsService)
                .formLogin(form -> form
                        .loginPage("/admin/login")
                        .defaultSuccessUrl("/admin/dashboard", true)
                        .permitAll()
                )
                .logout(logout -> logout
                        // allow logout via GET for the admin UI convenience (also works with POST)
                        .logoutRequestMatcher(new AntPathRequestMatcher("/admin/logout", "GET"))
                        .logoutSuccessUrl("/admin/login?logout")
                        .permitAll()
                )
                // disable frameOptions so H2 console can render in a frame
                .headers(headers -> headers.frameOptions(frame -> frame.disable()))
                ;

        // Exclude H2 console from CSRF protection to allow console POST actions
        http.csrf(csrf -> csrf.ignoringRequestMatchers(new AntPathRequestMatcher("/h2-console/**")));
        return http.build();
    }
}
