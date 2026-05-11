package org.com.sharekhan.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

    @GetMapping("/login")
    public String loginPage(Model model, HttpServletRequest request) {
        Object csrf = request.getAttribute("_csrf");
        if (csrf != null) {
            model.addAttribute("_csrf", csrf);
        }
        return "admin-login";
    }

    @GetMapping("/dashboard")
    public String userDashboard() {
        return "redirect:/admin-dashboard.html";
    }
}
