package com.example.intelligent_cv_matching_ai_agent.util.shared.ai;

import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeProfileAlignment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.object;

/** The model compares seven observable profile dimensions; the application supplies salary context separately. */
final class CvProfileAlignmentResponseSchema {

    private CvProfileAlignmentResponseSchema() {
    }

    static Map<String, Object> schema(
        Map<String, Object> input) {
        Map<String, Object> profileQuote =
            quote(ResumeProfileAlignment.profileEvidenceOptions(object(input.get("career_profile"))));
        Map<String, Object> cvQuote = quote(ResumeProfileAlignment.cvEvidenceOptions(object(input.get("resume"))));
        List<String> explicitRoles = ResumeProfileAlignment.explicitCvRoles(object(input.get("resume")));
        Object target = object(input.get("career_profile")).get("target_job_title");
        boolean explicitRoleComparison = target instanceof String title && !title.isBlank() && !explicitRoles.isEmpty();
        List<String> ordinaryCodes = ResumeProfileAlignment.CODES.stream()
            .filter(code -> !ResumeProfileAlignment.CV_PREFERENCE_CODES.contains(code))
            .filter(code -> !explicitRoleComparison || !"TARGET_ROLE".equals(code)).toList();
        List<Map<String, Object>> states = new ArrayList<>();
        if (profileQuote.containsKey("enum") && cvQuote.containsKey("enum")) {
            states.add(criterion("ASSESSED", profileQuote, cvQuote, true, 2, ordinaryCodes));
        }
        states.add(criterion("MISSING_CV_EVIDENCE", profileQuote, cvQuote, false, 0, ordinaryCodes));
        states.add(criterion("INSUFFICIENT_EVIDENCE", profileQuote, cvQuote, false, 2, ordinaryCodes));
        states.add(criterion("NOT_APPLICABLE", profileQuote, cvQuote, false, 0, ordinaryCodes));
        if (explicitRoleComparison) {
            states.add(
                criterion(
                    "ASSESSED",
                    quote(List.of(target.toString())),
                    quote(explicitRoles),
                    true,
                    2,
                    List.of("TARGET_ROLE")
                )
            );
        }
        states.add(preferenceCriterion(profileQuote, cvQuote));
        Map<String, Object> criterion = Map.of("oneOf", states);
        return objectSchema(
            Map.of(
                "rubric_version",
                Map.of("type", "string", "enum", List.of(ResumeProfileAlignment.RUBRIC_VERSION)),
                "criteria",
                array(criterion, ResumeProfileAlignment.CODES.size(), ResumeProfileAlignment.CODES.size())
            )
        );
    }

    private static Map<String, Object> criterion(
        String status,
        Map<String, Object> profileQuote,
        Map<String, Object> cvQuote,
        boolean assessed,
        int maximumCvQuotes,
        List<String> codes) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("code", Map.of("type", "string", "enum", codes));
        fields.put("status", Map.of("type", "string", "enum", List.of(status)));
        fields.put("score", assessed ? Map.of("type", "number", "minimum", 0, "maximum", 100) : Map.of("type", "null"));
        fields.put("feedback", Map.of("type", "string", "minLength", 1, "maxLength", 300));
        fields.put("profile_evidence", array(profileQuote, assessed ? 1 : 0, profileQuote.containsKey("enum") ? 2 : 0));
        fields.put("cv_evidence", array(cvQuote, assessed ? 1 : 0, cvQuote.containsKey("enum") ? maximumCvQuotes : 0));
        fields.put("suggestions", array(Map.of("type", "string", "minLength", 1, "maxLength", 240), 0, 2));
        return objectSchema(fields);
    }

    private static Map<String, Object> preferenceCriterion(
        Map<String, Object> profileQuote,
        Map<String, Object> cvQuote) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("code", Map.of("type", "string", "enum", ResumeProfileAlignment.CV_PREFERENCE_CODES));
        fields.put("status", Map.of("type", "string", "enum", List.of("MISSING_CV_EVIDENCE", "NOT_APPLICABLE")));
        fields.put("score", Map.of("type", "null"));
        fields.put("feedback", Map.of("type", "string", "minLength", 1, "maxLength", 120));
        fields.put("profile_evidence", array(profileQuote, 0, profileQuote.containsKey("enum") ? 1 : 0));
        fields.put("cv_evidence", array(cvQuote, 0, 0));
        fields.put("suggestions", array(Map.of("type", "string"), 0, 0));
        return objectSchema(fields);
    }

    private static Map<String, Object> quote(
        List<String> options) {
        return options.isEmpty() ? Map.of("type", "string") : Map.of("type", "string", "enum", options);
    }

    private static Map<String, Object> objectSchema(
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

    private static Map<String, Object> array(
        Map<String, Object> items,
        int minimum,
        int maximum) {
        return Map.of("type", "array", "items", items, "minItems", minimum, "maxItems", maximum);
    }
}
