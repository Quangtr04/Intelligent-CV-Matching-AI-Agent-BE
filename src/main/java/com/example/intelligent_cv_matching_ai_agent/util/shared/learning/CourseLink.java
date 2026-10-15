package com.example.intelligent_cv_matching_ai_agent.util.shared.learning;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Canonical links accepted for manually reviewed learning recommendations. */
public record CourseLink(
    String url,
    String provider,
    String resourceType
) {

    private static final Set<String> YOUTUBE_HOSTS = Set.of("youtube.com", "www.youtube.com", "m.youtube.com");

    private static final Set<String> COURSERA_PATHS =
        Set.of("learn", "specializations", "professional-certificates", "projects", "guided-projects");

    public static CourseLink parse(
        String input) {
        if (input == null || input.isBlank() || input.length() > 2048)
            throw invalid();

        try {
            URI uri = URI.create(input.strip());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443))
                throw invalid();

            String path = uri.getRawPath();
            if (YOUTUBE_HOSTS.contains(host)) {
                Map<String, String> query = query(uri.getRawQuery());
                if ("/watch".equals(path) && videoId(query.get("v")))
                    return new CourseLink("https://www.youtube.com/watch?v=" + query.get("v"), "YouTube", "VIDEO");
                if ("/playlist".equals(path) && playlistId(query.get("list")))
                    return new CourseLink(
                        "https://www.youtube.com/playlist?list=" + query.get("list"),
                        "YouTube",
                        "VIDEO"
                    );
            } else if ("youtu.be".equals(host)) {
                String id = path == null || !path.startsWith("/") ? "" : path.substring(1);
                if (videoId(id))
                    return new CourseLink("https://www.youtube.com/watch?v=" + id, "YouTube", "VIDEO");
            } else if ("coursera.org".equals(host) || "www.coursera.org".equals(host)) {
                String normalizedPath = path == null ? "" : path.replaceFirst("/$", "");
                String[] segments = normalizedPath.split("/", -1);
                if (segments.length == 3 && COURSERA_PATHS.contains(segments[1])
                    && segments[2].matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,199}"))
                    return new CourseLink("https://www.coursera.org" + normalizedPath, "Coursera", "COURSE");
            }
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }

        throw invalid();
    }

    /** Match skill names consistently without collapsing C, C++ and C#. */
    public static String skillSlug(
        String input) {
        if (input == null)
            return "";

        String value =
            Normalizer.normalize(input.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace("++", " plus plus ").replace("#", " sharp ").replace("+", " plus ");
        return value.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private static Map<String, String> query(
        String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawQuery == null)
            return values;

        for (String part : rawQuery.split("&")) {
            String[] pair = part.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            if (values.putIfAbsent(key, value) != null && ("v".equals(key) || "list".equals(key)))
                throw invalid();
        }
        return values;
    }

    private static boolean videoId(
        String value) {
        return value != null && value.matches("[a-zA-Z0-9_-]{11}");
    }

    private static boolean playlistId(
        String value) {
        return value != null && value.matches("[a-zA-Z0-9_-]{10,200}");
    }

    private static ApiException invalid() {
        return ApiException.badRequest("url must be an HTTPS YouTube video/playlist or Coursera course link");
    }
}
