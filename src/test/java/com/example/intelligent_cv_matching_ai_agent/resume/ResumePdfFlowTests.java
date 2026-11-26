package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.controller.candidate.resume.ResumeController;
import com.example.intelligent_cv_matching_ai_agent.dto.response.candidate.resume.ResumeDownloadResponse;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiExceptionHandler;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumePdfExportService;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.CandidateAiTaskGate;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP controller contracts with mocked services; no database, renderer or provider is invoked. */
class ResumePdfFlowTests {

    private final ResumeService resumes = mock(ResumeService.class);

    private final ResumePdfExportService pdf = mock(ResumePdfExportService.class);

    private final UUID id = UUID.randomUUID();

    private final UUID template = UUID.randomUUID();

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ResumeController(mock(CandidateAiTaskGate.class), resumes, pdf))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void manualCreationKeepsTheDraftWithoutGeneratingPdf() throws Exception {
        when(resumes.create(anyMap(), eq(false))).thenReturn(Map.of("id", id, "status", "DRAFT"));

        mvc.perform(
            post("/api/v1/resumes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Draft Java CV\",\"language\":\"en\"}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));

        verify(resumes).create(argThat(input -> "Draft Java CV".equals(input.get("title"))), eq(false));
        verifyNoInteractions(pdf);
    }

    @Test
    void aiBuilderKeepsTheReviewRequiredStateWithoutPrematurePdfPublication() throws Exception {
        when(resumes.create(anyMap(), eq(true))).thenReturn(Map.of("id", id, "status", "REVIEW_REQUIRED"));

        mvc.perform(
            post("/api/v1/resumes/build").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Review Java CV\"}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"));

        verify(resumes).create(anyMap(), eq(true));
        verifyNoInteractions(pdf);
    }

    @Test
    void confirmingReturnsTheCompleteResumeOnlyAfterPdfPublication() throws Exception {
        when(resumes.confirm(id, Map.of())).thenReturn(Map.of("id", id, "status", "READY"));
        when(pdf.export(id)).thenReturn(Map.of("id", id, "file_url", "published.pdf"));
        when(resumes.get(id)).thenReturn(
            Map.of(
                "id",
                id,
                "status",
                "READY",
                "file_url",
                "published.pdf",
                "mime_type",
                "application/pdf",
                "experiences",
                List.of(),
                "parse_results",
                List.of()
            )
        );

        mvc.perform(post("/api/v1/resumes/{id}/confirm", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("READY")).andExpect(jsonPath("$.file_url").value("published.pdf"))
            .andExpect(jsonPath("$.experiences").isArray()).andExpect(jsonPath("$.parse_results").isArray());

        var order = inOrder(resumes, pdf);
        order.verify(resumes).confirm(id, Map.of());
        order.verify(pdf).export(id);
        order.verify(resumes).get(id);
    }

    @Test
    void confirmationWithReviewedBodyPassesTheSameFieldsBeforePdfPublication() throws Exception {
        when(resumes.confirm(eq(id), anyMap())).thenReturn(Map.of("id", id, "status", "READY"));
        when(resumes.get(id)).thenReturn(Map.of("id", id, "status", "READY", "file_url", "reviewed.pdf"));

        mvc.perform(
            post("/api/v1/resumes/{id}/confirm", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"structured_data\":{\"summary\":\"Reviewed Java experience\"},\"is_primary\":true}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.file_url").value("reviewed.pdf"));

        verify(resumes).confirm(
            eq(id),
            argThat(
                input -> Boolean.TRUE.equals(input.get("is_primary"))
                    && Map.of("summary", "Reviewed Java experience").equals(input.get("structured_data"))
            )
        );
        verify(pdf).export(id);
    }

    @Test
    void pdfFailureAfterConfirmationReturnsAnErrorInsteadOfAFalseSuccess() throws Exception {
        when(resumes.confirm(id, Map.of())).thenReturn(Map.of("id", id, "status", "READY"));
        when(pdf.export(id)).thenThrow(new ApiException(503, "CV PDF storage is unavailable"));

        mvc.perform(post("/api/v1/resumes/{id}/confirm", id)).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.detail").value("CV PDF storage is unavailable"));

        var order = inOrder(resumes, pdf);
        order.verify(resumes).confirm(id, Map.of());
        order.verify(pdf).export(id);
        verify(resumes, never()).get(any());
    }

    @Test
    void rejectedConfirmationCannotGenerateOrRevealAnyPdf() throws Exception {
        when(resumes.confirm(id, Map.of())).thenThrow(ApiException.notFound("CV not found"));

        mvc.perform(post("/api/v1/resumes/{id}/confirm", id)).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("CV not found"));

        verifyNoInteractions(pdf);
        verify(resumes, never()).get(any());
    }

    @Test
    void readyTemplateOnlyPatchPublishesPdfThenReloadsTheResume() throws Exception {
        when(resumes.update(eq(id), anyMap())).thenReturn(Map.of("id", id, "status", "READY"));
        when(resumes.get(id)).thenReturn(Map.of("id", id, "status", "READY", "file_url", "new-template.pdf"));

        mvc.perform(
            patch("/api/v1/resumes/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"template_id\":\"" + template + "\"}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.file_url").value("new-template.pdf"));

        var order = inOrder(resumes, pdf);
        order.verify(resumes).update(id, Map.of("template_id", template.toString()));
        order.verify(pdf).export(id);
        order.verify(resumes).get(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "REVIEW_REQUIRED"})
    void unfinishedUpdateNeverPublishesOrReloadsAPdf(
        String state)
        throws Exception {
        when(resumes.update(eq(id), anyMap())).thenReturn(Map.of("id", id, "status", state));

        mvc.perform(
            patch("/api/v1/resumes/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"structured_data\":{\"summary\":\"Changed content awaiting review\"}}")
        ).andExpect(status().isOk()).andExpect(jsonPath("$.status").value(state));

        verifyNoInteractions(pdf);
        verify(resumes, never()).get(any());
    }

    @Test
    void defaultDownloadReturnsPublishedPdfWithUtf8FilenameAndPrivateCachePolicy() throws Exception {
        byte[] bytes = "%PDF-1.7\nsynthetic fixture".getBytes(StandardCharsets.UTF_8);
        String filename = "Nguyễn An CV.pdf";
        when(pdf.download(id)).thenReturn(new ResumeDownloadResponse(bytes, filename, "application/pdf"));

        var response = mvc.perform(get("/api/v1/resumes/{id}/download", id)).andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF)).andExpect(content().bytes(bytes))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
            .andExpect(header().string("X-Content-Type-Options", "nosniff")).andReturn().getResponse();

        String disposition = response.getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(disposition);
        assertEquals(filename, ContentDisposition.parse(disposition).getFilename());
        assertEquals("attachment", ContentDisposition.parse(disposition).getType());
        verify(pdf).download(id);
        verifyNoInteractions(resumes);
    }

    @Test
    void explicitTextDownloadPreservesTheExistingTextRouteWithoutCallingPdf() throws Exception {
        byte[] bytes = "Reviewed CV facts".getBytes(StandardCharsets.UTF_8);
        when(resumes.download(id, true))
            .thenReturn(new ResumeDownloadResponse(bytes, "CV.txt", "text/plain; charset=UTF-8"));

        mvc.perform(get("/api/v1/resumes/{id}/download", id).queryParam("text", "true")).andExpect(status().isOk())
            .andExpect(content().contentType("text/plain; charset=UTF-8")).andExpect(content().bytes(bytes))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));

        verify(resumes).download(id, true);
        verifyNoInteractions(pdf);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 409, 503})
    void failedPdfDownloadCannotFallBackToOldSourceBytesOrText(
        int error)
        throws Exception {
        when(pdf.download(id)).thenThrow(new ApiException(error, "PDF cannot be downloaded"));

        mvc.perform(get("/api/v1/resumes/{id}/download", id)).andExpect(status().is(error))
            .andExpect(jsonPath("$.detail").value("PDF cannot be downloaded"));

        verify(pdf).download(id);
        verifyNoInteractions(resumes);
    }
}
