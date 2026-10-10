package com.example.intelligent_cv_matching_ai_agent.ai;

import com.example.intelligent_cv_matching_ai_agent.repository.guest.ai.GuestTrialRepository;

import com.example.intelligent_cv_matching_ai_agent.service.guest.ai.GuestTrialService;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import com.example.intelligent_cv_matching_ai_agent.service.guest.identity.GuestAccess;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.dto.request.guest.ai.GuestBuildRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.guest.ai.GuestRecommendationRequest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuestTrialTests {

    private final SqlStore db = mock(SqlStore.class);

    private final GuestAccess guests = mock(GuestAccess.class);

    private final AiService ai = mock(AiService.class);

    private final ResumeService resumes = mock(ResumeService.class);

    private final GuestTrialService service = new GuestTrialService(new GuestTrialRepository(db), guests, ai, resumes);

    private final UUID guest = UUID.randomUUID(), demo = UUID.randomUUID(), template = UUID.randomUUID(),
        event = UUID.randomUUID(), run = UUID.randomUUID();

    private void session() {
        when(guests.id("session")).thenReturn(guest);
        when(
            db.one(
                ("SELECT * FROM resumes WHERE id=? AND is_demo=true AND user_id IS NULL " + "AND status='READY'"),
                demo
            )
        ).thenReturn(Map.of("id", demo, "structured_data", Map.of("summary", "Demo facts only")));
        when(db.json(any())).thenReturn(new SqlStore.JsonValue("{}"));
    }

    @Test
    void builderOnlyPassesTheAllowedDemoAndTemplateFactsToAi() {
        session();
        when(
            db.one(
                ("SELECT * FROM resume_templates WHERE id=? AND is_active=true AND " + "is_trial_enabled=true"),
                template
            )
        ).thenReturn(Map.of("id", template, "language", "EN"));
        when(ai.generateForGuest(eq("CV_BUILD"), anyMap(), eq(guest))).thenReturn(Map.of("summary", "Demo facts only"));
        Map<?, ?> output = (Map<?, ?>) service.build("session", new GuestBuildRequest(template, demo));
        assertEquals(Boolean.TRUE, output.get("trial"));
        verify(ai).generateForGuest(
            eq("CV_BUILD"),
            argThat(
                input -> Map.of("summary", "Demo facts only").equals(input.get("demo_resume"))
                    && !input.containsKey("structured_data") && !input.containsKey("tokens")
            ),
            eq(guest)
        );
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void recommendationPersistsOnlyTheTopThreeAndUsesTrustedFixedCredits() {
        session();
        when(resumes.canonicalText(anyMap())).thenReturn("Java developer");
        when(guests.reserve(guest, "JOB_RECOMMENDATION", 100, demo)).thenReturn(event);
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (int i = 4; i > 0; i--)
            jobs.add(
                Map.of(
                    "id",
                    new UUID(0, i),
                    "job_title",
                    "Role " + i,
                    "skills",
                    List.of(Map.of("name", i == 2 ? "Java" : "Python"))
                )
            );
        when(db.list(startsWith("SELECT j.id,j.job_title"))).thenReturn(jobs);
        when(db.insert(eq("job_matching_runs"), anyMap())).thenReturn(run);
        Map<?, ?> output = (Map<?, ?>) service.recommend("session", new GuestRecommendationRequest(demo));
        assertEquals(run, output.get("matching_run_id"));
        assertEquals("SKILL_OVERLAP", output.get("mode"));
        List<?> results = (List<?>) output.get("results");
        assertEquals(3, results.size());
        assertEquals(new UUID(0, 2), ((Map<?, ?>) results.getFirst()).get("id"));
        verify(db, times(3)).insert(eq("job_match_results"), anyMap());
        verify(guests).reserve(guest, "JOB_RECOMMENDATION", 100, demo);
        verify(guests).settle(event, 100, true);
        verifyNoInteractions(ai);
    }

    @Test
    void privateResumeIdsCannotConsumeGuestQuotaOrAccessRecommendationData() {
        session();
        when(
            db.one(
                ("SELECT * FROM resumes WHERE id=? AND is_demo=true AND user_id IS NULL " + "AND status='READY'"),
                demo
            )
        ).thenThrow(ApiException.notFound("CV not found"));
        assertThrows(ApiException.class, () -> service.recommend("session", new GuestRecommendationRequest(demo)));
        verify(guests, never()).reserve(any(), anyString(), anyInt(), any());
        verifyNoInteractions(ai, resumes);
    }
}
