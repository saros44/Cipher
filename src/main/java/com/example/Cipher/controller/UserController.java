package com.example.Cipher.controller;

import com.example.Cipher.model.User;
import com.example.Cipher.service.UserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    // Display the signup form
    @GetMapping("/signup")
    public String showSignupForm(Model model) {
        model.addAttribute("user", new User());
        return "signup";
    }

    // Handle the signup form submission
    @PostMapping("/signup")
    public String signup(@ModelAttribute User user, Model model) {
        userService.saveUser(user); // Save the new user
        model.addAttribute("message", "Signup successful! You can now log in.");
        return "login"; // Show login page with a success message
    }

    // Display the login form
    @GetMapping("/login")
    public String showLoginForm() {
        return "login";
    }
}

