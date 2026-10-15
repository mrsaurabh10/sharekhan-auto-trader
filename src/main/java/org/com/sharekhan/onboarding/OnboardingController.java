package org.com.sharekhan.onboarding;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OnboardingController {
    private final OnboardingStatusService onboarding;

    @GetMapping("/api/user/onboarding")
    public OnboardingStatusService.Status status() {
        return onboarding.status();
    }
}
