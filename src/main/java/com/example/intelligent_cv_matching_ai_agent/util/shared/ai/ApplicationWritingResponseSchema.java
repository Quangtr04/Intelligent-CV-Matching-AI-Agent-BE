package com.example.intelligent_cv_matching_ai_agent.util.shared.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps application writing on its persisted subject, email and cover-letter contract. */
final class ApplicationWritingResponseSchema {

    private ApplicationWritingResponseSchema() {
    }

    static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("subject", text(200));
        properties.put("email", text(3500));
        properties.put("cover_letter", text(5500));
        return Map.of(
            "type",
            "object",
            "properties",
            properties,
            "required",
            List.of("subject", "email", "cover_letter"),
            "additionalProperties",
            false
        );
    }

    private static Map<String, Object> text(
        int maximum) {
        return Map.of("type", "string", "minLength", 1, "maxLength", maximum);
    }
}
