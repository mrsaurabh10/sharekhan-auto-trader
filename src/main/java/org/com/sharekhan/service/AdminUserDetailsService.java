package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.AdminUser;
import org.com.sharekhan.entity.AppUser;
import org.com.sharekhan.repository.AdminUserRepository;
import org.com.sharekhan.repository.AppUserRepository;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminUserDetailsService implements UserDetailsService {

    private final AdminUserRepository adminUserRepository;
    private final AppUserRepository appUserRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return adminUserRepository.findByUsername(username)
                .map(this::adminDetails)
                .or(() -> appUserRepository.findByUsername(username)
                        .filter(u -> u.getPassword() != null && !u.getPassword().isBlank())
                        .map(this::appUserDetails))
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    private UserDetails adminDetails(AdminUser u) {
        Collection<GrantedAuthority> authorities = Arrays.stream(u.getRoles().split(","))
                .map(String::trim)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());

        return org.springframework.security.core.userdetails.User.withUsername(u.getUsername())
                .password(u.getPassword())
                .authorities(authorities)
                .build();
    }

    private UserDetails appUserDetails(AppUser u) {
        return org.springframework.security.core.userdetails.User.withUsername(u.getUsername())
                .password(u.getPassword())
                .authorities("ROLE_USER")
                .build();
    }
}
