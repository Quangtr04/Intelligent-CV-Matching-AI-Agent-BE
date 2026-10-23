package com.example.intelligent_cv_matching_ai_agent.admin;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.resume.ResumeTemplateRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.resume.ResumeTemplateStatusRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.resume.ResumeTemplateAdminRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.admin.monitoring.AuditService;
import com.example.intelligent_cv_matching_ai_agent.service.admin.resume.ResumeTemplateAdminService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResumeTemplateAdminServiceTest {

    private final ResumeTemplateAdminRepository db = mock(ResumeTemplateAdminRepository.class);

    private final CurrentUser user = mock(CurrentUser.class);

    private final AuditService audit = mock(AuditService.class);

    private final ResumeTemplateAdminService service = new ResumeTemplateAdminService(db, user, audit);

    private static final String HTML =
        "<!DOCTYPE html><h1 data-cv-field=\"fullName\"></h1>" + "<section data-cv-section=\"summary\"></section>";

    @Test
    void administratorIsRequiredForEveryOperationBeforeReadingOrWriting() {
        doThrow(ApiException.forbidden("Role required: ADMIN")).when(user).requireRole("ADMIN");
        UUID id = UUID.randomUUID();
        List<Executable> calls = List.of(
            () -> service.list(50, 0),
            () -> service.get(id),
            () -> service.create(request(HTML)),
            () -> service.update(id, request(HTML)),
            () -> service.status(id, new ResumeTemplateStatusRequest(false))
        );
        for (Executable call : calls) {
            assertEquals(403, assertThrows(ApiException.class, call).status());
        }
        verifyNoInteractions(db, audit);
    }

    @Test
    void creationStoresNormalizedAllowlistedValuesAndAuditsTheStoredRecord() {
        UUID id = UUID.randomUUID();
        Map<String, Object> saved = Map.of("id", id, "name", "ATS Test", "is_active", true);
        when(db.create(anyMap())).thenReturn(id);
        when(db.get(id)).thenReturn(saved);
        assertSame(saved, service.create(request(HTML)));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
        verify(db).create(values.capture());
        assertEquals("ATS Test", values.getValue().get("name"));
        assertEquals("Software Engineer", values.getValue().get("career_path"));
        assertEquals("", values.getValue().get("description"));
        assertFalse(values.getValue().containsKey("layout_config"));
        assertTrue(values.getValue().get("html_content").toString().contains("data-cv-field=\"fullName\""));
        verify(audit).record("CREATE_RESUME_TEMPLATE", "RESUME_TEMPLATE", id, null, saved);
    }

    @Test
    void fullUpdateClearsOptionalValuesAndAuditsBeforeAndAfter() {
        UUID id = UUID.randomUUID();
        Map<String, Object> before = Map.of("id", id, "name", "Old", "career_path", "Old Career");
        Map<String, Object> after = Map.of("id", id, "name", "New");
        when(db.lock(id)).thenReturn(before);
        when(db.get(id)).thenReturn(after);
        var input = new ResumeTemplateRequest(" New ", null, "vi", null, false, false, HTML);
        assertSame(after, service.update(id, input));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
        verify(db).update(eq(id), values.capture());
        assertNull(values.getValue().get("career_path"));
        assertEquals("", values.getValue().get("description"));
        assertEquals(false, values.getValue().get("is_trial_enabled"));
        assertNotNull(values.getValue().get("html_content"));
        verify(audit).record("UPDATE_RESUME_TEMPLATE", "RESUME_TEMPLATE", id, before, after);
    }

    @Test
    void htmlIsTheOnlyLayoutRepresentationAndCannotBeOmitted() {
        UUID id = UUID.randomUUID();
        when(db.create(anyMap())).thenReturn(id);
        when(db.get(id)).thenReturn(Map.of("id", id));
        String html = "<!DOCTYPE html><html><head></head><body><h1 data-cv-field=\"fullName\"></h1>"
            + "<section data-cv-section=\"summary\"></section></body></html>";
        service.create(new ResumeTemplateRequest("HTML", null, "vi", null, true, true, html));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
        verify(db).create(values.capture());
        assertTrue(values.getValue().get("html_content").toString().contains("data-cv-field=\"fullName\""));
        assertFalse(values.getValue().containsKey("layout_config"));
        assertThrows(
            ApiException.class,
            () -> service.create(new ResumeTemplateRequest("Config", null, "vi", null, true, true, "  "))
        );
    }

    @Test
    void statusChangesOnlyTheSwitchAndRepeatedRequestsAreIdempotent() {
        UUID id = UUID.randomUUID();
        Map<String, Object> before = Map.of("id", id, "is_active", true, "is_trial_enabled", true);
        Map<String, Object> after = Map.of("id", id, "is_active", false, "is_trial_enabled", true);
        when(db.lock(id)).thenReturn(before, after);
        when(db.get(id)).thenReturn(after);
        assertSame(after, service.status(id, new ResumeTemplateStatusRequest(false)));
        assertSame(after, service.status(id, new ResumeTemplateStatusRequest(false)));
        verify(db).status(id, false);
        verify(audit).record("UPDATE_RESUME_TEMPLATE_STATUS", "RESUME_TEMPLATE", id, before, after);
        verifyNoMoreInteractions(audit);
        verify(db, never()).update(any(), anyMap());
    }

    @ParameterizedTest
    @MethodSource("invalidHtml")
    void invalidHtmlCannotReachPersistence(
        String html) {
        assertEquals(400, assertThrows(ApiException.class, () -> service.create(request(html))).status());
        verifyNoInteractions(db, audit);
    }

    private static Stream<String> invalidHtml() {
        return Stream.of(null, "  ", "<p>No CV slots</p>", HTML + "<script>alert(1)</script>", "x".repeat(200001));
    }

    @Test
    void invalidMetadataAndPaginationCannotReachPersistence() {
        for (int limit : List.of(0, 101)) {
            assertThrows(ApiException.class, () -> service.list(limit, 0));
        }
        assertThrows(ApiException.class, () -> service.list(50, -1));
        assertThrows(ApiException.class, () -> service.list(50, 100001));
        assertThrows(
            ApiException.class,
            () -> service.create(new ResumeTemplateRequest(" ", null, "vi", null, true, true, HTML))
        );
        assertThrows(
            ApiException.class,
            () -> service.create(new ResumeTemplateRequest("Name", null, "EN", null, true, true, HTML))
        );
        assertThrows(
            ApiException.class,
            () -> service.create(new ResumeTemplateRequest("Bad\0Name", null, "vi", null, true, true, HTML))
        );
        assertThrows(
            ApiException.class,
            () -> service.create(new ResumeTemplateRequest("Name", null, "vi", "Bad\0Description", true, true, HTML))
        );
        assertThrows(
            ApiException.class,
            () -> service.status(UUID.randomUUID(), new ResumeTemplateStatusRequest(null))
        );
        verifyNoInteractions(db, audit);
    }

    private static ResumeTemplateRequest request(
        String html) {
        return new ResumeTemplateRequest("  ATS Test  ", " Software Engineer ", "vi", null, true, true, html);
    }
}
