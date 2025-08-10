package com.example.Cipher.controller;

import com.example.Cipher.model.User;
import com.example.Cipher.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;

@Controller
@RequestMapping("/")
public class UserController {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserService userService, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/signup")
    public String showSignupForm(Model model) {
        model.addAttribute("user", new User());
        return "signup";
    }

    @PostMapping("/signup")
    @ResponseBody
    public Map<String, Object> signup(@RequestParam("email") String email,
            @RequestParam("password") String password,
            HttpSession session) {
        Map<String, Object> response = new HashMap<>();

        // Check if the user already exists
        User existingUser = userService.findByEmail(email);
        if (existingUser != null) {
            response.put("status", "error");
            response.put("message", "Email already in use. Please choose a different one.");
            return response;
        }

        try {
            // Create a new user and save to the database
            User newUser = new User();
            newUser.setEmail(email);
            newUser.setPassword(password);
            userService.saveUser(newUser);

            // Set the user in the session after signup (if required)
            session.setAttribute("loggedInUser", newUser);

            // Return success response
            response.put("status", "success");
            response.put("message", "Signup successful!");
            response.put("redirectUrl", "/login");
        } catch (Exception e) {
            // Handle any errors during signup
            response.put("status", "error");
            response.put("message", "Signup failed. Please try again.");
        }

        return response;
    }

    @GetMapping("/login")
    public String showLoginForm() {
        return "login";
    }

    @PostMapping("/login")
    @ResponseBody
    public Map<String, Object> login(@RequestParam("email") String email,
            @RequestParam("password") String password,
            HttpSession session) {
        Map<String, Object> response = new HashMap<>();
        User user = userService.findByEmail(email);
        if (user != null && passwordEncoder.matches(password, user.getPassword())) { // Use matches()
            session.setAttribute("loggedInUser", user); // Set user in session
            response.put("status", "success");
            response.put("message", "Login successful!");
            response.put("redirectUrl", "/home"); // Correct URL for redirection
        } else {
            response.put("status", "error");
            response.put("message", "Invalid email or password. Please try again.");
        }
        return response;
    }

    @PostMapping("/logout")
    @ResponseBody
    public Map<String, Object> logout(HttpSession session) {
        Map<String, Object> response = new HashMap<>();

        // Invalidate the session
        session.invalidate();

        // Respond with success message
        response.put("status", "success");
        response.put("message", "Logged out successfully.");

        return response;
    }

}
