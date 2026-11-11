package com.example.intelligent_cv_matching_ai_agent.util.consultant.consultation;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;

/** Session bookings disclose only the assets explicitly attached by their candidate. */
public final class ConsultationAttachments {

    private ConsultationAttachments() {
    }

    public static boolean bound(
        Map<String, Object> thread) {
        return "SESSION_BOOKING".equals(thread.get("request_type"));
    }

    public static UUID resume(
        Map<String, Object> thread,
        UUID requested) {
        return selected(thread, "resume_id", requested);
    }

    public static UUID job(
        Map<String, Object> thread,
        UUID requested) {
        return selected(thread, "job_id", requested);
    }

    public static void requireReadable(
        Map<String, Object> thread,
        UUID viewer,
        boolean admin) {
        if (bound(thread) && !admin && !viewer.equals(thread.get("candidate_user_id"))
            && (!Set.of("PENDING", "ACCEPTED", "COMPLETED").contains(String.valueOf(thread.get("booking_status")))
                || Boolean.TRUE.equals(thread.get("credit_refunded")))) {
            throw ApiException.notFound("Consultation preparation is no longer shared with this consultant");
        }
    }

    private static UUID selected(
        Map<String, Object> thread,
        String field,
        UUID requested) {
        if (!bound(thread)) {
            return requested;
        }
        UUID selected = (UUID) thread.get(field);
        if (requested != null && !Objects.equals(selected, requested)) {
            throw ApiException.notFound("The requested asset is not attached to this consultation booking");
        }
        return selected;
    }
}
