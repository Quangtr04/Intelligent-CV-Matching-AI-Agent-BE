package com.example.intelligent_cv_matching_ai_agent.util.shared.ai;

import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeContentQuality;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeWritingQuality;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import com.example.intelligent_cv_matching_ai_agent.util.shared.Values;

/** JSON schema requiring the rubric fields while keeping the response concise. */
final class CvWritingResponseSchema {

    private CvWritingResponseSchema() {
    }

    static Map<String, Object> schema(
        Map<String, Object> input) {
        List<String> quotes = ResumeWritingQuality.evidenceOptions(Values.object(input.get("resume")));
        Map<String, Object> evidence = quotes.isEmpty() ? text(1, 300) : Map.of("type", "string", "enum", quotes);
        List<String> originals = new ArrayList<>(quotes);
        originals.add("");
        Map<String, Object> suggestion = object(
            Map.of(
                "category",
                generatedText(1, 100),
                "severity",
                Map.of("type", "string", "enum", List.of("LOW", "MEDIUM", "HIGH")),
                "original_content",
                quotes.isEmpty() ? text(0, 2000) : Map.of("type", "string", "enum", originals),
                "suggested_content",
                generatedText(0, 2000),
                "explanation",
                generatedText(1, 1000)
            )
        );
        return object(
            Map.of(
                "content_score",
                score(),
                "ats_score",
                score(),
                "summary",
                generatedText(1, 1000),
                "strengths",
                array(generatedText(1, 500), 0, 10),
                "issues",
                array(generatedText(1, 500), 0, 10),
                "suggestions",
                array(suggestion, 0, 3),
                "content_quality",
                object(
                    Map.of(
                        "rubric_version",
                        Map.of("type", "string", "enum", List.of(ResumeContentQuality.RUBRIC_VERSION)),
                        "criteria",
                        criteria(ResumeContentQuality.CODES, 1, evidence)
                    )
                ),
                "writing_quality",
                object(
                    Map.of(
                        "rubric_version",
                        Map.of("type", "string", "enum", List.of("cv-writing-v1")),
                        "criteria",
                        criteria(ResumeWritingQuality.CODES, 0, evidence),
                        "limitations",
                        array(generatedText(1, 500), 0, 5)
                    )
                )
            )
        );
    }

    private static Map<String, Object> criteria(
        List<String> codes,
        int minimumAssessedEvidence,
        Map<String, Object> evidence) {
        List<Map<String, Object>> slots = codes.stream().map(
            code -> Map.<String, Object>of(
                "oneOf",
                List.of(criterion(code, true, minimumAssessedEvidence, evidence), criterion(code, false, 0, evidence))
            )
        ).toList();
        return Map.of("type", "array", "prefixItems", slots, "minItems", codes.size(), "maxItems", codes.size());
    }

    private static Map<String, Object> criterion(
        String code,
        boolean assessed,
        int minimumEvidence,
        Map<String, Object> evidence) {
        return object(
            Map.of(
                "code",
                Map.of("type", "string", "enum", List.of(code)),
                "status",
                Map.of(
                    "type",
                    "string",
                    "enum",
                    assessed ? List.of("ASSESSED") : List.of("NOT_APPLICABLE", "INSUFFICIENT_EVIDENCE")
                ),
                "score",
                assessed ? score() : Map.of("type", "null"),
                "feedback",
                generatedText(1, 600),
                "evidence",
                array(evidence, minimumEvidence, 2)
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

    private static Map<String, Object> generatedText(
        int minimum,
        int maximum) {
        return Map.of(
            "type",
            "string",
            "minLength",
            minimum,
            "maxLength",
            maximum,
            "pattern",
            CvAnalysisLanguage.textPattern(minimum, maximum)
        );
    }

    private static Map<String, Object> array(
        Map<String, Object> items,
        int minimum,
        int maximum) {
        return Map.of("type", "array", "items", items, "minItems", minimum, "maxItems", maximum);
    }
}
