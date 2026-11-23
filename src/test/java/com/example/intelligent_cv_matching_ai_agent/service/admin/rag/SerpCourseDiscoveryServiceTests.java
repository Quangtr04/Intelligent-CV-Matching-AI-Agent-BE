package com.example.intelligent_cv_matching_ai_agent.service.admin.rag;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.rag.CourseSearchRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SerpCourseDiscoveryServiceTests {

    @Test
    void acceptsOnlySupportedCourseUrlsAndDeduplicatesCanonicalLinks() {
        var response = Map.<String, Object>of(
            "organic_results",
            List.of(
                result("https://youtu.be/dQw4w9WgXcQ"),
                result("https://www.youtube.com/watch?v=dQw4w9WgXcQ&feature=share"),
                result("https://www.coursera.org/learn/java?campaign=search"),
                result("https://www.youtube.com/@not-a-course"),
                result("https://example.test/java")
            )
        );
        var courses = SerpCourseDiscoveryService.candidates(new CourseSearchRequest("Java", "en", "ALL"), response);
        assertEquals(2, courses.size());
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", courses.getFirst().url());
        assertEquals(List.of("Java"), courses.getFirst().skills());
        var coursera =
            SerpCourseDiscoveryService.candidates(new CourseSearchRequest("Java", "vi", "COURSERA"), response);
        assertEquals(1, coursera.size());
        assertEquals("https://www.coursera.org/learn/java", coursera.getFirst().url());
        assertEquals("vi", coursera.getFirst().language());
    }

    @Test
    void missingConfigurationAndUnauthorizedUsersCannotTriggerImports() {
        var user = mock(CurrentUser.class);
        var courses = mock(RagCourseService.class);
        var service = new SerpCourseDiscoveryService(user, courses, "");
        var request = new CourseSearchRequest("Java", "en", "ALL");
        assertEquals(false, service.status().get("configured"));
        assertEquals(503, assertThrows(ApiException.class, () -> service.discover(request)).status());
        doThrow(ApiException.forbidden("Role required")).when(user).requireStaffOrOperationsRole();
        assertEquals(403, assertThrows(ApiException.class, () -> service.discover(request)).status());
        verifyNoInteractions(courses);
    }

    private static Map<String, Object> result(
        String url) {
        return Map.of("link", url, "title", "Java tutorial");
    }
}
