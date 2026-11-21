package com.example.intelligent_cv_matching_ai_agent.util.shared.ai;

import java.util.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

public final class RequirementEvidenceResponseSchema {

    private RequirementEvidenceResponseSchema() {
    }

    public static Map<String, Object> schema(
        Map<String, Object> input) {
        List<?> requirements = array(input.get("requirements"));
        List<String> ids = requirements.stream().map(row -> text(object(row).get("requirement_id"))).toList();
        Map<String, Object> citation = objectSchema(
            fields(
                "chunk_id",
                Map.of("type", "string"),
                "quote",
                Map.of("type", "string", "minLength", 1, "maxLength", 1400)
            )
        );
        Map<String, Object> result = objectSchema(
            fields(
                "requirement_id",
                Map.of("type", "string", "enum", ids),
                "status",
                Map.of(
                    "type",
                    "string",
                    "enum",
                    List.of("MET", "PARTIAL", "NOT_MET", "MISSING_EVIDENCE", "UNVERIFIED")
                ),
                "reason",
                Map.of("type", "string", "minLength", 1, "maxLength", 2000),
                "evidence",
                Map.of("type", "array", "items", citation, "maxItems", 6)
            )
        );
        return objectSchema(
            fields("results", Map.of("type", "array", "items", result, "minItems", ids.size(), "maxItems", ids.size()))
        );
    }

    private static Map<String, Object> objectSchema(
        Map<String, Object> properties) {
        return fields(
            "type",
            "object",
            "properties",
            properties,
            "required",
            new ArrayList<>(properties.keySet()),
            "additionalProperties",
            false
        );
    }

}
