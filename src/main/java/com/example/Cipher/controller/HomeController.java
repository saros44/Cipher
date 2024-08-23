package com.example.Cipher.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/")
public class HomeController {

    @GetMapping("/home")
    public String index() {
        return "index";
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
}
