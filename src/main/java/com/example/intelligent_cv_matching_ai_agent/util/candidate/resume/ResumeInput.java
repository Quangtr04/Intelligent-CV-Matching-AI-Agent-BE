package com.example.intelligent_cv_matching_ai_agent.util.candidate.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeData;
import java.util.*;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.object;

public final class ResumeInput {

    private ResumeInput() {
    }

    public static UUID optionalUuid(
        Object value) {
        if (value == null)
            return null;

        try {
            return UUID.fromString(value.toString());
        } catch (Exception ex) {
            throw ApiException.badRequest("Invalid UUID");
        }
    }

    public static String requiredTitle(
        Map<String, Object> input) {
        String title = ResumeData.text(input.get("title"), "title", 255);
        if (title.isBlank())
            throw ApiException.badRequest("title is required");

        return title;
    }

    public static String language(
        Object value) {
        String lang = value == null ? "vi" : value.toString();
        if (!Set.of("vi", "en", "VI", "EN").contains(lang))
            throw ApiException.badRequest("language must be vi or en");

        return lang.toLowerCase(Locale.ROOT);
    }

    public static Map<String, Object> document(
        Map<String, Object> input) {
        if (!(input.get("structured_data") instanceof Map<?, ?>))
            throw ApiException.badRequest("structured_data must be an object");

        return object(input.get("structured_data"));
    }

    public static String skillSlug(
        String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-|-$", "");
        if (slug.isBlank())
            throw ApiException.badRequest("Invalid skill name");

        return slug;
    }
}
