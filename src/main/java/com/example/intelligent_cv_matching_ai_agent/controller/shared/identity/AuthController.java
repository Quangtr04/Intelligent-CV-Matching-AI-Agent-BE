package com.example.intelligent_cv_matching_ai_agent.controller.shared.identity;

import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.EmailRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.GoogleLoginRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.LoginRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.RefreshTokenRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.RegistrationRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.VerifyEmailRequest;

import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.AuthFlowService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthFlowService service;

    public AuthController(
        AuthFlowService service) {
        this.service = service;
    }

    @PostMapping("/register")
    public Map<String, Object> register(
        @Valid
        @RequestBody
        RegistrationRequest body,
        HttpServletRequest request) {
        return service.register(body, request.getRemoteAddr());
    }

    @PostMapping("/verify-email")
    public Map<String, Object> verify(
        @Valid
        @RequestBody
        VerifyEmailRequest body,
        HttpServletRequest request) {
        return service.verify(body, request.getRemoteAddr());
    }

    @PostMapping("/resend-code")
    public Map<String, Object> resend(
        @Valid
        @RequestBody
        EmailRequest body,
        HttpServletRequest request) {
        return service.resend(body, request.getRemoteAddr());
    }

    @PostMapping("/login")
    public Map<String, Object> login(
        @Valid
        @RequestBody
        LoginRequest body,
        HttpServletRequest request) {
        return service.login(body, request.getRemoteAddr());
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(
        @Valid
        @RequestBody
        RefreshTokenRequest body,
        HttpServletRequest request) {
        return service.refresh(body, request.getRemoteAddr());
    }

    @PostMapping("/logout")
    public Map<String, Object> logout() {
        return service.logout();
    }

    
    @PostMapping("/forgot-password")
    public Map<String, Object> forgotPassword(
        @Valid
        @RequestBody
        com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.ForgotPasswordRequest body,
        HttpServletRequest request) {
        return service.forgotPassword(body, request.getRemoteAddr());
    }

    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(
        @Valid
        @RequestBody
        com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.ResetPasswordRequest body,
        HttpServletRequest request) {
        return service.resetPassword(body, request.getRemoteAddr());
    }

    @PostMapping("/google")
    public Map<String, Object> google(
        @Valid
        @RequestBody
        GoogleLoginRequest body,
        HttpServletRequest request) {
        return service.google(body, request.getRemoteAddr());
    }
}
