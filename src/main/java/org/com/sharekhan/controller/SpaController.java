package org.com.sharekhan.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {

    @GetMapping({"/spa", "/spa/"})
    public String spa() {
        // Forward to static resource index.html under /spa/
        return "forward:/spa/index.html";
    }
}

