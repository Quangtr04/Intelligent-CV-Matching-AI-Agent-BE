package com.example.intelligent_cv_matching_ai_agent.util.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.config.shared.jobs.JobCollectionProperties;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.CrawlSourceSettingsRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.response.admin.jobs.CrawlSettingsResponse;
import com.example.intelligent_cv_matching_ai_agent.dto.response.admin.jobs.CrawlSourceSettingsResponse;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobNormalizer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

/** Validation, normalization and immutable projections of crawl settings. */
public final class CrawlSettingsSupport {

    private CrawlSettingsSupport() {
    }

    public static CrawlSettingsResponse fromEnvironment(
        JobCollectionProperties configuration) {
        List<CrawlSourceSettingsResponse> sources = List.copyOf(configuration.getSources()).stream()
            .map(
                source -> new CrawlSourceSettingsResponse(
                    source.getSourceSite(),
                    source.getKeyword().strip(),
                    source.getRoleCategory().strip(),
                    JobNormalizer.normalizeLocationFilter(source.getLocation()),
                    source.getLimit(),
                    source.getMaxPages()
                )
            ).toList();
        return new CrawlSettingsResponse(
            configuration.isEnabled(),
            configuration.getFixedDelay().toString(),
            configuration.getActorId(),
            sources,
            "ENVIRONMENT",
            0,
            null,
            null
        );
    }

    public static CrawlSettingsResponse fromDatabase(
        Map<String, Object> row) {
        if (!(row.get("sources_json") instanceof List<?> entries)) {
            throw new IllegalStateException("Stored crawl sources must be an array");
        }
        List<CrawlSourceSettingsResponse> sources = entries.stream().map(raw -> {
            Map<String, Object> source = object(raw);
            return new CrawlSourceSettingsResponse(
                text(source.get("sourceSite")),
                text(source.get("keyword")),
                text(source.get("roleCategory")),
                source.get("location") == null ? null : text(source.get("location")),
                ((Number) source.get("limit")).intValue(),
                ((Number) source.get("maxPages")).intValue()
            );
        }).toList();
        return new CrawlSettingsResponse(
            Boolean.TRUE.equals(row.get("enabled")),
            Duration.ofMillis(((Number) row.get("fixed_delay_millis")).longValue()).toString(),
            (UUID) row.get("actor_id"),
            sources,
            "DATABASE",
            ((Number) row.get("version")).longValue(),
            (UUID) row.get("updated_by"),
            instant(row.get("updated_at"))
        );
    }

    public static Duration fixedDelay(
        String value) {
        if (value == null || value.isBlank() || value.length() > 100) {
            throw ApiException.badRequest("fixedDelay must be an ISO-8601 duration between PT1M and P30D");
        }
        try {
            Duration duration = Duration.parse(value.strip());
            if (duration.compareTo(Duration.ofMinutes(1)) < 0 || duration.compareTo(Duration.ofDays(30)) > 0) {
                throw ApiException.badRequest("fixedDelay must be between PT1M and P30D");
            }
            return Duration.ofMillis(duration.toMillis());
        } catch (DateTimeParseException | ArithmeticException exception) {
            throw ApiException.badRequest("fixedDelay must be an ISO-8601 duration between PT1M and P30D");
        }
    }

    public static CrawlSourceSettingsResponse normalize(
        CrawlSourceSettingsRequest source) {
        if (source == null) {
            throw ApiException.badRequest("Crawl sources cannot contain null entries");
        }
        String site = text(source.sourceSite()).strip().toUpperCase(Locale.ROOT);
        if (!Set.of("ITVIEC", "TOPCV").contains(site)) {
            throw ApiException.badRequest("sourceSite must be ITVIEC or TOPCV");
        }
        String keyword = bounded(source.keyword(), 255, "keyword");
        String role = bounded(source.roleCategory(), 100, "roleCategory");
        String location = source.location();
        if (location != null && (location.length() > 120 || !location.isBlank()
            && JobNormalizer.fold(location).codePoints().noneMatch(Character::isLetterOrDigit))) {
            throw ApiException.badRequest("location must contain a place name of at most 120 characters");
        }
        int limit = source.limit() == null ? 20 : source.limit();
        int maxPages = source.maxPages() == null ? 3 : source.maxPages();
        if (limit < 1 || limit > 20 || maxPages < 1 || maxPages > 5) {
            throw ApiException.badRequest("limit must be 1..20 and maxPages must be 1..5");
        }
        return new CrawlSourceSettingsResponse(
            site,
            keyword,
            role,
            JobNormalizer.normalizeLocationFilter(location),
            limit,
            maxPages
        );
    }

    private static String bounded(
        String value,
        int maximum,
        String field) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw ApiException.badRequest(field + " is required; maximum " + maximum + " characters");
        }
        return value.strip();
    }

    private static Instant instant(
        Object value) {
        if (value instanceof Instant timestamp) {
            return timestamp;
        }
        if (value instanceof OffsetDateTime timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return value == null ? null : Instant.parse(text(value));
    }
}
