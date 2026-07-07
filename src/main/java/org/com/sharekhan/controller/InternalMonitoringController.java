package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.dto.monitoring.MonitoringSnapshotResponse;
import org.com.sharekhan.service.MonitoringSnapshotService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
@RequestMapping("/internal/monitoring")
@RequiredArgsConstructor
public class InternalMonitoringController {

    private final MonitoringSnapshotService monitoringSnapshotService;

    @Value("${app.monitoring.api-token:}")
    private String configuredToken;

    @GetMapping("/snapshot")
    public ResponseEntity<?> snapshot(
            @RequestHeader(value = "X-Monitoring-Token", required = false) String suppliedToken) {
        if (!StringUtils.hasText(configuredToken)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "monitoring_not_configured"));
        }
        if (!tokensEqual(configuredToken, suppliedToken)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "forbidden"));
        }
        return ResponseEntity.ok(monitoringSnapshotService.snapshot());
    }

    private boolean tokensEqual(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
