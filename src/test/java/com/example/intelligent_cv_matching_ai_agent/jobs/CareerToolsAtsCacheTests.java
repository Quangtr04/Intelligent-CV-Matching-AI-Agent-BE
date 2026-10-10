package com.example.intelligent_cv_matching_ai_agent.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs.CvRequest;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.jobs.CareerToolsRepository;

import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;

import com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs.MatchingService;

import com.example.intelligent_cv_matching_ai_agent.service.candidate.jobs.CareerToolsService;

import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;

import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class CareerToolsAtsCacheTests {

    private final SqlStore db = mock(SqlStore.class);

    private final CurrentUser user = mock(CurrentUser.class);

    private final ResumeService resumes = mock(ResumeService.class);

    private final AiService ai = mock(AiService.class);

    private final ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);

    private final StringRedisTemplate client = mock(StringRedisTemplate.class);

    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private final CareerToolsService service =
        new CareerToolsService(new CareerToolsRepository(db), user, mock(MatchingService.class), resumes, ai, redis);

    private final UUID actor = UUID.randomUUID(), firstCv = UUID.randomUUID(), secondCv = UUID.randomUUID(),
        job = UUID.randomUUID();

    private final String canonical = "Identical reviewed Java developer CV";

    private String inputHash;

    @BeforeEach
    void configure() {
        when(user.id()).thenReturn(actor);
        when(resumes.owned(firstCv))
            .thenReturn(Map.of("id", firstCv, "status", "READY", "structured_data", Map.of("summary", canonical)));
        when(resumes.owned(secondCv))
            .thenReturn(Map.of("id", secondCv, "status", "READY", "structured_data", Map.of("summary", canonical)));
        when(resumes.canonicalText(anyMap())).thenReturn(canonical);
        when(db.one("SELECT * FROM market_job_posts WHERE id=? AND status='ACTIVE'", job))
            .thenReturn(Map.of("id", job, "raw_content_hash", "job-content-hash"));
        when(ai.provider()).thenReturn("openai");
        when(ai.model()).thenReturn("test-model");
        when(ai.generate(eq("JOB_ATS"), anyMap())).thenReturn(
            Map.of(
                "ats_score",
                80,
                "strengths",
                List.of("Java"),
                "missing_skills",
                List.of(),
                "improvement_suggestions",
                List.of("Describe the project")
            )
        );
        when(db.json(any())).thenAnswer(call -> call.getArgument(0));
        when(redis.getIfAvailable()).thenReturn(client);
        when(client.opsForValue()).thenReturn(values);
        inputHash = FlowSupport.hash(canonical + "\njob-content-hash\nopenai\ntest-model");
    }

    @Test
    void identicalCvContentsWithDifferentIdsUseSeparateRedisKeys() {
        UUID firstAnalysis = UUID.randomUUID(), secondAnalysis = UUID.randomUUID();
        when(db.insert(eq("job_resume_analyses"), anyMap())).thenReturn(firstAnalysis, secondAnalysis);
        when(db.one("SELECT * FROM job_resume_analyses WHERE id=?", firstAnalysis))
            .thenReturn(analysis(firstAnalysis, firstCv, job));
        when(db.one("SELECT * FROM job_resume_analyses WHERE id=?", secondAnalysis))
            .thenReturn(analysis(secondAnalysis, secondCv, job));

        assertEquals(firstCv, service.ats(job, new CvRequest(firstCv)).get("resume_id"));
        assertEquals(secondCv, service.ats(job, new CvRequest(secondCv)).get("resume_id"));

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(values, times(2)).get(keys.capture());
        assertNotEquals(
            keys.getAllValues().get(0),
            keys.getAllValues().get(1),
            ("Redis identity must include the selected CV even when its reviewed " + "contents are identical")
        );
        verify(values)
            .set(eq(keys.getAllValues().get(0)), eq(firstAnalysis.toString()), eq(java.time.Duration.ofHours(24)));
        verify(values)
            .set(eq(keys.getAllValues().get(1)), eq(secondAnalysis.toString()), eq(java.time.Duration.ofHours(24)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aRedisAnalysisForAnotherCvOrJobCannotBeReturned(
        boolean anotherCv) {
        UUID staleId = UUID.randomUUID(), freshId = UUID.randomUUID();
        Map<String, Object> stale =
            analysis(staleId, anotherCv ? secondCv : firstCv, anotherCv ? job : UUID.randomUUID());
        when(values.get(anyString())).thenReturn(staleId.toString());
        // The old lookup by only ID and owner would expose this wrong CV/job result.
        // The constrained lookup must miss even though owner and input hash are
        // identical.
        when(db.optional(startsWith("SELECT * FROM job_resume_analyses WHERE id=?"), eq(staleId), eq(actor)))
            .thenReturn(Optional.of(stale));
        when(db.insert(eq("job_resume_analyses"), anyMap())).thenReturn(freshId);
        when(db.one("SELECT * FROM job_resume_analyses WHERE id=?", freshId))
            .thenReturn(analysis(freshId, firstCv, job));

        Map<String, Object> result = service.ats(job, new CvRequest(firstCv));

        assertEquals(freshId, result.get("id"));
        assertEquals(firstCv, result.get("resume_id"));
        assertEquals(job, result.get("market_job_id"));
        assertEquals(false, result.get("cached"));
        verify(ai).generate(eq("JOB_ATS"), anyMap());
        verify(db).optional(
            argThat(
                sql -> sql.contains("id=?") && sql.contains("user_id=?") && sql.contains("resume_id=?")
                    && sql.contains("market_job_id=?") && sql.contains("input_hash")
            ),
            eq(staleId),
            eq(actor),
            eq(firstCv),
            eq(job),
            eq(inputHash)
        );
    }

    private Map<String, Object> analysis(
        UUID id,
        UUID cv,
        UUID targetJob) {
        return new LinkedHashMap<>(
            Map.of(
                "id",
                id,
                "user_id",
                actor,
                "resume_id",
                cv,
                "market_job_id",
                targetJob,
                "ats_score",
                80,
                "improvement_suggestions_json",
                Map.of("input_hash", inputHash, "items", List.of("Describe the project"))
            )
        );
    }
}
