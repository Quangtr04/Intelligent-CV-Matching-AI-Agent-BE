package com.example.intelligent_cv_matching_ai_agent.jobs;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.MatchingRepository;

import com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs.MatchingService;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MatchingServiceCacheTests {

    @Test
    void identicalSelectedCvContentDoesNotReuseAnotherCvRun() {
        verifyResumeProvenance("SELECTED_CV");
    }

    @Test
    void replacingAnAllCvsMemberWithIdenticalContentCreatesANewRun() {
        verifyResumeProvenance("ALL_CVS");
    }

    private void verifyResumeProvenance(
        String basis) {
        SqlStore db = mock(SqlStore.class);
        CurrentUser user = mock(CurrentUser.class);
        AiService ai = mock(AiService.class);
        ResumeService resumes = mock(ResumeService.class);
        UUID owner = UUID.randomUUID(), firstCv = UUID.randomUUID(), secondCv = UUID.randomUUID(),
            job = UUID.randomUUID();
        Map<String, Object> firstResume = Map.of("id", firstCv, "status", "READY");
        Map<String, Object> secondResume = Map.of("id", secondCv, "status", "READY");
        AtomicReference<Map<String, Object>> currentMember = new AtomicReference<>(firstResume);
        when(user.id()).thenReturn(owner);
        when(ai.model()).thenReturn("test-model");
        when(ai.embeddingModel()).thenReturn("test-embedding");
        when(resumes.owned(firstCv)).thenReturn(firstResume);
        when(resumes.owned(secondCv)).thenReturn(secondResume);
        // Separate resumes have exactly the same reviewed text, including title.
        when(resumes.canonicalText(anyMap())).thenReturn("Java CV\nJava backend developer");
        when(db.list(startsWith("SELECT * FROM resumes"), eq(owner)))
            .thenAnswer(ignored -> List.of(currentMember.get()));
        when(db.list(contains("FROM market_job_posts j WHERE j.status='ACTIVE'"))).thenAnswer(
            ignored -> List.of(
                new LinkedHashMap<>(
                    Map.of(
                        "id",
                        job,
                        "job_title",
                        "Java Developer",
                        "raw_content_hash",
                        "unchanged-job-content",
                        "skills",
                        List.of(Map.of("name", "java", "required", true))
                    )
                )
            )
        );
        when(db.scalar(contains("FROM market_job_posts WHERE status='ACTIVE'"))).thenReturn("unchanged-catalog");
        when(db.json(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Map<String, UUID> cache = new HashMap<>();
        Map<UUID, Map<String, Object>> runs = new HashMap<>();
        when(db.optional(contains("profile_snapshot->>'request_hash'"), eq(owner), anyString()))
            .thenAnswer(invocation -> {
                UUID run = cache.get(invocation.getArgument(2, String.class));
                return run == null ? Optional.empty() : Optional.of(Map.of("id", run));
            });
        when(db.insert(eq("job_matching_runs"), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> fields = invocation.getArgument(1);
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshot = (Map<String, Object>) fields.get("profile_snapshot");
            UUID run = UUID.randomUUID();
            cache.put(snapshot.get("request_hash").toString(), run);
            runs.put(run, Map.of("id", run, "user_id", owner, "basis", basis));
            return run;
        });
        when(db.one(contains("FROM job_matching_runs"), any(UUID.class), eq(owner)))
            .thenAnswer(invocation -> new LinkedHashMap<>(runs.get(invocation.getArgument(1, UUID.class))));

        MatchingService service = new MatchingService(new MatchingRepository(db), user, ai, resumes);
        List<UUID> firstSelection = basis.equals("SELECTED_CV") ? List.of(firstCv) : null;
        List<UUID> secondSelection = basis.equals("SELECTED_CV") ? List.of(secondCv) : null;
        Map<String, Object> firstRun = service.match(basis, firstSelection, 1);
        assertEquals(false, firstRun.get("cached"));
        Map<String, Object> firstRepeat = service.match(basis, firstSelection, 1);
        assertEquals(true, firstRepeat.get("cached"));
        assertEquals(firstRun.get("id"), firstRepeat.get("id"));

        currentMember.set(secondResume);
        Map<String, Object> secondRun = service.match(basis, secondSelection, 1);
        assertEquals(false, secondRun.get("cached"));
        assertNotEquals(firstRun.get("id"), secondRun.get("id"));
        Map<String, Object> secondRepeat = service.match(basis, secondSelection, 1);
        assertEquals(true, secondRepeat.get("cached"));
        assertEquals(secondRun.get("id"), secondRepeat.get("id"));
        verify(db).update(startsWith("INSERT INTO matching_run_resumes"), eq(firstRun.get("id")), eq(firstCv));
        verify(db).update(startsWith("INSERT INTO matching_run_resumes"), eq(secondRun.get("id")), eq(secondCv));
        verify(db, times(2)).insert(eq("job_matching_runs"), anyMap());
    }
}
