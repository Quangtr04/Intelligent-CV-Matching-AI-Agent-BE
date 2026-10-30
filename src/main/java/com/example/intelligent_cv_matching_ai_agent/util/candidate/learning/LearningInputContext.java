package com.example.intelligent_cv_matching_ai_agent.util.candidate.learning;

import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Stable fingerprints bind generated learning material to the exact authorized input snapshot. */
public final class LearningInputContext {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private LearningInputContext() {
    }

    public static String hash(
        Object value) {
        return FlowSupport.hash(JSON.writeValueAsString(stable(value)));
    }

    public static UUID optionalUuid(
        Object value) {
        return value == null || value.toString().isBlank() ? null : UUID.fromString(value.toString());
    }

    private static Object stable(
        Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach(
                (
                    key,
                    item) -> sorted.put(key.toString(), stable(item))
            );
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(LearningInputContext::stable).toList();
        }
        return value;
    }
}
