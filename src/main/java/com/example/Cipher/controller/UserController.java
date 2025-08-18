package com.example.Cipher.controller;

import com.example.Cipher.model.User;
import com.example.Cipher.service.UserService;
import com.example.Cipher.service.EmailService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Controller
@RequestMapping("/")
public class UserController {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    public UserController(UserService userService, PasswordEncoder passwordEncoder, EmailService emailService) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
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

    @GetMapping("/logout")
    public String logoutRedirect() {
        return "redirect:/login";
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

    // Handle password reset request
    @PostMapping("/api/passwordresetrequest")
    @ResponseBody
    public Map<String, Object> passwordResetRequest(@RequestParam("email") String email) {
        Map<String, Object> response = new HashMap<>();

        User user = userService.findByEmail(email);
        if (user == null) {
            response.put("status", "error");
            response.put("message", "Email not found.");
            return response;
        }

        String token = UUID.randomUUID().toString();
        user.setResetToken(token);
        userService.saveUser(user);

        String resetLink = "http://localhost:8080/passwordresetform?token=" + token;
        try {
            emailService.sendPasswordResetEmail(user.getEmail(), resetLink);
            response.put("status", "success");
            response.put("message", "Password reset link sent to your email.");
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Failed to send email. Please try again later.");
        }
        return response;
    }

    // Handle password reset form submission
    @PostMapping("/api/resetpassword")
    @ResponseBody
    public Map<String, Object> resetPassword(@RequestParam("token") String token,
                                             @RequestParam("password") String newPassword) {
        Map<String, Object> response = new HashMap<>();

        User user = userService.findByResetToken(token);
        if (user == null) {
            response.put("status", "error");
            response.put("message", "Invalid or expired reset token.");
            return response;
        }

        user.setPassword(newPassword); // Will be hashed in saveUser
        user.setResetToken(null);
        userService.saveUser(user);

        response.put("status", "success");
        response.put("message", "Your password has been reset.");
        return response;
    }

}
