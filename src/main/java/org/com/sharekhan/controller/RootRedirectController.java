package org.com.sharekhan.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class RootRedirectController {

    @GetMapping("/admin-login")
    public String redirectAdminLogin() {
        return "redirect:/admin/login";
    }

    @GetMapping("/admin-dashboard")
    public String redirectAdminDashboard() {
        return "redirect:/admin/dashboard";
    }

    @GetMapping("/admin")
    public String redirectAdminRoot() {
        return "redirect:/admin/login";
    }
}

