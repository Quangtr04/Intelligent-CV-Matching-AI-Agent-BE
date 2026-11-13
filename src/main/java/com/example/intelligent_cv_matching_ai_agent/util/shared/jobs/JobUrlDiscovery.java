package com.example.intelligent_cv_matching_ai_agent.util.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jsoup.Jsoup;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

/** Pure URL discovery; all fetching stays in the source-restricted crawler. */
public final class JobUrlDiscovery {

    private static final int MAX_HTML_CHARACTERS = 2 * 1024 * 1024;

    private static final Set<String> TRACKING_PARAMETERS =
        Set.of("gclid", "fbclid", "msclkid", "lab_feature", "segment_name", "segment_section", "ta_source", "u_sr_id");

    private static final Map<String, Map<String, String>> LOCATION_FILTERS = loadLocationFilters();

    private JobUrlDiscovery() {
    }

    public static String searchUrl(
        String source,
        String keyword,
        int page) {
        return searchUrl(source, keyword, null, page);
    }

    public static String searchUrl(
        String source,
        String keyword,
        String location,
        int page) {
        String normalizedSource = source == null ? "" : source.strip().toUpperCase(Locale.ROOT);
        if (keyword == null || keyword.isBlank() || keyword.length() > 255)
            throw ApiException.badRequest("A search keyword of at most 255 characters is required");
        if (page < 1 || page > 10)
            throw ApiException.badRequest("Search page must be 1..10");
        if (location != null && location.length() > 120)
            throw ApiException.badRequest("A search location must be at most 120 characters");

        String selectedLocation = JobNormalizer.normalizeLocationFilter(location);
        String encoded = URLEncoder.encode(keyword.strip(), StandardCharsets.UTF_8);
        String url = switch (normalizedSource) {
            case "ITVIEC" -> "https://itviec.com/it-jobs?query=" + encoded + "&page=" + page;
            case "TOPCV" -> "https://www.topcv.vn/tim-viec-lam-" + topCvKeywordPath(keyword) + "?page=" + page;
            default -> throw ApiException.badRequest("sourceSite must be ITVIEC or TOPCV");
        };

        String sourceLocation = LOCATION_FILTERS.get(normalizedSource).get(JobNormalizer.fold(selectedLocation));
        if (sourceLocation != null) {
            String parameter = "ITVIEC".equals(normalizedSource) ? "city" : "cityIds%5B%5D";
            url += "&" + parameter + "=" + URLEncoder.encode(sourceLocation, StandardCharsets.UTF_8);
        }

        return JobSourceValidator.validateSourceUrl(normalizedSource, url).toASCIIString();
    }

    /** TopCV exposes keyword search as a path, rather than the old bare search route. */
    private static String topCvKeywordPath(
        String keyword) {
        String slug = JobNormalizer.fold(keyword.strip()).replaceAll("\\s+", "-");
        return URLEncoder.encode(slug, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Map<String, Map<String, String>> loadLocationFilters() {
        try (var input = new ClassPathResource("jobs/source-location-filters.json").getInputStream()) {
            Map<String, Object> data = object(JsonMapper.builder().build().readValue(input, Map.class));
            Map<String, Object> sources = object(data.get("sources"));
            Map<String, Map<String, String>> filters = new LinkedHashMap<>();
            for (String source : List.of("ITVIEC", "TOPCV")) {
                Map<String, String> locations = new LinkedHashMap<>();
                for (var entry : object(sources.get(source)).entrySet()) {
                    String name = JobNormalizer.fold(JobNormalizer.normalizeLocation(entry.getKey()));
                    String value = text(entry.getValue());
                    if (name.isBlank() || !value.matches("[a-z0-9]+(?:-[a-z0-9]+)*"))
                        throw new IllegalArgumentException("Invalid source location filter");
                    locations.put(name, value);
                }
                if (locations.isEmpty())
                    throw new IllegalArgumentException("Missing source location filters");
                filters.put(source, Map.copyOf(locations));
            }
            return Map.copyOf(filters);
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("Unable to load source location filters", ex);
        }
    }

    public static List<String> discoverLinks(
        String html,
        String source,
        String baseUrl,
        int limit) {
        if (limit < 1 || limit > 1000)
            throw ApiException.badRequest("Discovery limit must be 1..1000");
        if (html == null || html.length() > MAX_HTML_CHARACTERS)
            throw ApiException.badRequest("Search HTML is missing or exceeds 2 MiB characters");

        String normalizedSource = source == null ? "" : source.strip().toUpperCase(Locale.ROOT);
        URI base = JobSourceValidator.validateSourceUrl(normalizedSource, baseUrl);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        int checked = 0;

        for (var link : Jsoup.parse(html).select("a[href]")) {
            if (++checked > 10000 || result.size() >= limit)
                break;
            String href = link.attr("href").strip();
            if (href.isBlank() || href.length() > 2000)
                continue;

            try {
                String resolved = base.resolve(href).toString();
                String canonical = canonicalUrl(normalizedSource, resolved);
                if (isDetailUrl(normalizedSource, canonical))
                    result.add(canonical);
            } catch (IllegalArgumentException | ApiException ignored) {
                // A search result may include ads, account links, or unsupported hosts.
            }
        }

        return List.copyOf(result);
    }

    public static boolean isDetailUrl(
        String source,
        String url) {
        try {
            URI validated = JobSourceValidator.validateSourceUrl(source, url);
            String path = validated.getRawPath();
            return switch (source) {
                case "ITVIEC" -> path.matches("/it-jobs/[^/]+-[0-9]{3,}/?");
                case "TOPCV" -> path.matches("/viec-lam/[^/]+/[0-9]+\\.html");
                default -> false;
            };
        } catch (IllegalArgumentException | ApiException ignored) {
            return false;
        }
    }

    /** Remove only known tracking keys; retain unknown query parameters unchanged. */
    public static String canonicalUrl(
        String source,
        String url) {
        URI validated = JobSourceValidator.validateSourceUrl(source, url);
        if (validated.getRawQuery() == null)
            return validated.toASCIIString();

        List<String> retained = new ArrayList<>();
        for (String parameter : validated.getRawQuery().split("&", -1)) {
            if (parameter.isEmpty())
                continue;
            String rawKey = parameter.split("=", 2)[0];
            String key;
            try {
                key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException ignored) {
                retained.add(parameter);
                continue;
            }
            if (!key.startsWith("utm_") && !TRACKING_PARAMETERS.contains(key))
                retained.add(parameter);
        }
        String base = validated.toASCIIString();
        base = base.substring(0, base.indexOf('?'));

        return retained.isEmpty() ? base : base + "?" + String.join("&", retained);
    }
}
