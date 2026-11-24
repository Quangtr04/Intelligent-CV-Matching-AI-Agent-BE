package com.example.intelligent_cv_matching_ai_agent.learning;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.RoadmapRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.AssessmentRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.LearningRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.learning.AssessmentService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.learning.LearningService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.learning.LearningReferenceService;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LearningSourceFlowTest {

    private static final Map<String, Object> RECOMMENDATIONS = Map.of(
        "provider",
        "roadmap.sh",
        "source_catalog_url",
        "https://roadmap.sh/roadmaps",
        "roadmaps",
        List.of(Map.of("slug", "java", "title", "Java", "url", "https://roadmap.sh/java"))
    );

    private static final List<Map<String, Object>> SCORES =
        List.of(Map.of("skill", "Java", "total", 2, "correct", 1, "score", 50));

    private final LearningRepository db = mock(LearningRepository.class);

    private final AssessmentRepository assessmentDb = mock(AssessmentRepository.class);

    private final CurrentUser current = mock(CurrentUser.class);

    private final AiService ai = mock(AiService.class);

    private final ResumeService resumes = mock(ResumeService.class);

    private final LearningReferenceService references = mock(LearningReferenceService.class);

    private final AssessmentService assessments =
        new AssessmentService(assessmentDb, current, ai, mock(PlatformTransactionManager.class));

    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

    private final LearningService service =
        new LearningService(db, current, ai, assessments, resumes, references, transactions);

    private final UUID actor = UUID.randomUUID();

    private final UUID assessmentId = UUID.randomUUID();

    private final UUID assessmentJobId = UUID.randomUUID();

    @BeforeEach
    void identity() {
        when(current.id()).thenReturn(actor);
    }

    @Test
    void directRecommendationsValidateTheCandidateAndReturnReferencesWithoutCallingAi() {
        List<String> skills = List.of("Java", "Spring Boot");
        when(references.recommendSkills(skills)).thenReturn(RECOMMENDATIONS);
        when(references.recommendSkills(List.of())).thenReturn(RECOMMENDATIONS);

        assertSame(RECOMMENDATIONS, service.recommendations("  Java Backend  ", skills));
        assertSame(RECOMMENDATIONS, service.recommendations("Java Backend", null));

        verify(current, times(2)).requireRole("CANDIDATE");
        verify(references).recommendSkills(skills);
        verify(references).recommendSkills(List.of());
        verifyNoInteractions(ai, db, assessmentDb, resumes);
    }

    @Test
    void invalidDirectRecommendationInputsAreRejectedBeforeReferenceWork() {
        assertBadRecommendation(null, List.of());
        assertBadRecommendation(" ", List.of());
        assertBadRecommendation("a".repeat(256), List.of());
        assertBadRecommendation("Java Backend", List.of(" "));
        assertBadRecommendation("Java Backend", Collections.singletonList(null));
        assertBadRecommendation("Java Backend", List.of("a".repeat(151)));
        assertBadRecommendation("Java Backend", Collections.nCopies(51, "Java"));

        verifyNoInteractions(references, ai, db, assessmentDb, resumes);
    }

    @Test
    void anAuthenticatedNonCandidateCannotRequestDirectRecommendations() {
        var originalContext = SecurityContextHolder.getContext();
        var adminContext = SecurityContextHolder.createEmptyContext();
        adminContext.setAuthentication(
            new UsernamePasswordAuthenticationToken(
                actor.toString(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
            )
        );
        SecurityContextHolder.setContext(adminContext);
        try {
            LearningService restricted =
                new LearningService(db, new CurrentUser(), ai, assessments, resumes, references, transactions);
            ApiException failure =
                assertThrows(ApiException.class, () -> restricted.recommendations("Java Backend", List.of("Java")));
            assertEquals(403, failure.status());
            verifyNoInteractions(references, ai, db, assessmentDb, resumes);
        } finally {
            SecurityContextHolder.setContext(originalContext);
        }
    }

    @Test
    void completedAssessmentReceivesReferencesWithoutCallingAi() {
        Map<String, Object> completed = session("completed");
        when(assessmentDb.getAssessmentSessionsById(assessmentId, actor)).thenReturn(completed);
        when(references.forAssessment(completed)).thenReturn(RECOMMENDATIONS);

        Map<String, Object> result = service.assessment(assessmentId);

        assertSame(RECOMMENDATIONS, result.get("learning_recommendations"));
        verify(assessmentDb).getAssessmentSessionsById(assessmentId, actor);
        verify(assessmentDb).listQuestionsForSession(assessmentId, true);
        verifyNoInteractions(ai);
    }

    @Test
    void inProgressAssessmentDoesNotReceiveRecommendationsOrRevealAnswers() {
        when(assessmentDb.getAssessmentSessionsById(assessmentId, actor)).thenReturn(session("in_progress"));

        Map<String, Object> result = service.assessment(assessmentId);

        assertFalse(result.containsKey("learning_recommendations"));
        verify(assessmentDb).listQuestionsForSession(assessmentId, false);
        verifyNoInteractions(references, ai);
    }

    @Test
    void repeatedSubmissionAddsReferencesWithoutRegradingOrConsumingAi() {
        Map<String, Object> completed = session("completed");
        when(assessmentDb.getAssessmentSessionsByIdForUpdate(assessmentId, actor))
            .thenReturn(session("in_progress"), completed);
        when(assessmentDb.countAssessmentQuestionsBySessionId(assessmentId)).thenReturn(0L);
        when(assessmentDb.listAssessmentQuestionsByIsCorrect(assessmentId)).thenReturn(SCORES);
        when(assessmentDb.json(SCORES)).thenReturn("encoded-scores");
        when(assessmentDb.getAssessmentSessionsById(assessmentId, actor)).thenReturn(completed);
        when(references.forAssessment(completed)).thenReturn(RECOMMENDATIONS);

        assertSame(RECOMMENDATIONS, service.submit(assessmentId).get("learning_recommendations"));
        assertSame(RECOMMENDATIONS, service.submit(assessmentId).get("learning_recommendations"));

        verify(assessmentDb).countAssessmentQuestionsBySessionId(assessmentId);
        verify(assessmentDb).updateAssessmentAnswersByQuestionId(assessmentId);
        verify(assessmentDb).updateAssessmentSessionsById("encoded-scores", assessmentId);
        verify(assessmentDb).updateRoadmapsByUserId(actor, assessmentJobId);
        verify(references, times(2)).forAssessment(completed);
        verifyNoInteractions(ai);
    }

    @Test
    void recommendationsCannotBypassAssessmentOwnership() {
        when(assessmentDb.getAssessmentSessionsById(assessmentId, actor))
            .thenThrow(new ApiException(404, "Assessment not found"));

        assertThrows(ApiException.class, () -> service.assessment(assessmentId));

        verify(assessmentDb).getAssessmentSessionsById(assessmentId, actor);
        verifyNoInteractions(references, ai);
    }

    @Test
    void foreignResumeIsRejectedBeforeReferencesOrAiAreRequested() {
        UUID resumeId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(db.getMarketJobPostsById(jobId)).thenReturn(Map.of("job_title", "Java Backend"));
        when(resumes.owned(resumeId)).thenThrow(new ApiException(404, "Resume not found"));

        assertThrows(
            ApiException.class,
            () -> service.createRoadmap(new RoadmapRequest("Java Backend", jobId, resumeId, null))
        );

        verify(current).requireRole("CANDIDATE");
        verify(resumes).owned(resumeId);
        verifyNoInteractions(references);
        verify(ai).requireRoadmapAccess();
        verifyNoMoreInteractions(ai);
        verify(db, never()).insertRoadmaps(anyMap());
    }

    @Test
    void foreignRoadmapIsRejectedBeforeItsReferencesAreResolved() {
        UUID roadmapId = UUID.randomUUID();
        when(db.getRoadmapsById(roadmapId, actor)).thenThrow(new ApiException(404, "Roadmap not found"));

        assertThrows(ApiException.class, () -> service.roadmap(roadmapId));

        verify(db).getRoadmapsById(roadmapId, actor);
        verifyNoInteractions(references, ai);
    }

    @Test
    void invalidSnapshotCannotSuppressMissingSkillsInADirectProfileRoadmap() {
        UUID jobId = UUID.randomUUID();
        when(db.getMarketJobPostsById(jobId)).thenReturn(Map.of("job_title", "Java Backend"));
        when(db.listMarketJobSkillsByMarketJobId(jobId))
            .thenReturn(List.of(Map.of("skill_name", "Java", "is_required", true)));
        Map<String, Object> invalid = new HashMap<>(session("completed"));
        invalid.put("market_job_id", jobId);
        invalid.put("skill_scores", List.of(Map.of("skill", "Java", "score", 100)));
        invalid.put("context_snapshot", Map.of("owner_id", actor.toString()));
        invalid.put("input_hash", "not-the-snapshot-hash");
        when(db.findCompletedAssessmentForJob(actor, jobId)).thenReturn(Optional.of(invalid));
        when(ai.generate(eq("ROADMAP_GENERATE"), anyMap())).thenThrow(new ApiException(429, "AI quota exhausted"));

        assertEquals(
            429,
            assertThrows(ApiException.class, () -> service.createRoadmap(new RoadmapRequest(null, jobId, null, null)))
                .status()
        );
        verify(ai).generate(
            eq("ROADMAP_GENERATE"),
            argThat(input -> ((List<?>) input.get("allowed_missing_skills")).size() == 1)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"jd", "profile"})
    void staleOptionalAssessmentScoresAreIgnoredButExplicitStaleAssessmentIsRejected(
        String changed) {
        UUID jobId = UUID.randomUUID();
        String previousJd = "Build Java backend services and validate transactions.";
        Map<String, Object> originalJob = Map.of("job_title", "Java Backend", "raw_content", previousJd);
        List<Map<String, Object>> skills = List.of(Map.of("skill_name", "Java", "is_required", true));
        when(assessmentDb.getMarketJobPostsById(jobId)).thenReturn(originalJob);
        when(assessmentDb.listMarketJobSkills(jobId)).thenReturn(skills);
        when(assessmentDb.findCandidateProfilesByUserId2(actor)).thenReturn(Optional.of(Map.of("years_experience", 1)));
        var prepared = assessments.prepareForOwner(actor, "Java Backend", jobId);
        Map<String, Object> completed = new HashMap<>(session("completed"));
        completed.put("market_job_id", jobId);
        completed.put("skill_scores", List.of(Map.of("skill", "Java", "score", 100)));
        completed.put("context_snapshot", prepared.snapshot());
        completed.put("input_hash", prepared.inputHash());
        Map<String, Object> currentJob = "jd".equals(changed)
            ? Map.of("job_title", "Java Backend", "raw_content", previousJd + " Maintain deployment rollback.")
            : originalJob;
        int experience = "profile".equals(changed) ? 2 : 1;
        when(assessmentDb.getMarketJobPostsById(jobId)).thenReturn(currentJob);
        when(assessmentDb.findCandidateProfilesByUserId2(actor))
            .thenReturn(Optional.of(Map.of("years_experience", experience)));
        when(db.getMarketJobPostsById(jobId)).thenReturn(currentJob);
        when(db.findCandidateProfilesByUserId(actor)).thenReturn(Optional.of(Map.of("years_experience", experience)));
        when(db.listMarketJobSkillsByMarketJobId(jobId)).thenReturn(skills);
        when(db.findCompletedAssessmentForJob(actor, jobId)).thenReturn(Optional.of(completed));
        when(db.getAssessmentSessionsById(assessmentId, actor)).thenReturn(completed);
        when(ai.generate(eq("ROADMAP_GENERATE"), anyMap())).thenThrow(new ApiException(429, "AI quota exhausted"));

        assertEquals(
            429,
            assertThrows(ApiException.class, () -> service.createRoadmap(new RoadmapRequest(null, jobId, null, null)))
                .status()
        );
        verify(ai).generate(eq("ROADMAP_GENERATE"), argThat(input -> {
            var gaps = (List<?>) input.get("allowed_missing_skills");
            return gaps.size() == 2
                && gaps.stream().allMatch(raw -> "MISSING_EVIDENCE".equals(((Map<?, ?>) raw).get("gap_status")));
        }));

        clearInvocations(ai);
        assertEquals(
            409,
            assertThrows(
                ApiException.class,
                () -> service.createRoadmap(new RoadmapRequest(null, jobId, null, assessmentId))
            ).status()
        );
        verify(ai).requireRoadmapAccess();
        verifyNoMoreInteractions(ai);
        verify(db, never()).insertRoadmaps(anyMap());
    }

    @Test
    void quotaRejectedGenerationCannotPersistARoadmapOrResourceLinks() {
        UUID jobId = UUID.randomUUID();
        when(db.getMarketJobPostsById(jobId)).thenReturn(Map.of("job_title", "Java Backend"));
        when(db.listMarketJobSkillsByMarketJobId(jobId))
            .thenReturn(List.of(Map.of("skill_name", "Spring Boot", "is_required", true)));
        when(references.recommend(eq("Java Backend"), anyCollection())).thenReturn(RECOMMENDATIONS);
        when(ai.generate(eq("ROADMAP_GENERATE"), anyMap())).thenThrow(new ApiException(429, "AI quota exhausted"));

        ApiException failure = assertThrows(
            ApiException.class,
            () -> service.createRoadmap(new RoadmapRequest("Java Backend", jobId, null, null))
        );

        assertEquals(429, failure.status());
        verify(current).requireRole("CANDIDATE");
        verify(db, never()).insertGapAnalyses(anyMap());
        verify(db, never()).insertRoadmaps(anyMap());
        verify(db, never()).insertRoadmapNodes(anyMap());
        verify(db, never()).updateRoadmapNodeResources(any(), any(), anyInt(), any(), anyMap());
    }

    @Test
    void aJobThatExpiresDuringInferenceCannotPublishARoadmap() {
        UUID jobId = UUID.randomUUID();
        when(db.getMarketJobPostsById(jobId)).thenReturn(Map.of("job_title", "Java Backend"))
            .thenThrow(ApiException.notFound("Job no longer available"));
        when(db.listMarketJobSkillsByMarketJobId(jobId))
            .thenReturn(List.of(Map.of("skill_name", "Spring Boot", "is_required", true)));
        when(ai.generate(eq("ROADMAP_GENERATE"), anyMap())).thenReturn(
            Map.of(
                "title",
                "Practice Spring Boot",
                "skill_gaps",
                List.of(Map.of("skill_name", "Spring Boot", "gap_status", "MISSING_EVIDENCE", "urgency_score", 80)),
                "nodes",
                List.of(Map.of("skill_name", "Spring Boot", "estimated_hours", 3, "prerequisites", List.of()))
            )
        );

        ApiException failure = assertThrows(
            ApiException.class,
            () -> service.createRoadmap(new RoadmapRequest("Java Backend", jobId, null, null))
        );

        assertEquals(404, failure.status());
        verify(ai, times(1)).generate(eq("ROADMAP_GENERATE"), anyMap());
        verify(db, never()).insertGapAnalyses(anyMap());
        verify(db, never()).insertRoadmaps(anyMap());
    }

    @Test
    void roadmapGenerationSnapshotsTrustedCourseLinksAndIgnoresInventedResources() {
        UUID gapId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(db.getMarketJobPostsById(jobId)).thenReturn(Map.of("job_title", "Java Backend"));
        when(db.listMarketJobSkillsByMarketJobId(jobId))
            .thenReturn(List.of(Map.of("skill_name", "Spring Boot", "is_required", true)));
        UUID roadmapId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        UUID firstResource = UUID.randomUUID();
        UUID secondResource = UUID.randomUUID();
        UUID inventedResource = UUID.randomUUID();
        when(db.findCandidateProfilesByUserId(actor)).thenReturn(Optional.empty());
        when(db.findAssessmentSessionsByUserId(actor, "Java Backend")).thenReturn(Optional.empty());
        when(references.recommend(eq("Java Backend"), anyCollection())).thenReturn(RECOMMENDATIONS);
        when(references.resourcesForSkill("Spring Boot")).thenReturn(
            List.of(
                Map.of(
                    "id",
                    firstResource,
                    "title",
                    "Spring video",
                    "url",
                    "https://www.youtube.com/watch?v=course123"
                ),
                Map.of(
                    "id",
                    secondResource,
                    "title",
                    "Spring Boot course",
                    "url",
                    "https://www.coursera.org/learn/spring-boot"
                )
            )
        );
        when(ai.generate(eq("ROADMAP_GENERATE"), anyMap())).thenReturn(
            Map.of(
                "title",
                "Java Backend",
                "skill_gaps",
                List.of(Map.of("skill_name", "Spring Boot", "gap_status", "MISSING_EVIDENCE", "urgency_score", 80)),
                "nodes",
                List.of(
                    Map.of(
                        "skill_name",
                        "Spring Boot",
                        "estimated_hours",
                        10,
                        "prerequisites",
                        List.of(),
                        "resources",
                        List.of(Map.of("id", inventedResource, "url", "https://invented.example.invalid/course"))
                    )
                )
            )
        );
        when(db.readGapAnalysesByUserId(actor, jobId)).thenReturn(1);
        when(db.insertGapAnalyses(anyMap())).thenReturn(gapId);
        when(db.insertRoadmaps(anyMap())).thenReturn(roadmapId);
        when(db.json(anyMap())).thenReturn("saved-recommendations");
        when(db.insertRoadmapNodes(anyMap())).thenReturn(nodeId);
        when(db.getRoadmapsById(roadmapId, actor)).thenReturn(
            new HashMap<>(
                Map.of(
                    "id",
                    roadmapId,
                    "user_id",
                    actor,
                    "gap_analysis_id",
                    gapId,
                    "target_job_title",
                    "Java Backend",
                    "learning_recommendations_snapshot",
                    RECOMMENDATIONS
                )
            )
        );
        when(db.listRoadmapNodesByRoadmapId(roadmapId))
            .thenReturn(List.of(new HashMap<>(Map.of("id", nodeId, "skill_name", "Spring Boot"))));

        Map<String, Object> result = service.createRoadmap(new RoadmapRequest("Java Backend", jobId, null, null));

        verify(current).requireRole("CANDIDATE");
        verify(ai).generate(
            eq("ROADMAP_GENERATE"),
            argThat(
                input -> "MISSING_SKILLS_ONLY".equals(input.get("learning_scope"))
                    && ((List<?>) input.get("allowed_missing_skills")).size() == 1
            )
        );
        verify(db).insertRoadmaps(
            argThat(fields -> "saved-recommendations".equals(fields.get("learning_recommendations_snapshot")))
        );
        verify(db).updateRoadmapNodeResources(
            eq(nodeId),
            eq(firstResource),
            eq(1),
            anyString(),
            argThat(resource -> "https://www.youtube.com/watch?v=course123".equals(resource.get("url")))
        );
        verify(db).updateRoadmapNodeResources(
            eq(nodeId),
            eq(secondResource),
            eq(2),
            anyString(),
            argThat(resource -> "https://www.coursera.org/learn/spring-boot".equals(resource.get("url")))
        );
        verify(db, never()).updateRoadmapNodeResources(eq(nodeId), eq(inventedResource), anyInt(), any(), anyMap());
        verify(references, never()).recommend(anyString(), anyCollection());
        assertSame(RECOMMENDATIONS, result.get("learning_recommendations"));
        assertFalse(result.containsKey("learning_recommendations_snapshot"));
    }

    @Test
    void savedRoadmapRecommendationsAreReadWithoutConsultingTheLiveCatalog() {
        UUID roadmapId = UUID.randomUUID();
        UUID gapId = UUID.randomUUID();
        when(db.getRoadmapsById(roadmapId, actor)).thenAnswer(
            invocation -> new HashMap<>(
                Map.of(
                    "id",
                    roadmapId,
                    "target_job_title",
                    "Java Backend",
                    "gap_analysis_id",
                    gapId,
                    "learning_recommendations_snapshot",
                    RECOMMENDATIONS
                )
            )
        );

        assertSame(RECOMMENDATIONS, service.roadmap(roadmapId).get("learning_recommendations"));
        assertSame(RECOMMENDATIONS, service.roadmap(roadmapId).get("learning_recommendations"));

        verifyNoInteractions(references, ai);
    }

    @Test
    void legacyRoadmapsWithoutARecommendationSnapshotKeepTheExistingFallback() {
        UUID roadmapId = UUID.randomUUID();
        when(db.getRoadmapsById(roadmapId, actor)).thenReturn(
            new HashMap<>(
                Map.of("id", roadmapId, "target_job_title", "Java Backend", "gap_analysis_id", UUID.randomUUID())
            )
        );
        when(references.recommend("Java Backend", List.of())).thenReturn(RECOMMENDATIONS);

        assertSame(RECOMMENDATIONS, service.roadmap(roadmapId).get("learning_recommendations"));

        verify(references).recommend("Java Backend", List.of());
    }

    private Map<String, Object> session(
        String status) {
        return new HashMap<>(
            Map.of(
                "id",
                assessmentId,
                "user_id",
                actor,
                "target_job_title",
                "Java Backend",
                "market_job_id",
                assessmentJobId,
                "status",
                status,
                "skill_scores",
                SCORES
            )
        );
    }

    private void assertBadRecommendation(
        String target,
        List<String> skills) {
        assertEquals(400, assertThrows(ApiException.class, () -> service.recommendations(target, skills)).status());
    }
}
