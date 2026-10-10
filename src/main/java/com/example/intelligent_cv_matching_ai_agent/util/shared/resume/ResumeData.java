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
        List.of("company_name", "job_title", "start_date", "end_date", "is_current", "description"),
        "education",
        List.of("school_name", "degree", "major", "start_date", "end_date", "description"),
        "projects",
        List.of("project_name", "role", "description", "technologies", "project_url", "start_date", "end_date"),
        "certifications",
        List.of("name", "issuer", "issue_date", "expiry_date", "credential_url"),
        "skills",
        List.of("name", "skill_id", "proficiency_level", "years_experience")
    );

    public static Map<String, Object> normalize(
        Map<String, Object> source) {
        if (source == null)
            throw ApiException.badRequest("structured_data is required");

        Map<String, Object> result = new LinkedHashMap<>();

        for (String name : List.of("full_name", "email", "phone", "location", "summary")) {
            Object value = source.get(name);

            if (value != null)
                result.put(name, text(value, name, name.equals("summary") ? 20_000 : 500));
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

                        if (col.endsWith("_date")) {
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
