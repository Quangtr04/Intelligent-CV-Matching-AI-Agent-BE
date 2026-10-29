package com.example.intelligent_cv_matching_ai_agent.service.shared.identity;

import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.EmailRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.ForgotPasswordRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.GoogleLoginRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.LoginRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.PasswordChangeRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.RefreshTokenRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.RegistrationRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.ResetPasswordRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.VerifyEmailRequest;

import com.example.intelligent_cv_matching_ai_agent.security.shared.AuthThrottle;

import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import org.springframework.stereotype.Service;
import java.util.Map;

/** Authentication orchestration and throttling shared by the HTTP endpoints. */
@Service
public class AuthFlowService {

    private final AuthService auth;

    private final TokenService tokens;

    private final CurrentUser current;

    private final AuthThrottle throttle;

    private final GoogleLoginService google;

    public AuthFlowService(
        AuthService auth,
        TokenService tokens,
        CurrentUser current,
        AuthThrottle throttle,
        GoogleLoginService google) {
        this.auth = auth;
        this.tokens = tokens;
        this.current = current;
        this.throttle = throttle;
        this.google = google;
    }

    public Map<String, Object> register(
        RegistrationRequest body,
        String remoteAddress) {
        throttle.check("register:" + remoteAddress, 10, 3600);

        return auth.register(body.email(), body.password(), body.fullName(), body.preferredLanguage());
    }

    public Map<String, Object> verify(
        VerifyEmailRequest body,
        String remoteAddress) {
        throttle.check("verify:" + remoteAddress, 30, 600);

        return auth.verify(body.email(), body.code());
    }

    public Map<String, Object> resend(
        EmailRequest body,
        String remoteAddress) {
        throttle.check("resend:" + remoteAddress, 10, 3600);

        return auth.resend(body.email());
    }

    public Map<String, Object> login(
        LoginRequest body,
        String remoteAddress) {
        throttle.check("login-ip:" + remoteAddress, 100, 900);
        throttle.check("login-email:" + AuthService.email(body.email()), 15, 900);

        return auth.login(body.email(), body.password());
    }

    public Map<String, Object> refresh(
        RefreshTokenRequest body,
        String remoteAddress) {
        throttle.check("refresh:" + remoteAddress, 100, 900);

        return tokens.rotate(body.refreshToken());
    }

    public Map<String, Object> logout() {
        tokens.revokeAll(current.id());

        return Map.of("message", "All sessions logged out");
    }

    public Map<String, Object> google(
        GoogleLoginRequest body,
        String remoteAddress) {
        throttle.check("google:" + remoteAddress, 30, 900);

        return google.login(body.idToken());
    }

    public Map<String, Object> forgotPassword(
        ForgotPasswordRequest body,
        String remoteAddress) {
        throttle.check("forgot-password:" + remoteAddress, 10, 3600);

        return auth.forgotPassword(body.email());
    }

    public Map<String, Object> resetPassword(
        ResetPasswordRequest body,
        String remoteAddress) {
        throttle.check("reset-password:" + remoteAddress, 10, 3600);

        return auth.resetPassword(body.token(), body.newPassword(), body.newPasswordConfirmation());
    }
}
