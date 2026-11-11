package com.example.intelligent_cv_matching_ai_agent.learning;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.RoadmapNodeStatusRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.RoadmapRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.LearningRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.learning.AssessmentService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.learning.LearningService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.learning.LearningReferenceService;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LearningProgressScenarioTests {

    private final LearningRepository db = mock(LearningRepository.class);

    private final CurrentUser user = mock(CurrentUser.class);

    private final AiService ai = mock(AiService.class);

    private final ResumeService resumes = mock(ResumeService.class);

    private final LearningReferenceService references = mock(LearningReferenceService.class);

    private final AssessmentService assessments = mock(AssessmentService.class);

    private final LearningService service =
        new LearningService(db, user, ai, assessments, resumes, references, mock(PlatformTransactionManager.class));

    private final UUID owner = UUID.randomUUID();

    private final UUID roadmap = UUID.randomUUID();

    private final UUID node = UUID.randomUUID();

    @BeforeEach
    void identity() {
        when(user.id()).thenReturn(owner);
    }

    @Test
    void foreignRoadmapStopsBeforeLoadingOrChangingAnyNode() {
        when(db.getRoadmapsByIdForUpdate(roadmap, owner)).thenThrow(ApiException.notFound("Roadmap not found"));

        ApiException failure = assertThrows(
            ApiException.class,
            () -> service.node(roadmap, node, new RoadmapNodeStatusRequest("completed"))
        );

        assertEquals(404, failure.status());
        verify(db, never()).getRoadmapNodesById(any(), any());
        verify(db, never()).updateRoadmapNodesById2(anyString(), any(), any());
        verify(db, never()).updateRoadmapsByStatus(any(), any(), any());
        verifyNoInteractions(ai, references, assessments, resumes);
    }

    @Test
    void nodeFromAnotherRoadmapCannotBeChangedEvenWhenTheRoadmapIsOwned() {
        when(db.getRoadmapNodesById(node, roadmap)).thenThrow(ApiException.notFound("Node not found"));

        assertEquals(
            404,
            assertThrows(
                ApiException.class,
                () -> service.node(roadmap, node, new RoadmapNodeStatusRequest("in_progress"))
            ).status()
        );

        verify(db).getRoadmapsByIdForUpdate(roadmap, owner);
        verify(db, never()).countRoadmapNodePrerequisitesByNodeId(any());
        verify(db, never()).updateRoadmapNodesById2(anyString(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"in_progress", "completed"})
    void unfinishedPrerequisiteRejectsStartingOrCompletingTheNodeWithoutWrites(
        String status) {
        when(db.countRoadmapNodePrerequisitesByNodeId(node)).thenReturn(1L);

        ApiException failure =
            assertThrows(ApiException.class, () -> service.node(roadmap, node, new RoadmapNodeStatusRequest(status)));

        assertEquals(409, failure.status());
        verify(db, never()).updateRoadmapNodesById2(anyString(), any(), any());
        verify(db, never()).updateRoadmapsByStatus(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not_started", "in_progress"})
    void activeDependentPreventsRegressingItsPrerequisite(
        String status) {
        when(db.countRoadmapNodePrerequisitesByNodeId(node)).thenReturn(0L);
        when(db.countRoadmapNodePrerequisitesByPrerequisiteNodeId(node)).thenReturn(1L);

        assertEquals(
            409,
            assertThrows(ApiException.class, () -> service.node(roadmap, node, new RoadmapNodeStatusRequest(status)))
                .status()
        );

        verify(db, never()).updateRoadmapNodesById2(anyString(), any(), any());
        verify(db, never()).updateRoadmapsByStatus(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not_started", "in_progress", "completed"})
    void eligibleProgressUsesTheRightCompletionTimestampAndRecalculatesTheRoadmap(
        String status) {
        when(db.countRoadmapNodePrerequisitesByNodeId(node)).thenReturn(0L);
        when(db.countRoadmapNodePrerequisitesByPrerequisiteNodeId(node)).thenReturn(0L);
        when(db.getRoadmapsById(roadmap, owner)).thenReturn(new HashMap<>(Map.of("id", roadmap)));

        Map<String, Object> response = service.node(roadmap, node, new RoadmapNodeStatusRequest(status));

        var completion = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        var order = inOrder(db);
        order.verify(db).getRoadmapsByIdForUpdate(roadmap, owner);
        order.verify(db).getRoadmapNodesById(node, roadmap);
        order.verify(db).updateRoadmapNodesById2(eq(status), completion.capture(), eq(node));
        order.verify(db).updateRoadmapsByStatus(roadmap, roadmap, roadmap);
        order.verify(db).getRoadmapsById(roadmap, owner);
        assertEquals(roadmap, response.get("id"));
        assertEquals("completed".equals(status), completion.getValue() != null);
        verifyNoInteractions(ai, assessments, resumes);
    }

    @Test
    void completingAnEligiblePrerequisiteDoesNotRequireResettingItsDependents() {
        when(db.countRoadmapNodePrerequisitesByNodeId(node)).thenReturn(0L);
        when(db.getRoadmapsById(roadmap, owner)).thenReturn(new HashMap<>(Map.of("id", roadmap)));

        service.node(roadmap, node, new RoadmapNodeStatusRequest("completed"));

        verify(db, never()).countRoadmapNodePrerequisitesByPrerequisiteNodeId(any());
        verify(db).updateRoadmapNodesById2(eq("completed"), notNull(), eq(node));
    }

    @Test
    void fullyEvidencedProfileCreatesACompletedEmptyRoadmapWithoutAnAiCallOrAssessment() {
        UUID job = UUID.randomUUID();
        UUID gap = UUID.randomUUID();
        when(db.json(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(db.getMarketJobPostsById(job)).thenReturn(Map.of("job_title", "Java Developer"));
        when(db.listMarketJobSkillsByMarketJobId(job))
            .thenReturn(List.of(Map.of("skill_name", "Java", "is_required", true)));
        when(db.listCandidateProfileSkillsByUserId(owner)).thenReturn(List.of(Map.of("name", "Java")));
        when(db.readGapAnalysesByUserId(owner, job)).thenReturn(1);
        when(db.insertGapAnalyses(anyMap())).thenReturn(gap);
        Map<String, Object> saved = new HashMap<>();
        when(db.insertRoadmaps(anyMap())).thenAnswer(invocation -> {
            saved.putAll(invocation.getArgument(0));
            saved.put("id", roadmap);
            return roadmap;
        });
        when(db.getRoadmapsById(roadmap, owner)).thenAnswer(invocation -> new HashMap<>(saved));

        Map<String, Object> result = service.createRoadmap(new RoadmapRequest(null, job, null, null));
        assertEquals(roadmap, result.get("id"));
        assertEquals("completed", result.get("status"));
        assertEquals(100, result.get("progress_percent"));
        assertEquals(List.of(), result.get("nodes"));

        verify(user).requireRole("CANDIDATE");
        verify(db).insertRoadmaps(
            argThat(
                fields -> "completed".equals(fields.get("status"))
                    && Integer.valueOf(100).equals(fields.get("progress_percent"))
                    && Integer.valueOf(0).equals(fields.get("estimated_total_hours"))
            )
        );
        verify(db, never()).insertRoadmapNodes(anyMap());
        verifyNoInteractions(ai, assessments, resumes, references);
    }
}
