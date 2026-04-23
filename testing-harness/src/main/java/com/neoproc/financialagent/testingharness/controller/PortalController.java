package com.neoproc.financialagent.testingharness.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;

@Controller
public class PortalController {

    @GetMapping("/login")
    public String login(@RequestParam(value = "error", required = false) String error, Model model) {
        if (error != null) {
            model.addAttribute("errorMessage", "Invalid credentials");
        }
        return "login";
    }

    @GetMapping("/report")
    public String report(@AuthenticationPrincipal UserDetails user, Model model) {
        model.addAttribute("username", user.getUsername());
        model.addAttribute("today", LocalDate.now());
        return "report";
    }
}
