package org.com.sharekhan.service;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.entity.AppUser;
import org.com.sharekhan.repository.AppUserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final AppUserRepository appUserRepository;

    public boolean isAdmin() {
        Authentication auth = authentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    public Optional<AppUser> currentAppUser() {
        Authentication auth = authentication();
        if (auth == null || !auth.isAuthenticated() || isAdmin()) {
            return Optional.empty();
        }
        return appUserRepository.findByUsername(auth.getName());
    }

    public Long currentAppUserIdOrNull() {
        return currentAppUser().map(AppUser::getId).orElse(null);
    }

    public Long scopedUserId(Long requestedUserId) {
        if (isAdmin()) {
            return requestedUserId;
        }
        return currentAppUserIdOrNull();
    }

    private Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
