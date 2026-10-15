package com.example.intelligent_cv_matching_ai_agent.learning;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.shared.learning.CourseLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CourseLinkTests {

    @Test
    void canonicalizesEquivalentVideoLinksAndDropsTracking() {
        String expected = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
        assertEquals(expected, CourseLink.parse("https://youtu.be/dQw4w9WgXcQ?si=tracking&t=10").url());
        assertEquals(
            expected,
            CourseLink.parse("https://m.youtube.com/watch?v=dQw4w9WgXcQ&feature=share#section").url()
        );
        assertEquals(expected, CourseLink.parse("https://YOUTUBE.com:443/watch?v=dQw4w9WgXcQ").url());
    }

    @Test
    void keepsPlaylistIdentityAndCourseIdentityWithoutTracking() {
        assertEquals(
            "https://www.youtube.com/playlist?list=PLtest_123456789",
            CourseLink.parse("https://youtube.com/playlist?list=PLtest_123456789&si=track").url()
        );
        CourseLink link = CourseLink.parse("https://coursera.org/learn/machine-learning/?utm_source=email#about");
        assertEquals("https://www.coursera.org/learn/machine-learning", link.url());
        assertEquals("Coursera", link.provider());
        assertEquals("COURSE", link.resourceType());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "https://www.coursera.org/specializations/python",
            "https://www.coursera.org/professional-certificates/google-data-analytics",
            "https://www.coursera.org/projects/python-introduction",
            "https://www.coursera.org/guided-projects/sql-basics"}
    )
    void acceptsSupportedCourseFamilies(
        String url) {
        assertEquals(url, CourseLink.parse(url).url());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "http://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ",
            "https://coursera.org.evil.example/learn/python",
            "https://evil.example@youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com:8443/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/redirect?q=https://evil.example",
            "https://youtube.com/watch?v=short",
            "https://youtube.com/watch?v=dQw4w9WgXcQ&v=abcdefghijk",
            "https://youtube.com/watch?%76=dQw4w9WgXcQ&v=abcdefghijk",
            "https://youtube.com/playlist?list=short",
            "https://youtu.be",
            "https://youtu.be/",
            "https://youtu.be/dQw4w9WgXcQ/extra",
            "https://coursera.org/search?query=java",
            "https://coursera.org/learn/python/redirect",
            "https://coursera.org/learn/%2e%2e",
            "https://coursera.org/learn/../redirect",
            "https://coursera.org/learn/",
            "javascript:alert(1)",
            ""}
    )
    void rejectsUnsupportedOrMisleadingUrls(
        String url) {
        assertEquals(400, assertThrows(ApiException.class, () -> CourseLink.parse(url)).status());
    }

    @Test
    void skillMatchingHandlesAccentsAndPreservesLanguageDistinctions() {
        assertEquals("spring-boot", CourseLink.skillSlug(" Spring Boot "));
        assertEquals("lap-trinh", CourseLink.skillSlug("Lập trình"));
        assertNotEquals(CourseLink.skillSlug("C"), CourseLink.skillSlug("C++"));
        assertNotEquals(CourseLink.skillSlug("C++"), CourseLink.skillSlug("C#"));
    }
}
