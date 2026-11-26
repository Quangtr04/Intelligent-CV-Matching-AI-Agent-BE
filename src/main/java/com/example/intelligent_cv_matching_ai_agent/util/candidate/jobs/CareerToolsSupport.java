package com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.util.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

public final class CareerToolsSupport {

    private CareerToolsSupport() {
    }

    public static Map<String, Object> analysisResponse(
        Map<String, Object> row,
        boolean cached) {
        Map<String, Object> response = new LinkedHashMap<>(row);
        var metadata = object(response.get("improvement_suggestions_json"));
        response.put("improvement_suggestions_json", array(metadata.get("items")));
        if (metadata.get("evidence_report") instanceof Map<?, ?> report)
            response.put("evidence_report", report);
        if (metadata.get("dimension_scores") instanceof Map<?, ?> scores)
            response.put("dimension_scores", scores);
        response.put("cached", cached);

        return response;
    }

    public static String requiredAi(
        Map<String, Object> result,
        String key) {
        String value = text(result.get(key));
        if (value.isBlank() || value.length() > 30000)
            throw new ApiException(502, "AI returned invalid " + key);

        return value;
    }

    public static String languageOf(
        Object value) {
        String language = value == null ? "" : text(value).toLowerCase(Locale.ROOT);

        return Set.of("vi", "en").contains(language) ? language : "vi";
    }

    public static void validateSalaryInterval(
        double min,
        double max) {
        if (max < min)
            throw new ApiException(502, "Invalid salary interval");
    }

    public static String salaryCurrency(
        Object value) {
        String currency = text(value);
        if (!Set.of("VND", "USD", "EUR").contains(currency))
            throw new ApiException(502, "Unsupported salary currency");

        return currency;
    }
}
