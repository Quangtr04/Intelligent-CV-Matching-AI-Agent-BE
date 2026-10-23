package com.example.intelligent_cv_matching_ai_agent.util.admin.rag;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.shared.learning.CourseLink;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validation and document text for administrator-reviewed course links. */
public final class RagCourseSupport {

    private RagCourseSupport() {
    }

    public static String content(
        Map<String, Object> course,
        List<String> skills) {
        return "Admin-reviewed learning resource\nTitle: " + course.get("title") + "\nURL: " + course.get("url")
            + "\nProvider: " + course.get("provider") + "\nSkills: " + String.join(", ", skills) + "\nLanguage: "
            + course.get("language") + "\nFree: " + course.get("is_free") + "\nDescription: "
            + course.get("description");
    }

    public static List<String> skills(
        Object input) {
        if (!(input instanceof List<?> values) || values.isEmpty() || values.size() > 30) {
            throw ApiException.badRequest("skills must contain between 1 and 30 skill names");
        }

        Map<String, String> distinct = new LinkedHashMap<>();
        for (Object value : values) {
            if (!(value instanceof String name)) {
                throw ApiException.badRequest("Each skill must be a name");
            }
            String clean = text(name, "skill", 150);
            String slug = CourseLink.skillSlug(clean);
            if (slug.isBlank()) {
                throw ApiException.badRequest("Each skill must contain letters or numbers");
            }
            distinct.putIfAbsent(slug, clean);
        }
        return new ArrayList<>(distinct.values());
    }

    public static String text(
        String value,
        String name,
        int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw ApiException.badRequest(name + " is required and must not exceed " + maxLength + " characters");
        }
        return value.strip();
    }
}
