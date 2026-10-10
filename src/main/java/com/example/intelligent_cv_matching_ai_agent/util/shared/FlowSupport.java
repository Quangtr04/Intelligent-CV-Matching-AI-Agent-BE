package com.example.intelligent_cv_matching_ai_agent.util.shared;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class FlowSupport {

    private FlowSupport() {
    }

    public static String hash(
        String text) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static double score(
        Object value,
        double max,
        String field) {
        double number = Values.number(value, Double.NaN);
        if (!Double.isFinite(number) || number < 0 || number > max)
            throw new ApiException(502, "AI returned invalid " + field);

        return number;
    }

    public static String vector(
        float[] values) {
        if (values.length != 1536)
            throw new ApiException(502, "Embedding model must return 1536 dimensions");

        StringJoiner joiner = new StringJoiner(",", "[", "]");

        for (float value : values) {
            if (!Float.isFinite(value))
                throw new ApiException(502, "Invalid embedding");

            joiner.add(Float.toString(value));
        }

        return joiner.toString();
    }

    public static Set<String> words(
        String text) {
        Set<String> words = new TreeSet<>();

        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}+#.]+"))
            if (word.length() > 1)
                words.add(word);

        return words;
    }
}
