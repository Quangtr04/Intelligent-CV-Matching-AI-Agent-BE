package com.example.intelligent_cv_matching_ai_agent.util.shared;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import java.util.*;

public final class Values {

    private Values() {
    }

    public static Map<String, Object> fields(
        Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();

        for (int i = 0; i < pairs.length; i += 2)
            map.put((String) pairs[i], pairs[i + 1]);

        return map;
    }

    public static String text(
        Object value) {
        return value == null ? "" : value.toString();
    }

    public static String required(
        Map<String, Object> map,
        String key,
        int max) {
        String value = text(map.get(key)).strip();
        if (value.isEmpty() || value.length() > max)
            throw ApiException.badRequest(key + " is required; maximum " + max + " characters");

        return value;
    }

    public static List<?> array(
        Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(
        Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    public static double number(
        Object value,
        double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    public static UUID uuid(
        Object value) {
        try {
            return UUID.fromString(text(value));
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("Invalid UUID");
        }
    }

    public static int pageSize(
        int limit) {
        if (limit < 1 || limit > 100)
            throw ApiException.badRequest("limit must be 1..100");

        return limit;
    }

    public static int offset(
        int offset) {
        if (offset < 0 || offset > 100000)
            throw ApiException.badRequest("offset must be 0..100000");

        return offset;
    }
}
