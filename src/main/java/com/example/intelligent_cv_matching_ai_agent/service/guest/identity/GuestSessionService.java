package com.example.intelligent_cv_matching_ai_agent.service.guest.identity;

import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;

import com.example.intelligent_cv_matching_ai_agent.security.shared.AuthThrottle;

import org.springframework.stereotype.Service;
import com.example.intelligent_cv_matching_ai_agent.repository.guest.identity.GuestSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class GuestSessionService {

    private final GuestSessionRepository db;

    private final GuestAccess guest;

    private final AuthThrottle throttle;

    private final int tokenLimit;

    public GuestSessionService(
        GuestSessionRepository db,
        GuestAccess guest,
        AuthThrottle throttle,
        @Value("${app.guest.token-limit:10000}")
        int tokenLimit) {
        this.db = db;
        this.guest = guest;
        this.throttle = throttle;
        this.tokenLimit = tokenLimit;
    }

    public Map<String, Object> create(
        String remoteAddress) {
        throttle.check("guest:" + remoteAddress, 3, 86400);
        String raw = TokenService.random("g_");
        OffsetDateTime expires = OffsetDateTime.now().plusHours(24);

        UUID id = db.insertGuestSessions(
            Map.of(
                "session_key",
                TokenService.hash(raw),
                "token_limit",
                tokenLimit,
                "token_used",
                0,
                "created_at",
                OffsetDateTime.now(),
                "expires_at",
                expires
            )
        );

        return Map.of("id", id, "sessionKey", raw, "tokenLimit", tokenLimit, "tokenUsed", 0, "expiresAt", expires);
    }

    public java.util.List<Map<String, Object>> demos(
        String key) {
        guest.require(key);

        return db.listResumesByIsDemo();
    }

    public java.util.List<Map<String, Object>> templates(
        String key) {
        guest.require(key);

        return db.listResumeTemplatesByIsActive();
    }
}
