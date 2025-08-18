package com.example.Cipher.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/")
public class HomeController {

    @GetMapping
    public String root() {
        return "redirect:/login";
    }

    // Remove manual session check, let Spring Security handle authentication
    @GetMapping("/home")
    public String home(Model model) {
        // Optionally, add user info from SecurityContext if needed
        return "home";
    }

    @GetMapping("/Forgotpassword")
    public String ShowForgotpassword() {
        return "Forgotpassword";
    }

    @GetMapping("/passwordresetform")
    public String Showpasswordresetform() {
        return "passwordresetform";
    }

    @GetMapping("/encode")
    public String encode() {
        return "encode";
    }

    @GetMapping("/decode")
    public String decode() {
        return "decode";
    }

    @GetMapping("/AEncode")
    public String AEncode() {
        return "AEncode";
    }

    @GetMapping("/ADecode")
    public String ADecode() {
        return "ADecode";
    }

    @GetMapping("/Vencode")
    public String Vencode() {
        return "Vencode";
    }

    @GetMapping("/Vdecode")
    public String Vdecode() {
        return "Vdecode";
    }
}
