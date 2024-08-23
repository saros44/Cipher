package com.example.Cipher.Config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authz -> authz
                        .requestMatchers("/", "/login", "/signup").permitAll() // Allow access to login and signup pages
                        .requestMatchers("/home", "/encode", "/decode", "/aencode", "/adecode")
                        .authenticated() // Require authentication for these pages
                        .anyRequest().authenticated() // Require authentication for any other request
                )
                .formLogin(form -> form
                        .loginPage("/login") // Specify custom login page
                        .defaultSuccessUrl("/home", true) // Redirect to /home on successful login
                        .permitAll() // Allow everyone to see the login page
                )
                .csrf(csrf -> csrf.ignoringRequestMatchers("/")); // Disable CSRF protection for the auth endpoints

        return http.build(); // Build the SecurityFilterChain
    }
}
