package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.resume;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/**
 * CV sections and AI builder facts are intentionally extensible JSON objects.
 * The request retains absent versus explicitly null fields for PATCH semantics;
 * the service validates dates, skills, ownership and confirmation rules.
 */
public final class ResumeWriteRequest {

    private final Map<String, Object> values = new LinkedHashMap<>();

    @JsonAnySetter
    public void put(
        String name,
        Object value) {
        values.put(name, value);
    }

    @JsonAnyGetter
    public Map<String, Object> properties() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public Map<String, Object> toInputMap() {
        return new LinkedHashMap<>(values);
    }

    @AssertTrue(message = "CV title, language, raw_content, structured_data and is_primary have invalid types")
    @JsonIgnore
    public boolean isValidShape() {
        return optionalText("title", 255)
            && (!values.containsKey("language") || values.get("language") == null || optionalText("language", 2))
            && (!values.containsKey("raw_content") || values.get("raw_content") == null
                || optionalText("raw_content", 200000))
            && (!values.containsKey("structured_data") || values.get("structured_data") instanceof Map<?, ?>)
            && (!values.containsKey("is_primary") || values.get("is_primary") == null
                || values.get("is_primary") instanceof Boolean);
    }

    private boolean optionalText(
        String key,
        int maximum) {
        if (!values.containsKey(key))
            return true;
        return values.get(key) instanceof String text && text.length() <= maximum && text.indexOf('\0') < 0;
    }
}
