package com.example.intelligent_cv_matching_ai_agent.service.admin.rag;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.rag.CourseLinkRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.rag.CourseSearchRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.util.shared.learning.CourseLink;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

@Service
public class SerpCourseDiscoveryService {

    private final CurrentUser user;

    private final RagCourseService courses;

    private final String key;

    private final RestClient client;

    public SerpCourseDiscoveryService(
        CurrentUser user,
        RagCourseService courses,
        @Value("${app.rag.serpapi-key:}")
        String key) {
        this.user = user;
        this.courses = courses;
        this.key = key.strip();
        var factory =
            new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public Map<String, Object> status() {
        user.requireStaffOrOperationsRole();
        return Map.of("configured", !key.isBlank());
    }

    public Map<String, Object> discover(
        CourseSearchRequest request) {
        user.requireStaffOrOperationsRole();
        if (key.isBlank())
            throw new ApiException(503, "SerpApi is not configured");
        String skill = request.skill().strip();
        if (CourseLink.skillSlug(skill).isBlank())
            throw ApiException.badRequest("Enter a valid skill name");
        var candidates = candidates(request, search(request));
        int added = courses.addDiscovered(candidates);
        return fields("found", candidates.size(), "added", added, "duplicates", candidates.size() - added);
    }

    private Map<String, Object> search(
        CourseSearchRequest request) {
        String sites = switch (request.provider()) {
            case "YOUTUBE" -> "site:youtube.com";
            case "COURSERA" -> "site:coursera.org";
            default -> "(site:youtube.com OR site:coursera.org)";
        };
        boolean vietnamese = "vi".equals(request.language());
        String query = request.skill().strip().replace('"', ' ')
            + (vietnamese ? " khóa học hướng dẫn " : " course tutorial ") + sites;
        URI uri = UriComponentsBuilder.fromUriString("https://serpapi.com/search.json").queryParam("engine", "google")
            .queryParam("q", "{q}").queryParam("api_key", "{key}").queryParam("hl", request.language())
            .queryParam("gl", vietnamese ? "vn" : "us").encode().buildAndExpand(query, key).toUri();
        try {
            var body = object(client.get().uri(uri).retrieve().body(Map.class));
            if (body.isEmpty() || body.containsKey("error")
                || !"Success".equals(object(body.get("search_metadata")).get("status")))
                throw new ApiException(502, "SerpApi did not complete the search");
            return body;
        } catch (ResourceAccessException failure) {
            throw new ApiException(504, "SerpApi did not respond in time");
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 429)
                throw new ApiException(429, "SerpApi search quota or rate limit reached");
            throw new ApiException(502, "SerpApi rejected the course search");
        } catch (RestClientException failure) {
            throw new ApiException(502, "SerpApi returned an invalid course search response");
        }
    }

    static List<CourseLinkRequest> candidates(
        CourseSearchRequest request,
        Map<String, Object> response) {
        var found = new LinkedHashMap<String, CourseLinkRequest>();
        for (Object raw : array(response.get("organic_results"))) {
            var row = object(raw);
            String title = text(row.get("title")).strip();
            if (title.isBlank())
                continue;
            CourseLink link;
            try {
                link = CourseLink.parse(text(row.get("link")));
            } catch (ApiException unsupportedLink) {
                continue;
            }
            if (!"ALL".equals(request.provider()) && !request.provider().equalsIgnoreCase(link.provider()))
                continue;
            String snippet = text(row.get("snippet")).strip();
            String description = snippet.isBlank() ? title : snippet;
            found.putIfAbsent(
                link.url(),
                new CourseLinkRequest(
                    title.substring(0, Math.min(title.length(), 255)),
                    link.url(),
                    description.substring(0, Math.min(description.length(), 12000)),
                    List.of(request.skill().strip()),
                    request.language(),
                    "YouTube".equals(link.provider())
                )
            );
            if (found.size() == 10)
                break;
        }
        return List.copyOf(found.values());
    }
}
