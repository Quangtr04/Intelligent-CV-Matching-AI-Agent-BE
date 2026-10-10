package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.resume.ResumeRepository;

import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeFileExtractor;

import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeData;

import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;

import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;

import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResumeOwnershipTests {

    private final SqlStore db = mock(SqlStore.class);

    private final CurrentUser current = new CurrentUser();

    private final UUID actor = UUID.randomUUID(), id = UUID.randomUUID();

    private final ResumeService service = new ResumeService(
        new ResumeRepository(db),
        current,
        mock(AiService.class),
        mock(ResumeFileExtractor.class),
        JsonMapper.builder().build(),
        "./target/test-storage"
    );

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext()
            .setAuthentication(new UsernamePasswordAuthenticationToken(actor.toString(), null, List.of()));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsAnotherUsersCvBeforeArchiving() {
        when(db.one("SELECT * FROM resumes WHERE id=?", id)).thenReturn(Map.of("id", id, "user_id", UUID.randomUUID()));
        assertThrows(ApiException.class, () -> service.archive(id));
        verify(db, never()).update(anyString(), any());
    }

    @Test
    void rejectsDemoCvThroughPrivateOwnershipApi() {
        Map<String, Object> demo = new HashMap<>();
        demo.put("id", id);
        demo.put("user_id", null);
        when(db.one("SELECT * FROM resumes WHERE id=?", id)).thenReturn(demo);
        assertThrows(ApiException.class, () -> service.owned(id));
    }

    @Test
    void reviewedContentOverridesContradictoryRawExtraction() {
        String text = service.canonicalText(
            Map.of(
                "title",
                "Reviewed CV",
                "raw_content",
                "incorrect employer",
                "structured_data",
                Map.of("summary", "Correct employer", "skills", List.of("Java"))
            )
        );
        assertTrue(text.contains("Correct employer"));
        assertFalse(text.contains("incorrect employer"));
    }

    @Test
    void validatesResumeDatesAndCurrentEmployment() {
        assertThrows(
            ApiException.class,
            () -> ResumeData
                .normalize(Map.of("experience", List.of(Map.of("start_date", "2025-01-01", "end_date", "2024-01-01"))))
        );
        assertThrows(
            ApiException.class,
            () -> ResumeData
                .normalize(Map.of("experience", List.of(Map.of("is_current", true, "end_date", "2025-01-01"))))
        );
    }
}
