package com.example.intelligent_cv_matching_ai_agent.util.shared.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.array;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.object;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.text;

/** Constrains each bounded reranking batch to the supplied jobs and one compact result per job. */
final class JobMatchResponseSchema {

    private JobMatchResponseSchema() {
    }

    static Map<String, Object> schema(
        Map<String, Object> input) {
        List<String> ids = array(input.get("jobs")).stream().map(job -> text(object(job).get("id"))).toList();
        if (ids.isEmpty() || ids.size() > 3 || ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException("Job reranking requires one to three distinct job IDs");
        }
        for (String id : ids) {
            if (!UUID.fromString(id).toString().equalsIgnoreCase(id)) {
                throw new IllegalArgumentException("Job reranking requires canonical job UUIDs");
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("job_id", Map.of("type", "string", "enum", ids));
        fields.put("score", Map.of("type", "number", "minimum", 0, "maximum", 1));
        fields.put("reason", textSchema(240));
        fields.put("strengths", stringList());
        fields.put("skill_gaps", stringList());
        return objectSchema(
            Map.of(
                "results",
                Map.of("type", "array", "items", objectSchema(fields), "minItems", ids.size(), "maxItems", ids.size())
            )
        );
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

    private static Map<String, Object> textSchema(
        int maximum) {
        return Map.of("type", "string", "minLength", 1, "maxLength", maximum);
    }

    private static Map<String, Object> stringList() {
        return Map.of("type", "array", "items", textSchema(100), "minItems", 0, "maxItems", 5);
    }
}
