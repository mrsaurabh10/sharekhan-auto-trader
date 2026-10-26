package org.com.sharekhan.controller;

import lombok.RequiredArgsConstructor;
import org.com.sharekhan.strategy.mcx.NaturalGasLiveCandles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/** Bounded public-market fields only; use the same admin guard as historical diagnostics. */
@RestController @RequiredArgsConstructor
@RequestMapping("/api/sharekhan/historical")
public class NaturalGasDataDiagnosticsController {
    private final NaturalGasLiveCandles live;
    @Value("${app.admin.token:}") private String adminToken;
    @GetMapping("/mcx-capture")
    public ResponseEntity<?> capture(@RequestHeader(value="X-Admin-Token",required=false) String token,@RequestParam(name="scripCode") Integer scripCode) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        boolean admin=auth!=null&&auth.isAuthenticated()&&auth.getAuthorities().stream().anyMatch(a->"ROLE_ADMIN".equals(a.getAuthority()));
        if(!admin && (adminToken==null||adminToken.isBlank()||!adminToken.equals(token)))return ResponseEntity.status(403).build();
        return ResponseEntity.ok(live.diagnostics(scripCode));
    }
}
