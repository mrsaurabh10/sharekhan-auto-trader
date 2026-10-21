package org.com.sharekhan.onboarding;
import org.com.sharekhan.controller.UserController;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
@RestControllerAdvice(assignableTypes = {org.com.sharekhan.telegram.TelegramSettingsController.class, AccountController.class, BrokerCatalogController.class, OnboardingController.class, UserController.class,
        org.com.sharekhan.controller.TradeTriggerController.class, org.com.sharekhan.controller.StrategyController.class,
        org.com.sharekhan.controller.OrderViewController.class, org.com.sharekhan.controller.TradeAnalyticsController.class})
public class PortalExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> status(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", ex.getReason() == null ? "Request could not be completed" : ex.getReason()));
    }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<?> denied() { return ResponseEntity.status(403).body(Map.of("message", "You do not have access to this resource")); }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalidInput() { return ResponseEntity.badRequest().body(Map.of("message", "Check the submitted values and try again")); }
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<?> conflict() {
        return ResponseEntity.status(409).body(Map.of("message", "This request conflicts with an existing record. Refresh and try again."));
    }
}
