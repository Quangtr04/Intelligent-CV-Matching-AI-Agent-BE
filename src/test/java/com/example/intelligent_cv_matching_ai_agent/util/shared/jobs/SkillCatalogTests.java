package com.example.intelligent_cv_matching_ai_agent.util.shared.jobs;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.learning.SkillNames;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeSkillMatcher;

class SkillCatalogTests {

    private SkillCatalog.Skill skill(
        String id,
        String name,
        List<String> aliases,
        List<String> topics) {
        return new SkillCatalog.Skill(
            id,
            name,
            "TEST",
            "TECHNOLOGY",
            aliases,
            List.of(),
            "",
            List.of(),
            List.of(),
            topics,
            List.of("test")
        );
    }

    private SkillCatalog.Document document(
        List<SkillCatalog.Skill> skills,
        List<SkillCatalog.Topic> topics) {
        return new SkillCatalog.Document(
            "test-1",
            List.of(new SkillCatalog.Source("test", "local:test", "1", "Project-authored", "Synthetic test data")),
            skills,
            topics
        );
    }

    private Set<String> names(
        String source) {
        return SkillCatalog.get().recognize(source).stream().map(SkillCatalog.Skill::name)
            .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void loadsOnceAndExposesStableCanonicalIdsWithoutRelatedSkillExpansion() {
        var catalog = SkillCatalog.get();
        assertSame(catalog, SkillCatalog.get());
        assertTrue(catalog.size() >= 300);
        assertEquals("postgresql", catalog.resolve("Postgres").orElseThrow().id());
        assertEquals(SkillNames.normalize("Postgres"), SkillNames.normalize("PostgreSQL"));
        assertNotEquals(SkillNames.normalize("SQL"), SkillNames.normalize("PostgreSQL"));
        assertEquals(Set.of("PostgreSQL"), names("Postgres"));
        assertNotEquals(SkillNames.normalize("C"), SkillNames.normalize("C++"));
        assertNotEquals(SkillNames.normalize("C++"), SkillNames.normalize("C#"));
    }

    @Test
    void usesLongestLexicalMentionWithoutDroppingAnIndependentParentMention() {
        assertEquals(
            Set.of("React Native", "SQL Server", "Spring Boot"),
            names("React Native / SQL Server / Spring Boot")
        );
        assertEquals(Set.of("Spring", "Spring Boot"), names("Spring Boot and Spring"));
        assertEquals(Set.of("JavaScript", "GitHub"), names("JavaScript GitHub MySQLConnector"));
    }

    @Test
    void contextualRulesRequireActionAndObjectAndExposeTheirLayer() {
        var skills = JobRequirementExtractor.recognize("Gather stakeholder requirements and optimize SQL queries.");
        assertTrue(
            skills.stream()
                .anyMatch(s -> s.name().equals("Requirements elicitation") && s.recognition().equals("CONTEXTUAL"))
        );
        assertTrue(skills.stream().anyMatch(s -> s.name().equals("Query optimization")));
        assertFalse(names("Gather flowers and optimize schedules").contains("Requirements elicitation"));
        assertTrue(
            names("Kinh nghiệm thiết kế API và trực quan hóa dữ liệu")
                .containsAll(Set.of("API design", "Data visualization"))
        );
    }

    @Test
    void ambiguousShortNamesNeedContextWhileExplicitDeclarationsCanBeNormalized() {
        assertTrue(names("Go to work; R and C are letters.").isEmpty());
        assertTrue(names("R programming and embedded C programming").containsAll(Set.of("R", "C")));
        assertEquals("Go", SkillCatalog.get().canonicalName("Go"));
        assertEquals(
            List.of("Python"),
            ResumeSkillMatcher.find("No experience with Playwright. Want to learn Terraform. Python.")
        );
        assertEquals(
            List.of("Requirements elicitation"),
            ResumeSkillMatcher.find("Gathered requirements; no experience with Python")
        );
    }

    @Test
    void rejectsDuplicateIdsConflictingAliasesUnknownReferencesAndCycles() {
        var first = skill("alpha", "Alpha", List.of("shared"), List.of());
        var other = skill("beta", "Beta", List.of("shared"), List.of());
        assertThrows(
            IllegalArgumentException.class,
            () -> new SkillCatalog(List.of(document(List.of(first, first), List.of())), "1")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new SkillCatalog(List.of(document(List.of(first, other), List.of())), "1")
        );
        var bad = skill("bad", "Bad", List.of(), List.of("missing"));
        assertThrows(
            IllegalArgumentException.class,
            () -> new SkillCatalog(List.of(document(List.of(bad), List.of())), "1")
        );
        var topics = List.of(
            new SkillCatalog.Topic("a", "A", "LEARNING_TOPIC", List.of("b"), List.of("test")),
            new SkillCatalog.Topic("b", "B", "LEARNING_TOPIC", List.of("a"), List.of("test"))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new SkillCatalog(List.of(document(List.of(first), topics)), "1")
        );
    }

    @Test
    void filtersLearningTopicsToMissingSkillsAndUsesOnlyExplicitSourceEdges() {
        var topics = List.of(
            new SkillCatalog.Topic("a-topic", "A", "LEARNING_TOPIC", List.of(), List.of("test")),
            new SkillCatalog.Topic("b-topic", "B", "LEARNING_TOPIC", List.of("a-topic"), List.of("test"))
        );
        var catalog = new SkillCatalog(
            List.of(
                document(
                    List.of(
                        skill("alpha", "Alpha", List.of(), List.of("a-topic")),
                        skill("beta", "Beta", List.of(), List.of("b-topic"))
                    ),
                    topics
                )
            ),
            "1"
        );
        assertTrue(catalog.isPrerequisite("Alpha", "Beta"));
        assertFalse(catalog.isPrerequisite("Beta", "Alpha"));
        var onlyBeta = catalog.learningContext(List.of(Map.of("skill_name", "Beta")));
        assertEquals(1, onlyBeta.size());
        assertEquals("beta", onlyBeta.getFirst().get("skill_id"));
        var topic = (Map<?, ?>) ((List<?>) onlyBeta.getFirst().get("topics")).getFirst();
        assertEquals(List.of(), topic.get("prerequisites"));
        assertTrue(SkillCatalog.get().learningContext(List.of(Map.of("skill_name", "NebulaFlux"))).isEmpty());
    }

    @Test
    void reviewedRegressionLabelsCoverSeveralDomainsAndReportMeasuredCounts() {
        Map<String, Set<String>> reference = new LinkedHashMap<>();
        reference.put(
            "Java, Spring Boot, PostgreSQL and REST APIs required. Docker preferred.",
            Set.of("Java", "Spring Boot", "PostgreSQL", "REST API", "Docker")
        );
        reference.put(
            "Use Python and Pandas to clean datasets. SQL required.",
            Set.of("Python", "Pandas", "Data handling", "SQL")
        );
        reference.put(
            "Manage stakeholders and elicit requirements. Scrum and user stories required.",
            Set.of("Stakeholder management", "Requirements elicitation", "Scrum", "User stories")
        );
        reference.put(
            "Automate tests using Playwright. API testing required.",
            Set.of("Test automation", "Playwright", "API testing")
        );
        reference.put(
            "Prepare financial reports. Budgeting and accounting required.",
            Set.of("Financial reporting", "Budgeting", "Accounting")
        );
        reference.put(
            "Assess risks and conduct penetration testing. OWASP knowledge required.",
            Set.of("Risk assessment", "Penetration testing", "OWASP")
        );
        reference.put(
            "Thiết kế API và tối ưu truy vấn SQL. Thành thạo PowerBI.",
            Set.of("API design", "Query optimization", "SQL", "Power BI")
        );
        reference.put("Experience with NebulaFlux required.", Set.of());
        int truePositive = 0, falsePositive = 0, falseNegative = 0;
        for (var row : reference.entrySet()) {
            var actual = names(row.getKey());
            truePositive += (int) actual.stream().filter(row.getValue()::contains).count();
            falsePositive += (int) actual.stream().filter(n -> !row.getValue().contains(n)).count();
            falseNegative += (int) row.getValue().stream().filter(n -> !actual.contains(n)).count();
            assertEquals(row.getValue(), actual, row.getKey());
        }
        System.out.printf(
            "CATALOG_REFERENCE cases=%d TP=%d FP=%d FN=%d%n",
            reference.size(),
            truePositive,
            falsePositive,
            falseNegative
        );
    }

    @Test
    void measuresWarmedExtractionPerformanceWithoutNetworkOrAi() {
        String jd = ("<h2>Requirements</h2><ul><li>Java and PostgreSQL "
            + "required.</li><li>Design APIs and optimize queries.</li><li>Unknown "
            + "operational protocols required.</li><li>Docker or Kubernetes " + "required.</li><li>Good communication "
            + "skills.</li></ul><h2>Benefits</h2><p>Free training</p>");
        for (int index = 0; index < 20; index++)
            JobRequirementExtractor.extract("", "", jd);
        long[] samples = new long[100];
        for (int index = 0; index < samples.length; index++) {
            long start = System.nanoTime();
            var extraction = JobRequirementExtractor.extract("", "", jd);
            samples[index] = System.nanoTime() - start;
            assertEquals(5, extraction.requirements().size());
            assertTrue(extraction.reviewRequired() > 0);
        }
        Arrays.sort(samples);
        System.out.printf(
            Locale.ROOT,
            "CATALOG_BENCHMARK skills=%d jobs=%d p50_ms=%.3f p95_ms=%.3f%n",
            SkillCatalog.get().size(),
            samples.length,
            samples[50] / 1_000_000.0,
            samples[95] / 1_000_000.0
        );
    }

    @Test
    void loadsActualSyntheticImporterOutputWithoutChangingTheSharedRuntimeCatalog() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        try (var base = getClass().getResourceAsStream("/skills/catalog.json");
            var imported = getClass().getResourceAsStream("/catalog/imported-synthetic-example.json")) {
            var catalog = new SkillCatalog(
                List.of(
                    mapper.readValue(base, SkillCatalog.Document.class),
                    mapper.readValue(imported, SkillCatalog.Document.class)
                ),
                "fixture"
            );
            assertEquals(SkillCatalog.get().size() + 2, catalog.size());
            assertTrue(catalog.isPrerequisite("Example contract tests", "Example pipeline validation"));
            assertTrue(SkillCatalog.get().resolve("Example contract tests").isEmpty());
        }
    }

    @Test
    void contextualDisambiguationAvoidsEverydayWordsAndWhitespaceVariantsRetainIdentity() {
        assertTrue(names("React quickly at the spring conference about communication protocols.").isEmpty());
        assertEquals(Set.of("Spring Boot"), names("Spring   Boot"));
        assertEquals(Set.of("React"), names("React"));
    }
}
