package com.example.intelligent_cv_matching_ai_agent.util.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * URL validation shared by ingestion and external job application workflows.
 */
public final class JobSourceValidator {

    private JobSourceValidator() {
    }

    public static URI validateExternalUrl(
        String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getPort() != -1)
                throw ApiException.badRequest("An external HTTPS URL is required");

            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (!(host.equals("itviec.com") || host.endsWith(".itviec.com") || host.equals("topcv.vn")
                || host.endsWith(".topcv.vn")))
                throw ApiException.badRequest("Only ITviec and TopCV source URLs are supported");

            return uri;
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("Invalid job URL");
        }
    }

    public static URI validateSourceUrl(
        String source,
        String url) {
        if (url == null || url.length() > 2000)
            throw ApiException.badRequest("Source URL is missing or too long");

        URI uri = validateExternalUrl(url);
        Set<String> hosts = switch (source) {
            case "ITVIEC" -> Set.of("itviec.com", "www.itviec.com");
            case "TOPCV" -> Set.of("topcv.vn", "www.topcv.vn");
            default -> throw ApiException.badRequest("sourceSite must be ITVIEC or TOPCV");
        };
        if (!hosts.contains(uri.getHost().toLowerCase(Locale.ROOT)))
            throw ApiException.badRequest("URL host does not match the selected source");

        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();

        return URI.create(
            "https://" + uri.getHost().toLowerCase(Locale.ROOT) + path
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
        ).normalize();
    }
}
