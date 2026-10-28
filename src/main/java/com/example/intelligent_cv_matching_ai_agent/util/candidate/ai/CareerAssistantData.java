package com.example.intelligent_cv_matching_ai_agent.util.candidate.ai;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeData;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Validates assistant inputs and maps retrieved source data to citations. */
public final class CareerAssistantData {

    private CareerAssistantData() {
    }

    public static String message(
        Map<String, Object> body) {
        String message = ResumeData.text(body.get("message"), "message", 8000);
        if (message.isBlank())
            throw ApiException.badRequest("message is required");
        if (body.containsKey("retrieve") && !(body.get("retrieve") instanceof Boolean))
            throw ApiException.badRequest("retrieve must be boolean");

        return message;
    }

    public static String language(
        Map<String, Object> body) {
        Object raw = body.get("language");
        if (raw == null)
            return null;
        if (!(raw instanceof String value))
            throw ApiException.badRequest("language must be vi or en");
        String language = value.trim().toLowerCase(Locale.ROOT);
        if (!language.equals("vi") && !language.equals("en"))
            throw ApiException.badRequest("language must be vi or en");
        return language;
    }

    public static Object feedbackRating(
        Object rating) {
        if (rating != null && (!(rating instanceof Number n) || !Double.isFinite(n.doubleValue())
            || n.doubleValue() != n.intValue() || n.intValue() < 1 || n.intValue() > 5))
            throw ApiException.badRequest("rating must be an integer from 1 to 5");

        return rating;
    }

    public static Object feedbackHelpful(
        Object helpful) {
        if (helpful != null && !(helpful instanceof Boolean))
            throw ApiException.badRequest("is_helpful must be boolean");

        return helpful;
    }

    public static Map<String, Object> citation(
        UUID reply,
        Map<String, Object> source) {
        Map<String, Object> citation = new LinkedHashMap<>();
        citation.put("message_id", reply);
        citation.put("rag_chunk_id", source.get("chunk_id"));
        citation.put("rag_document_id", source.get("document_id"));
        citation.put("source_label", source.get("title"));
        citation.put("similarity_score", source.get("similarity"));
        citation.put("source_url", source.get("source_url"));
        citation.put("source_provider", source.get("source_provider"));

        return citation;
    }

    public static UUID contextUuid(
        Object raw) {
        if (raw == null)
            return null;

        try {
            return UUID.fromString(raw.toString());
        } catch (Exception ex) {
            throw ApiException.badRequest("Invalid context UUID");
        }
    }
}
