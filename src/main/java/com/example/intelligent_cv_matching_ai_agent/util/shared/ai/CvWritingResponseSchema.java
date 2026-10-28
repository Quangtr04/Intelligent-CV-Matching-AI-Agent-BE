package com.example.intelligent_cv_matching_ai_agent.util.shared.ai;

import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeContentQuality;
import java.util.List;
import java.util.Map;

/** Native Ollama JSON schema: require the rubric fields while keeping the response concise. */
final class CvWritingResponseSchema {

    private CvWritingResponseSchema() {
    }

    static Map<String, Object> schema() {
        Map<String, Object> criterion = object(
            Map.of(
                "code",
                Map.of(
                    "type",
                    "string",
                    "enum",
                    List.of(
                        "BULLET_STRUCTURE",
                        "PUNCTUATION_CONSISTENCY",
                        "PROFESSIONAL_WORDING",
                        "CONCISENESS",
                        "ACHIEVEMENT_CLARITY"
                    )
                ),
                "status",
                Map.of("type", "string", "enum", List.of("ASSESSED", "NOT_APPLICABLE", "INSUFFICIENT_EVIDENCE")),
                "score",
                Map.of("type", List.of("number", "null"), "minimum", 0, "maximum", 100),
                "feedback",
                text(1, 600),
                "evidence",
                array(text(1, 300), 0, 2)
            )
        );
        Map<String, Object> suggestion = object(
            Map.of(
                "category",
                text(1, 100),
                "severity",
                Map.of("type", "string", "enum", List.of("LOW", "MEDIUM", "HIGH")),
                "original_content",
                text(0, 2000),
                "suggested_content",
                text(0, 2000),
                "explanation",
                text(1, 1000)
            )
        );
        Map<String, Object> contentCriterion = object(
            Map.of(
                "code",
                Map.of("type", "string", "enum", ResumeContentQuality.CODES),
                "status",
                Map.of("type", "string", "enum", List.of("ASSESSED", "NOT_APPLICABLE", "INSUFFICIENT_EVIDENCE")),
                "score",
                Map.of("type", List.of("number", "null"), "minimum", 0, "maximum", 100),
                "feedback",
                text(1, 600),
                "evidence",
                array(text(1, 300), 0, 2)
            )
        );
        return object(
            Map.of(
                "content_score",
                score(),
                "ats_score",
                score(),
                "summary",
                text(1, 1000),
                "strengths",
                array(text(1, 500), 0, 10),
                "issues",
                array(text(1, 500), 0, 10),
                "suggestions",
                array(suggestion, 0, 3),
                "content_quality",
                object(
                    Map.of(
                        "rubric_version",
                        Map.of("type", "string", "enum", List.of(ResumeContentQuality.RUBRIC_VERSION)),
                        "criteria",
                        array(contentCriterion, ResumeContentQuality.CODES.size(), ResumeContentQuality.CODES.size())
                    )
                ),
                "writing_quality",
                object(
                    Map.of(
                        "rubric_version",
                        Map.of("type", "string", "enum", List.of("cv-writing-v1")),
                        "criteria",
                        array(criterion, 5, 5),
                        "limitations",
                        array(text(1, 500), 0, 5)
                    )
                )
            )
        );
    }

    private static Map<String, Object> object(
        Map<String, Object> properties) {
        return Map.of(
            "type",
            "object",
            "properties",
            properties,
            "required",
            properties.keySet().stream().sorted().toList(),
            "additionalProperties",
            false
        );
    }

    private static Map<String, Object> text(
        int minimum,
        int maximum) {
        return Map.of("type", "string", "minLength", minimum, "maxLength", maximum);
    }

    private static Map<String, Object> score() {
        return Map.of("type", "number", "minimum", 0, "maximum", 100);
    }

    private static Map<String, Object> array(
        Map<String, Object> items,
        int minimum,
        int maximum) {
        return Map.of("type", "array", "items", items, "minItems", minimum, "maxItems", maximum);
    }
}
