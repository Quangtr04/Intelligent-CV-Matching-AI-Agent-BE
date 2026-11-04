package com.example.intelligent_cv_matching_ai_agent.util.shared.ai;

import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeWritingQuality;
import com.example.intelligent_cv_matching_ai_agent.util.shared.Values;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** A CV writing proposal is reviewed by the candidate; this boundary never changes stored CV content. */
public final class CvSupportResponseSchema {

    private static final Pattern PLACEHOLDER =
        Pattern.compile("(?iu)\\[(?:number|metrics?|số liệu|con số|tên công ty|company name)[^\\]\\r\\n]*\\]");

    private CvSupportResponseSchema() {
    }

    public static Map<String, Object> schema(
        Map<String, Object> input) {
        Map<String, Object> suggestion = object(
            Map.of(
                "original_content",
                Map.of("type", "string", "enum", originals(input)),
                "suggested_content",
                text(1, 6000),
                "explanation",
                Map.of(
                    "type",
                    "string",
                    "minLength",
                    1,
                    "maxLength",
                    1000,
                    "pattern",
                    CvAnalysisLanguage.textPattern(1, 1000)
                )
            )
        );
        return object(
            Map.of(
                "answer",
                text(1, 6000),
                "suggestions",
                Map.of("type", "array", "items", suggestion, "minItems", 0, "maxItems", 3)
            )
        );
    }

    public static void validate(
        Map<String, Object> result) {
        requiredText(result.get("answer"), "answer", 6000, false);
        if (!(result.get("suggestions") instanceof List<?> suggestions) || suggestions.size() > 3) {
            throw new IllegalArgumentException("CV support suggestions must contain at most three items");
        }
        for (Object item : suggestions) {
            if (!(item instanceof Map<?, ?> row)) {
                throw new IllegalArgumentException("CV support suggestion must be an object");
            }
            requiredText(row.get("original_content"), "original_content", 8000, true);
            requiredText(row.get("suggested_content"), "suggested_content", 6000, false);
            requiredText(row.get("explanation"), "explanation", 1000, false);
        }
        Map<String, Object> prose = new LinkedHashMap<>(result);
        prose.put("summary", result.get("answer"));
        CvAnalysisLanguage.validate(prose);
        if (PLACEHOLDER.matcher(result.get("answer").toString()).find()) {
            throw new IllegalArgumentException("CV support must not invent numeric or employer placeholders");
        }
        for (Object item : suggestions) {
            if (PLACEHOLDER.matcher(((Map<?, ?>) item).get("suggested_content").toString()).find()) {
                throw new IllegalArgumentException("CV support must not invent numeric or employer placeholders");
            }
        }
    }

    public static void validateProposal(
        Map<String, Object> result,
        Map<String, Object> input) {
        validate(result);
        List<String> allowed = originals(input);
        for (Object item : (List<?>) result.get("suggestions")) {
            String original = ((Map<?, ?>) item).get("original_content").toString();
            if (!allowed.contains(original)) {
                throw new IllegalArgumentException("CV support original_content must be a literal source excerpt");
            }
        }
    }

    private static List<String> originals(
        Map<String, Object> input) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add("");
        Map<String, Object> section = Values.object(input.get("section"));
        String current = Objects.toString(section.get("current_text"), "");
        if (!current.isBlank()) {
            if (current.length() <= 1200) {
                result.add(current);
            }
            result.addAll(ResumeWritingQuality.evidenceOptions(Map.of("raw_text", current)));
        }
        result.addAll(ResumeWritingQuality.evidenceOptions(Values.object(input.get("resume"))));
        return List.copyOf(result);
    }

    private static void requiredText(
        Object value,
        String field,
        int maximum,
        boolean allowEmpty) {
        if (!(value instanceof String text) || text.length() > maximum || !allowEmpty && text.isBlank()) {
            throw new IllegalArgumentException("Invalid CV support " + field);
        }
    }

    private static Map<String, Object> text(
        int minimum,
        int maximum) {
        return Map.of("type", "string", "minLength", minimum, "maxLength", maximum);
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
}
