package org.com.sharekhan.onboarding;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @RequestMapping("/api/account") @RequiredArgsConstructor
public class AccountController {
    private final AccountService accounts;
    @GetMapping("/availability") public Map<String, Boolean> availability() { return Map.of("enabled", accounts.available()); }
    @PostMapping("/register") public Map<String, String> register(@RequestBody AccountService.Registration input) {
        accounts.register(input); return message();
    }
    @PostMapping("/resend") public Map<String, String> resend(@RequestBody AccountService.EmailRequest input) {
        accounts.requestEmail(input.email(), false); return message();
    }
    @PostMapping("/forgot-password") public Map<String, String> forgot(@RequestBody AccountService.EmailRequest input) {
        accounts.requestEmail(input.email(), true); return message();
    }
    @PostMapping("/verify") public Map<String, String> verify(@RequestBody AccountService.TokenRequest input) {
        accounts.redeem(input, false); return Map.of("message", "Email verified. You can now sign in.");
    }
    @PostMapping("/reset-password") public Map<String, String> reset(@RequestBody AccountService.TokenRequest input) {
        accounts.redeem(input, true); return Map.of("message", "Password reset. Sign in with your new password.");
    }
    private Map<String, String> message() { return Map.of("message", "If your account is eligible, you will receive an email with the next step."); }
}
