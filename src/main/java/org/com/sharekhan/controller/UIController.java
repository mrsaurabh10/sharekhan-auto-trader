package org.com.sharekhan.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class UIController {

    @GetMapping("/place-order")
    public String showOrderForm() {
        return "place-order"; // this maps to place-order.html in templates folder
    }
}