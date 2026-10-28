package com.example.intelligent_cv_matching_ai_agent.util.shared.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.time.LocalDate;
import java.util.*;

/**
 * A small, explicit document schema; clients edit extracted content before
 * confirming it.
 */
public final class ResumeData {

    private ResumeData() {
    }

    private static final Map<String, List<String>> SECTIONS = Map.of(
        "experience",
        List.of(
            "id",
            "company_name",
            "job_title",
            "location",
            "start_date",
            "end_date",
            "is_current",
            "description",
            "display_start_date",
            "display_end_date",
            "highlights"
        ),
        "education",
        List.of(
            "id",
            "school_name",
            "degree",
            "major",
            "gpa",
            "start_date",
            "end_date",
            "description",
            "display_start_date",
            "display_end_date",
            "highlights"
        ),
        "projects",
        List.of("id", "project_name", "role", "description", "technologies", "project_url", "start_date", "end_date"),
        "certifications",
        List.of("id", "name", "issuer", "issue_date", "expiry_date", "credential_url", "display_date"),
        "skills",
        List.of("name", "skill_id", "proficiency_level", "years_experience")
    );

    public static Map<String, Object> normalize(
        Map<String, Object> source) {
        if (source == null)
            throw ApiException.badRequest("structured_data is required");

        Map<String, Object> result = new LinkedHashMap<>();

        for (String name : List.of("full_name", "email", "phone", "location", "summary", "position")) {
            Object value = source.get(name);

            if (value != null)
                result.put(name, text(value, name, name.equals("summary") ? 20_000 : 500));
        }

        if (source.containsKey("frontend_fields")) {
            if (!(source.get("frontend_fields") instanceof Map<?, ?> fields)) {
                throw ApiException.badRequest("frontend_fields must be an object");
            }
            Map<String, Object> preserved = new LinkedHashMap<>();
            for (String key : List.of(
                "website",
                "linkedin",
                "github",
                "detailedSkills",
                "softSkills",
                "languages",
                "awards",
                "interests",
                "references",
                "activities",
                "dob",
                "avatarUrl"
            )) {
                if (fields.containsKey(key)) {
                    preserved.put(key, presentationValue(fields.get(key), 0));
                }
            }
            result.put("frontend_fields", preserved);
        }

        SECTIONS.forEach(
            (
                section,
                columns) -> {
                Object raw = source.getOrDefault(section, List.of());
                if (!(raw instanceof List<?> entries) || entries.size() > 100)
                    throw ApiException.badRequest(section + " must be an array of at most 100 entries");

                List<Map<String, Object>> normalized = new ArrayList<>();

                for (Object entry : entries) {
                    if (section.equals("skills") && entry instanceof String name)
                        entry = Map.of("name", name);

                    if (!(entry instanceof Map<?, ?> item))
                        throw ApiException.badRequest("Invalid " + section + " entry");

                    Map<String, Object> row = new LinkedHashMap<>();

                    for (String col : columns) {
                        Object value = item.get(col);

                        if (value == null || value instanceof String s && s.isBlank())
                            continue;

                        if (col.startsWith("display_") && col.endsWith("date")) {
                            row.put(col, text(value, col, 50));
                        } else if (col.equals("highlights")) {
                            if (!(value instanceof List<?> list) || list.size() > 100) {
                                throw ApiException.badRequest("highlights must be an array of at most 100 entries");
                            }
                            row.put(col, list.stream().map(itemValue -> text(itemValue, col, 20_000)).toList());
                        } else if (col.endsWith("_date")) {
                            try {
                                row.put(col, LocalDate.parse(value.toString()).toString());
                            } catch (Exception ex) {
                                throw ApiException.badRequest(col + " must be YYYY-MM-DD");
                            }
                        } else if (col.equals("skill_id")) {
                            try {
                                row.put(col, UUID.fromString(value.toString()).toString());
                            } catch (Exception ex) {
                                throw ApiException.badRequest("Invalid skill_id");
                            }
                        } else if (col.equals("is_current")) {
                            if (!(value instanceof Boolean))
                                throw ApiException.badRequest("is_current must be boolean");

                            row.put(col, value);
                        } else if (col.equals("years_experience")) {
                            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                                || number.doubleValue() < 0 || number.doubleValue() > 99)
                                throw ApiException.badRequest("years_experience must be between 0 and 99");

                            row.put(col, value);
                        } else if (col.equals("technologies")) {
                            if (!(value instanceof List<?> list) || list.size() > 100)
                                throw ApiException.badRequest("technologies must be an array");

                            row.put(col, list.stream().map(v -> text(v, col, 150)).toList());
                        } else
                            row.put(
                                col,
                                text(
                                    value,
                                    col,
                                    col.equals("description") ? 20_000 : col.equals("proficiency_level") ? 50 : 255
                                )
                            );
                    }

                    if ((section.equals("certifications") || section.equals("skills")) && !row.containsKey("name"))
                        throw ApiException.badRequest(section + " requires name");
                    if (section.equals("experience") && Boolean.TRUE.equals(row.get("is_current"))
                        && row.containsKey("end_date"))
                        throw ApiException.badRequest("Current experience must not have end_date");

                    orderedDates(row, "start_date", "end_date");
                    orderedDates(row, "issue_date", "expiry_date");
                    normalized.add(row);
                }

                result.put(section, normalized);
            }
        );

        return result;
    }

    /** Preserve bounded presentation data without weakening the canonical CV schema. */
    private static Object presentationValue(
        Object value,
        int depth) {
        if (depth > 4) {
            throw ApiException.badRequest("CV presentation fields are nested too deeply");
        }
        if (value == null || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String) {
            return text(value, "CV presentation field", 20_000);
        }
        if (value instanceof Number number) {
            if (!Double.isFinite(number.doubleValue())) {
                throw ApiException.badRequest("CV presentation number must be finite");
            }
            return number;
        }
        if (value instanceof List<?> list && list.size() <= 100) {
            return list.stream().map(item -> presentationValue(item, depth + 1)).toList();
        }
        if (value instanceof Map<?, ?> map && map.size() <= 30) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = text(entry.getKey(), "CV presentation key", 100);
                result.put(key, presentationValue(entry.getValue(), depth + 1));
            }
            return result;
        }
        throw ApiException.badRequest("Invalid CV presentation field");
    }

    private static void orderedDates(
        Map<String, Object> row,
        String start,
        String end) {
        if (row.containsKey(start) && row.containsKey(end)
            && LocalDate.parse(row.get(end).toString()).isBefore(LocalDate.parse(row.get(start).toString())))
            throw ApiException.badRequest(end + " must not precede " + start);
    }

    public static String text(
        Object value,
        String name,
        int max) {
        if (!(value instanceof String s) || s.length() > max || s.indexOf('\0') >= 0)
            throw ApiException.badRequest(name + " must be a string of at most " + max + " characters");

        return s.strip();
    }
}
