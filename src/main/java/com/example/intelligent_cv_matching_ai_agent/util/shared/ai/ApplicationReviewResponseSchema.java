package com.example.intelligent_cv_matching_ai_agent.util.shared.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Structured review of a cover letter or application email that the candidate wrote. */
final class ApplicationReviewResponseSchema {

    static final List<String> SCORE_CODES = List.of("relevance", "clarity", "tone", "language", "structure");

    static final List<String> ISSUE_CATEGORIES =
        List.of("SPELLING", "GRAMMAR", "TONE", "CLARITY", "RELEVANCE", "STRUCTURE", "FACT", "LENGTH");

    private ApplicationReviewResponseSchema() {
    }

    static Map<String, Object> schema() {
        Map<String, Object> scores = new LinkedHashMap<>();
        for (String code : SCORE_CODES)
            scores.put(code, Map.of("type", "integer", "minimum", 0, "maximum", 100));

        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("category", Map.of("type", "string", "enum", ISSUE_CATEGORIES));
        issue.put("severity", Map.of("type", "string", "enum", List.of("HIGH", "MEDIUM", "LOW")));
        issue.put("original", text(0, 600));
        issue.put("suggestion", text(0, 1200));
        issue.put("explanation", text(1, 600));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("summary", text(1, 800));
        properties.put(
            "scores",
            Map.of("type", "object", "properties", scores, "required", SCORE_CODES, "additionalProperties", false)
        );
        properties.put("strengths", Map.of("type", "array", "maxItems", 6, "items", text(1, 300)));
        properties.put(
            "issues",
            Map.of(
                "type",
                "array",
                "maxItems",
                15,
                "items",
                Map.of(
                    "type",
                    "object",
                    "properties",
                    issue,
                    "required",
                    List.copyOf(issue.keySet()),
                    "additionalProperties",
                    false
                )
            )
        );
        properties.put("missing_keywords", Map.of("type", "array", "maxItems", 10, "items", text(1, 80)));
        return Map.of(
            "type",
            "object",
            "properties",
            properties,
            "required",
            List.copyOf(properties.keySet()),
            "additionalProperties",
            false
        );
    }

    private static Map<String, Object> text(
        int minimum,
        int maximum) {
        return Map.of("type", "string", "minLength", minimum, "maxLength", maximum);
    }
}
