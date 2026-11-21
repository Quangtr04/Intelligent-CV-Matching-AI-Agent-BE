package com.example.intelligent_cv_matching_ai_agent.util.shared.ai;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.array;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.object;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.text;

/** Native schemas keep assessment and learning generation inside the selected JD's skill boundaries. */
final class LearningResponseSchema {

    private LearningResponseSchema() {
    }

    static Map<String, Object> schema(
        String feature,
        Map<String, Object> input) {
        String source = "ASSESSMENT_GENERATE".equals(feature) ? "jd_skills" : "allowed_missing_skills";
        List<String> names = array(input.get(source)).stream().map(value -> {
            if (value instanceof String name)
                return name;
            return text(object(value).get("skill_name"));
        }).filter(name -> !name.isBlank()).distinct().toList();
        if (names.isEmpty())
            throw new IllegalArgumentException("Selected JD skill context is required for " + feature);
        Map<String, Object> skill = Map.of("type", "string", "enum", names);
        if ("ASSESSMENT_GENERATE".equals(feature)) {
            // Generate the knowledge question before its supporting JD quote, so evidence does not become the quiz.
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("part", Map.of("type", "integer", "enum", List.of(1, 2)));
            fields.put("related_skill_name", skill);
            fields.put("question_text", textSchema(1, 300));
            Map<String, Object> options = new LinkedHashMap<>();
            for (String label : List.of("A", "B", "C", "D"))
                options.put(label, textSchema(1, 120));
            fields.put("options", objectSchema(options));
            fields.put("explanation", textSchema(1, 200));
            fields.put("correct_option", Map.of("type", "string", "enum", List.of("A", "B", "C", "D")));
            fields.put("jd_evidence", textSchema(8, 250));
            if (input.containsKey("resume")) {
                fields.put("cv_evidence", textSchema(0, 250));
            }
            Map<String, Object> question = objectSchema(fields);
            int count = AssessmentQuestionCount.from(input);
            return objectSchema(Map.of("questions", arraySchema(question, count, count)));
        }
        // A positional schema prevents duplicate/missing skills and self/forward dependencies before decoding.
        // Gap status and assessment evidence are authoritative server data, not model output.
        List<Map<String, Object>> nodes = java.util.stream.IntStream.range(0, names.size()).mapToObj(index -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("skill_name", Map.of("type", "string", "const", names.get(index)));
            fields.put("description", textSchema(1, 1500));
            fields.put("estimated_hours", Map.of("type", "integer", "minimum", 1, "maximum", 1000));
            fields.put(
                "prerequisites",
                arraySchema(Map.of("type", "integer", "minimum", 1, "maximum", Math.max(1, index)), 0, index)
            );
            return objectSchema(fields);
        }).toList();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("title", textSchema(1, 255));
        properties.put("summary", textSchema(1, 1000));
        properties.put("estimated_total_hours", Map.of("type", "integer", "minimum", 0, "maximum", 50000));
        properties.put(
            "nodes",
            Map.of("type", "array", "prefixItems", nodes, "minItems", names.size(), "maxItems", names.size())
        );
        return objectSchema(properties);
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
        int minimum,
        int maximum) {
        return Map.of("type", "string", "minLength", minimum, "maxLength", maximum);
    }

    private static Map<String, Object> arraySchema(
        Map<String, Object> items,
        int minimum,
        int maximum) {
        return Map.of("type", "array", "items", items, "minItems", minimum, "maxItems", maximum);
    }
}
