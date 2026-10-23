package com.example.intelligent_cv_matching_ai_agent.learning;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.LearningRepository;
import com.example.intelligent_cv_matching_ai_agent.service.shared.learning.LearningReferenceService;
import com.example.intelligent_cv_matching_ai_agent.util.shared.learning.RoadmapReferenceCatalog;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LearningReferenceServiceTest {

    @Mock
    private LearningRepository db;

    @Spy
    private RoadmapReferenceCatalog catalog = new RoadmapReferenceCatalog();

    @InjectMocks
    private LearningReferenceService service;

    @Test
    void javaAndJavaScriptSelectDistinctOfficialRoadmaps() {
        List<Map<String, Object>> java = catalog.select("Java Developer", List.of());
        List<Map<String, Object>> javaScript = catalog.select("JavaScript Developer", List.of());

        assertTrue(slugs(java).contains("java"));
        assertFalse(slugs(java).contains("javascript"));
        assertTrue(slugs(javaScript).contains("javascript"));
        assertFalse(slugs(javaScript).contains("java"));
        assertTrue(java.stream().anyMatch(row -> "https://roadmap.sh/java".equals(row.get("url"))));
        assertTrue(javaScript.stream().anyMatch(row -> "https://roadmap.sh/javascript".equals(row.get("url"))));
    }

    @Test
    void springAndSpringBootAliasesSelectTheSameOfficialRoadmap() {
        List<Map<String, Object>> spring = catalog.select("", List.of("Spring"));
        List<Map<String, Object>> springBoot = catalog.select("", List.of("Spring Boot"));

        assertTrue(slugs(spring).contains("spring-boot"));
        assertEquals(slugs(spring), slugs(springBoot));
        assertTrue(catalog.skillSlugs("Spring").contains("spring-boot"));
        assertTrue(catalog.skillSlugs("Spring Boot").contains("spring-boot"));
    }

    @Test
    void vietnameseBackendCareerSelectsTheVerifiedBackendReference() {
        List<Map<String, Object>> result = catalog.select("Lập trình viên Backend", List.of());

        assertTrue(slugs(result).contains("backend"));
        assertTrue(result.stream().anyMatch(row -> "https://roadmap.sh/backend".equals(row.get("url"))));
    }

    @Test
    void unsupportedCareerKeepsCatalogLinkWithoutInventingRoadmapsOrResources() {
        Map<String, Object> result = service.recommend("Underwater Basket Weaver", List.of());

        assertEquals("roadmap.sh", result.get("provider"));
        assertEquals(catalog.catalogUrl(), result.get("source_catalog_url"));
        assertEquals(List.of(), result.get("roadmaps"));
        assertEquals(List.of(), result.get("resources"));
    }

    @Test
    void assessmentFocusIncludesOnlyWeakTechnicalSkillsBelowSeventyPercent() {
        Map<String, Object> session = Map.of(
            "status",
            "completed",
            "target_job_title",
            "Java Backend",
            "skill_scores",
            List.of(
                score("Java", new BigDecimal("69.99")),
                score("Spring Boot", 20),
                score("SQL", 70),
                score("JavaScript", 90),
                score("General", 0)
            )
        );

        Map<String, Object> result = service.forAssessment(session);

        assertEquals(Set.of("Java", "Spring Boot"), new HashSet<>((List<?>) result.get("focus_skills")));
        assertTrue(roadmaps(result).isEmpty());
        verify(db, never()).listApprovedCourseResources(anyCollection(), anyString());
    }

    @Test
    void knownCatalogResourceDetailsAreReturnedWithoutInventedCourseUrls() {
        Map<String, Object> resource = resource(UUID.randomUUID());
        when(db.listLearningResourcesByUrls(anyCollection())).thenReturn(List.of(resource));
        when(db.listLearningResourcesForSkill(anyString(), anyString())).thenReturn(List.of(resource));

        Map<String, Object> result = service.recommend("Java Backend", List.of("Spring Boot"));

        assertEquals(List.of(resource), result.get("resources"));
        assertEquals("https://roadmap.sh/spring-boot", resource.get("url"));
        verify(db, atLeastOnce())
            .listLearningResourcesByUrls(argThat(urls -> urls.contains("https://roadmap.sh/spring-boot")));
    }

    @Test
    void resourceQueriesUseSpringAliasesAndRetainLegacyTitleMatching() {
        Map<String, Object> resource = resource(UUID.randomUUID());
        when(db.listLearningResourcesForSkill(anyString(), anyString())).thenReturn(List.of(resource));

        List<Map<String, Object>> resources = service.resourcesForSkill("Spring Boot");

        assertEquals(List.of(resource), resources);
        ArgumentCaptor<String> slug = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(db, atLeastOnce()).listLearningResourcesForSkill(slug.capture(), title.capture());
        assertTrue(slug.getAllValues().contains("spring-boot"));
        assertTrue(slug.getAllValues().contains("spring"));
        assertTrue(title.getAllValues().stream().anyMatch(pattern -> pattern.toLowerCase().contains("spring boot")));
    }

    @Test
    void blankSkillsDoNotTriggerBroadCatalogResourceSearches() {
        assertEquals(List.of(), service.resourcesForSkill(" "));
        verifyNoInteractions(db);
    }

    @Test
    void unsafeLegacyResourceUrlsAreExcludedFromRecommendations() {
        Map<String, Object> valid = resource(UUID.randomUUID());
        when(db.listLearningResourcesForSkill(anyString(), anyString())).thenReturn(
            List.of(
                Map.of("id", UUID.randomUUID(), "url", "javascript:alert(1)"),
                Map.of("id", UUID.randomUUID(), "url", "http://example.invalid/course"),
                Map.of("id", UUID.randomUUID(), "url", "https://user:password@example.invalid/course"),
                valid
            )
        );

        assertEquals(List.of(valid), service.resourcesForSkill("Spring Boot"));
    }

    @Test
    void approvedYoutubeAndCourseraCoursesAppearBeforeTheCatalogLimit() {
        Map<String, Object> youtube = course("https://www.youtube.com/watch?v=course123", "YouTube");
        Map<String, Object> coursera = course("https://www.coursera.org/learn/java", "Coursera");
        List<Map<String, Object>> official = IntStream.range(0, 15).mapToObj(
            index -> Map.<String, Object>of("id", UUID.randomUUID(), "url", "https://docs.example.test/java/" + index)
        ).toList();
        when(db.listLearningResourcesByUrls(anyCollection())).thenReturn(official);
        when(db.listLearningResourcesForSkill(anyString(), anyString())).thenReturn(List.of(youtube, coursera));

        List<?> resources = (List<?>) service.recommend("Java Backend", List.of("Java")).get("resources");

        assertEquals(15, resources.size());
        assertEquals(youtube, resources.get(0));
        assertEquals(coursera, resources.get(1));
    }

    @Test
    void targetOnlyRecommendationsCanSelectApprovedCoursesWithoutExplicitSkills() {
        Map<String, Object> youtube = course("https://www.youtube.com/watch?v=course123", "YouTube");
        when(db.listApprovedCourseResources(anyCollection(), eq("java-backend-developer")))
            .thenReturn(List.of(youtube));

        Map<String, Object> result = service.recommend("Java Backend Developer", List.of());

        assertEquals(List.of(youtube), result.get("resources"));
        verify(db).listApprovedCourseResources(argThat(slugs -> slugs.contains("java")), eq("java-backend-developer"));
    }

    @Test
    void customSkillNamesQueryTheSameNormalizedSlugAsAdminCourseLinks() {
        Map<String, Object> course = course("https://www.coursera.org/learn/flink", "Coursera");
        when(db.listLearningResourcesForSkill(eq("apache-flink"), anyString())).thenReturn(List.of(course));

        assertEquals(List.of(course), service.resourcesForSkill("Apache Flink"));

        verify(db).listLearningResourcesForSkill("apache-flink", "%Apache Flink%");
    }

    private static Map<String, Object> course(
        String url,
        String provider) {
        return Map.of(
            "id",
            UUID.randomUUID(),
            "title",
            "Approved course",
            "provider",
            provider,
            "url",
            url,
            "metadata_json",
            Map.of("course_id", UUID.randomUUID(), "reviewed", true)
        );
    }

    private static Map<String, Object> score(
        String skill,
        Number score) {
        return Map.of("skill", skill, "total", 10, "correct", 0, "score", score);
    }

    private static Map<String, Object> resource(
        UUID id) {
        return Map.of(
            "id",
            id,
            "title",
            "Spring Boot",
            "resource_type",
            "ROADMAP",
            "provider",
            "roadmap.sh",
            "url",
            "https://roadmap.sh/spring-boot",
            "is_free",
            true,
            "metadata_json",
            Map.of("skill_slugs", List.of("spring", "spring-boot"), "source_type", "OFFICIAL")
        );
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> roadmaps(
        Map<String, Object> recommendations) {
        return (List<Map<String, Object>>) recommendations.get("roadmaps");
    }

    private static Set<String> slugs(
        List<Map<String, Object>> roadmaps) {
        Set<String> slugs = new HashSet<>();
        for (Map<String, Object> roadmap : roadmaps) {
            slugs.add(roadmap.get("slug").toString());
        }
        return slugs;
    }
}
