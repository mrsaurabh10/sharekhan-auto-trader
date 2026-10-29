package org.com.sharekhan.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.com.sharekhan.service.AdminUserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
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
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers("/portal", "/portal/", "/stock-analysis", "/api/stock-analysis/**").hasAnyRole("ADMIN", "USER")
                        .requestMatchers("/", "/login", "/account", "/portal/**", "/error", "/api/account/**", "/api/auth/csrf-token").permitAll()
                        .requestMatchers("/admin/login", "/css/**", "/js/**", "/images/**", "/strategy-runs.js", "/strategy-runs.css").permitAll()
                        .requestMatchers("/h2-console/**").denyAll()
                        .requestMatchers("/api/trades/trigger-all", "/api/trades/close-all", "/api/trades/close-all/**",
                            "/api/backtests/**", "/api/sharekhan/historical/**").permitAll()
                        .requestMatchers("/api/auth/**").hasRole("ADMIN")
                        .requestMatchers("/admin/**", "/api/trades/force-close/**", "/api/trades/trigger-stock-atr-all", "/api/trades/manual-execute").hasRole("ADMIN")
                        .requestMatchers("/dashboard", "/api/user/**", "/api/brokers/**", "/api/strategies/**",
                            "/api/orders/**", "/api/analytics/**", "/api/trades/**", "/api/scripts/**",
                            "/api/market-news/**").hasAnyRole("ADMIN", "USER")
                        .requestMatchers("/internal/monitoring/**", "/telegram/webhook").permitAll()
                        .anyRequest().hasRole("ADMIN")
                )
                .userDetailsService(adminUserDetailsService)
                .httpBasic(org.springframework.security.config.Customizer.withDefaults())
                // Browser sessions keep unverified accounts out of authenticated APIs.
                .addFilterBefore(new org.com.sharekhan.onboarding.AccountRateLimitFilter(),
                    org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(errors -> errors.defaultAuthenticationEntryPointFor(
                    (req, res, ex) -> { res.setStatus(401); res.setContentType("application/json"); res.getWriter().write("{\"message\":\"Please sign in again\"}"); },
                    new AntPathRequestMatcher("/api/**")))
                .sessionManagement(session -> session.maximumSessions(-1).sessionRegistry(sessionRegistry()))
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .successHandler(roleAwareSuccessHandler())
                        .permitAll()
                )
                .logout(logout -> logout
                        // Logout uses POST with CSRF protection.
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .permitAll()
                )
                // Prevent embedding the trading workspace on another origin.
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin())
                    .referrerPolicy(ref -> ref.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                ;

        // Exclude H2 console from CSRF protection to allow console POST actions
        http.csrf(csrf -> csrf.ignoringRequestMatchers(
            new AntPathRequestMatcher("/h2-console/**"),
            new AntPathRequestMatcher("/api/trades/trigger-all"),
            new AntPathRequestMatcher("/api/trades/close-all"),
            new AntPathRequestMatcher("/api/trades/close-all/**"),
            new AntPathRequestMatcher("/api/backtests/**"),
            new AntPathRequestMatcher("/api/shoonya/oauth/exchange"),
            new AntPathRequestMatcher("/telegram/webhook") // Telegram supplies its own shared secret header
        ));
        return http.build();
    }

    @Bean
    public org.springframework.security.core.session.SessionRegistry sessionRegistry() {
        return new org.springframework.security.core.session.SessionRegistryImpl();
    }
    @Bean
    public org.springframework.security.web.session.HttpSessionEventPublisher httpSessionEventPublisher() {
        return new org.springframework.security.web.session.HttpSessionEventPublisher();
    }
    @Bean
    public AuthenticationSuccessHandler roleAwareSuccessHandler() {
        return (request, response, authentication) -> {
            boolean admin = authentication.getAuthorities().stream()
                    .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
            response.sendRedirect(admin ? "/admin/dashboard" : "/dashboard");
        };
    }
}
