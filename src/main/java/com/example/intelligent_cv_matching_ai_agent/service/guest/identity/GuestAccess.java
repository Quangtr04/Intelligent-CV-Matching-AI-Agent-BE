package com.example.intelligent_cv_matching_ai_agent.service.guest.identity;

import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.guest.identity.GuestAccessRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class GuestAccess {

    private final GuestAccessRepository db;

    public GuestAccess(
        GuestAccessRepository db) {
        this.db = db;
    }

    public Map<String, Object> require(
        String key) {
        if (key == null || !key.startsWith("g_") || key.length() != 45)
            throw new ApiException(401, "X-Guest-Session header required");

        return db.findGuestSessionsBySessionKey(TokenService.hash(key))
            .orElseThrow(() -> new ApiException(401, "Guest session expired"));
    }

    public UUID id(
        String key) {
        return (UUID) require(key).get("id");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consume(
        String key,
        String feature,
        int tokens,
        UUID demoResume) {
        charge(id(key), feature, tokens, demoResume, "COMPLETED");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID reserve(
        UUID guestId,
        String feature,
        int tokens,
        UUID demoResume) {
        return charge(guestId, feature, tokens, demoResume, "PROCESSING");
    }

    private UUID charge(
        UUID guestId,
        String feature,
        int tokens,
        UUID demoResume,
        String status) {
        if (tokens < 0)
            throw ApiException.badRequest("Invalid token usage");

        int changed = db.updateGuestSessionsById(tokens, guestId, tokens);
        if (changed != 1)
            throw new ApiException(429, "Trial limit reached; register an account to continue");

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("guest_session_id", guestId);
        event.put("feature_code", feature);
        event.put("tokens_used", tokens);
        event.put("status", status);
        event.put("created_at", OffsetDateTime.now());

        if (demoResume != null)
            event.put("demo_resume_id", demoResume);

        return db.insertTrialUsageEvents(event);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settle(
        UUID eventId,
        int actualTokens,
        boolean success) {
        Map<String, Object> event = db.getTrialUsageEventsByIdForUpdate(eventId);
        String status = success ? "COMPLETED" : "FAILED";

        if (!"PROCESSING".equals(event.get("status"))) {
            if (status.equals(event.get("status")) && actualTokens == ((Number) event.get("tokens_used")).intValue())
                return;

            throw ApiException.conflict("Trial usage already finalized");
        }

        int reserved = ((Number) event.get("tokens_used")).intValue();
        if (actualTokens < 0 || actualTokens > reserved)
            throw ApiException.badRequest("Actual trial usage exceeds the reserved budget");

        int refund = reserved - actualTokens;

        int changed = db.updateGuestSessionsById2(refund, event.get("guest_session_id"), refund);
        if (changed != 1)
            throw ApiException.conflict("Trial quota accounting is inconsistent");

        db.updateTrialUsageEventsById(actualTokens, status, eventId);
    }
}
