package com.example.intelligent_cv_matching_ai_agent.util.consultant.consultation;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.text;

/** Validates AI evaluation fields and prepares consultation response data. */
public final class ConsultationEvaluationData {

    private ConsultationEvaluationData() {
    }

    public static Map<String, Object> suggestion(
        Object value) {
        if (!(value instanceof Map<?, ?> row)) {
            throw new ApiException(502, "AI returned an invalid CV suggestion");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("category", "severity", "original_content", "suggested_content", "explanation")) {
            if (row.get(key) != null) {
                int limit = "category".equals(key) ? 100 : "severity".equals(key) ? 30 : 100000;
                result.put(key, aiText(row.get(key), "suggestion " + key, limit, false));
            }
        }
        return result;
    }

    public static List<?> aiArray(
        Map<String, Object> generated,
        String key) {
        if (!(generated.get(key) instanceof List<?> rows)) {
            throw new ApiException(502, "AI returned invalid " + key);
        }
        return rows;
    }

    public static List<String> aiStrings(
        Map<String, Object> generated,
        String key) {
        return aiArray(generated, key).stream().map(value -> aiText(value, key, 100000, true)).toList();
    }

    public static String aiText(
        Object value,
        String field,
        int limit,
        boolean required) {
        if (!(value instanceof String result) || result.length() > limit || (required && result.isBlank())) {
            throw new ApiException(502, "AI returned invalid " + field);
        }
        return result;
    }

    public static String languageOf(
        Map<String, Object> resume) {
        String language = text(resume.get("language")).toLowerCase(Locale.ROOT);
        return List.of("vi", "en").contains(language) ? language : "vi";
    }

    public static Map<String, Object> atsResponse(
        Map<String, Object> row,
        boolean cached) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("cached", cached);
        return result;
    }
}
