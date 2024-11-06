package com.example.Cipher.controller;

import com.example.Cipher.model.User;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/")
public class HomeController {

    // Example of a secured page
    @GetMapping("/home")
    public String home(HttpSession session, Model model) {
        // Check if the user is logged in
        if (session.getAttribute("loggedInUser") == null) {
            return "redirect:/login"; // If not logged in, redirect to login page
        }
        // Add user information to the model
        User loggedInUser = (User) session.getAttribute("loggedInUser");
        model.addAttribute("email", loggedInUser.getEmail());
        return "home"; // Render the home page
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

//public class HomeController {
//
//    // Publicly accessible home page
//    @GetMapping("/home")
//    public String home() {
//        return "home"; // This page is public
//    }
//
//    // Secured encode page
//    @GetMapping("/encode")
//    public String encode(HttpSession session) {
//        if (session.getAttribute("loggedInUser") == null) {
//            return "redirect:/login"; // Redirect to login if not logged in
//        }
//        return "encode"; // Render encode page if logged in
//    }
//
//    // Secured decode page
//    @GetMapping("/decode")
//    public String decode(HttpSession session) {
//        if (session.getAttribute("loggedInUser") == null) {
//            return "redirect:/login"; // Redirect to login if not logged in
//        }
//        return "decode"; // Render decode page if logged in
//    }
//
//    // Secured AEncode page
//    @GetMapping("/AEncode")
//    public String AEncode(HttpSession session) {
//        if (session.getAttribute("loggedInUser") == null) {
//            return "redirect:/login"; // Redirect to login if not logged in
//        }
//        return "AEncode"; // Render AEncode page if logged in
//    }
//
//    // Secured ADecode page
//    @GetMapping("/ADecode")
//    public String ADecode(HttpSession session) {
//        if (session.getAttribute("loggedInUser") == null) {
//            return "redirect:/login"; // Redirect to login if not logged in
//        }
//        return "ADecode"; // Render ADecode page if logged in
//    }
//}
