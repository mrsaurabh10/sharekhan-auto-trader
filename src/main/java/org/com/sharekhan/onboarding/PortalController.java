package org.com.sharekhan.onboarding;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
@Controller
public class PortalController {
    @GetMapping("/") public String root(org.springframework.security.core.Authentication authentication) {
        boolean admin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return admin ? "redirect:/admin/dashboard" : "redirect:/dashboard";
    }
    @GetMapping("/account") public String account() { return "account"; }
}
