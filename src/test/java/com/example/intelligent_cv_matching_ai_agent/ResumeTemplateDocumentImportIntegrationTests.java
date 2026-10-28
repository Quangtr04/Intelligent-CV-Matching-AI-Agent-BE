package com.example.intelligent_cv_matching_ai_agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import java.io.ByteArrayOutputStream;
import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
        "APP_ENV_FILE=src/test/resources/test.env",
        "app.security.jwt-secret=${random.uuid}${random.uuid}",
        "spring.docker.compose.enabled=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "app.ai.enabled=false",
        "app.jobs.collection.enabled=false",
        "app.jobs.expiration.enabled=false",
        "app.jobs.embedding.enabled=false",
        "app.billing.maintenance-enabled=false",
        "app.mail.enabled=false",
        "app.ocr.enabled=false"}
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class ResumeTemplateDocumentImportIntegrationTests {

    private static final String BASE = "/api/v1/admin/resume-templates";

    private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SqlStore db;

    @Autowired
    private TokenService tokens;

    @Autowired
    private JsonMapper mapper;

    private String admin;

    private String candidate;

    @BeforeEach
    void identities() {
        admin = token("ADMIN");
        candidate = token("CANDIDATE");
    }

    @Test
    void authorizesBeforeParsingAnInvalidUploadedDocument() throws Exception {
        byte[] invalid = "not a PDF".getBytes(StandardCharsets.UTF_8);
        Map<String, Long> before = counts();
        mvc.perform(multipart(BASE + "/import").file(file("bad.pdf", "application/pdf", invalid)))
            .andExpect(status().isUnauthorized());
        mvc.perform(
            multipart(BASE + "/import").file(file("bad.pdf", "application/pdf", invalid))
                .header("Authorization", candidate)
        ).andExpect(status().isForbidden());
        assertEquals(before, counts());
    }

    @Test
    void realDocxCreatesAnEditableDraftWithoutSavingTemplatesAuditOrAiUsage() throws Exception {
        Map<String, Long> before = counts();
        Map<String, Object> draft = response(
            mvc.perform(
                multipart(BASE + "/import").file(file("sample.docx", DOCX_MIME, docx())).param("language", "vi")
                    .header("Authorization", admin)
            ).andExpect(status().isOk()).andExpect(jsonPath("$.requires_review").value(true))
                .andExpect(jsonPath("$.source_format").value("DOCX"))
                .andExpect(jsonPath("$.blocks[0].id").value("import-block-1")).andReturn()
        );
        String html = draft.get("html_content").toString();
        var document = Jsoup.parse(html);
        assertTrue(document.body().text().contains("<script>alert('source')</script>"));
        assertTrue(document.select("script,img,a,[data-cv-field],[data-cv-section]").isEmpty());
        assertTrue(document.select("[id^=import-block-]").size() >= 2);
        assertFalse(((List<?>) draft.get("warnings")).isEmpty());
        assertEquals("vi", document.selectFirst("html").attr("lang"));
        assertEquals(before, counts());
        mvc.perform(
            post(BASE).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(templateBody(html, true)))
        ).andExpect(status().isBadRequest());
        assertEquals(before, counts(), "An unmapped conversion draft cannot be published as a CV template");
    }

    @Test
    void pdfDraftHasActualPageCountAndEscapedEditableText() throws Exception {
        Map<String, Long> before = counts();
        var draft = response(
            mvc.perform(
                multipart(BASE + "/import").file(file("sample.pdf", "application/pdf", pdf())).param("language", "en")
                    .header("Authorization", admin)
            ).andExpect(status().isOk()).andExpect(jsonPath("$.source_format").value("PDF"))
                .andExpect(jsonPath("$.page_count").value(1)).andExpect(jsonPath("$.requires_review").value(true))
                .andReturn()
        );
        var document = Jsoup.parse(draft.get("html_content").toString());
        assertTrue(document.body().text().contains("<not-markup> Java experience"));
        assertTrue(document.select("not-markup,img,svg,script").isEmpty());
        assertFalse(document.select("p[id^=import-block-]").isEmpty());
        assertEquals(before, counts());
    }

    @Test
    void invalidLanguageMissingFileAndWrongFormatFailWithoutWrites() throws Exception {
        Map<String, Long> before = counts();
        mvc.perform(
            multipart(BASE + "/import").file(file("sample.docx", DOCX_MIME, docx())).param("language", "zz")
                .header("Authorization", admin)
        ).andExpect(status().isBadRequest());
        mvc.perform(multipart(BASE + "/import").header("Authorization", admin)).andExpect(status().isBadRequest());
        mvc.perform(
            multipart(BASE + "/import").file(file("sample.txt", "text/plain", "Summary".getBytes()))
                .header("Authorization", admin)
        ).andExpect(status().isBadRequest());
        mvc.perform(
            multipart(BASE + "/import").file(file("sample.pdf", "application/pdf", "wrong".getBytes()))
                .header("Authorization", admin)
        ).andExpect(status().isBadRequest());
        assertEquals(before, counts());
    }

    @Test
    void importReviewMapSaveAndExplicitlyPublishMakesTheSameHtmlSelectableByCandidate() throws Exception {
        Map<String, Long> before = counts();
        Map<String, Object> draft = response(
            mvc.perform(
                multipart(BASE + "/import").file(file("review.docx", DOCX_MIME, docx())).param("language", "en")
                    .header("Authorization", admin)
            ).andExpect(status().isOk()).andReturn()
        );
        assertEquals(before, counts());
        var document = Jsoup.parse(draft.get("html_content").toString());
        document.selectFirst("h1").attr("data-cv-field", "fullName").text("Candidate name");
        document.selectFirst("p").tagName("div").attr("data-cv-section", "summary").empty();
        var created = response(
            mvc.perform(
                post(BASE).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(templateBody(document.outerHtml(), false)))
            ).andExpect(status().isOk()).andExpect(jsonPath("$.is_active").value(false)).andReturn()
        );
        String templateId = created.get("id").toString();
        String storedHtml = created.get("html_content").toString();
        assertEquals(before.get("resume_templates") + 1, count("resume_templates"));
        assertEquals(before.get("audit_logs") + 1, count("audit_logs"));
        assertEquals(before.get("ai_usage_logs"), count("ai_usage_logs"));
        assertFalse(listed(templateId));
        Map<String, Object> resume = Map.of(
            "title",
            "Candidate selects imported HTML",
            "language",
            "en",
            "template_id",
            templateId,
            "structured_data",
            Map.of("summary", "Verified candidate facts")
        );
        mvc.perform(
            post("/api/v1/resumes").header("Authorization", candidate).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(resume))
        ).andExpect(status().isNotFound());
        mvc.perform(
            patch(BASE + "/" + templateId + "/status").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}")
        ).andExpect(status().isOk());
        assertTrue(listed(templateId));
        Map<String, Object> cv = response(
            mvc.perform(
                post("/api/v1/resumes").header("Authorization", candidate).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(resume))
            ).andExpect(status().isOk()).andExpect(jsonPath("$.template_id").value(templateId))
                .andExpect(jsonPath("$.template.html_content").value(storedHtml)).andReturn()
        );
        mvc.perform(get("/api/v1/resumes/" + cv.get("id")).header("Authorization", candidate))
            .andExpect(status().isOk()).andExpect(jsonPath("$.template.html_content").value(storedHtml));
        assertEquals(
            storedHtml,
            db.scalar("SELECT html_content FROM resume_templates WHERE id=?", UUID.fromString(templateId))
        );
        assertEquals(before.get("ai_usage_logs"), count("ai_usage_logs"));
    }

    @Test
    void importedPdfArtworkAndTextGeometrySurviveSavingPublishingAndCandidateTemplateRetrieval() throws Exception {
        Map<String, Long> before = counts();
        Map<String, Object> draft = response(
            mvc.perform(
                multipart(BASE + "/import").file(file("sidebar.pdf", "application/pdf", pdfWithArtwork()))
                    .param("language", "en").header("Authorization", admin)
            ).andExpect(status().isOk()).andExpect(jsonPath("$.page_count").value(1)).andReturn()
        );
        assertEquals(before, counts(), "Import produces a review draft without a database write");
        var document = Jsoup.parse(draft.get("html_content").toString());
        String png = document.selectFirst("img.pdf-artwork").attr("src");
        assertTrue(png.startsWith("data:image/png;base64,"));
        var lines = document.select("p.pdf-line");
        assertEquals(2, lines.size());
        String nameGeometry = lines.getFirst().attr("style");
        assertTrue(nameGeometry.contains("position:absolute;left:40.00pt;"));
        lines.getFirst().attr("data-cv-field", "fullName");
        lines.getLast().tagName("div").attr("data-cv-section", "summary").empty();
        Map<String, Object> created = response(
            mvc.perform(
                post(BASE).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(templateBody(document.outerHtml(), false)))
            ).andExpect(status().isOk()).andExpect(jsonPath("$.is_active").value(false)).andReturn()
        );
        String templateId = created.get("id").toString();
        String storedHtml = created.get("html_content").toString();
        assertFalse(listed(templateId));
        mvc.perform(
            patch(BASE + "/" + templateId + "/status").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}")
        ).andExpect(status().isOk());
        List<?> available = mapper.readValue(
            mvc.perform(get("/api/v1/resumes/templates").header("Authorization", candidate)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(),
            List.class
        );
        Map<?, ?> candidateTemplate = (Map<?, ?>) available.stream()
            .filter(value -> templateId.equals(((Map<?, ?>) value).get("id"))).findFirst().orElseThrow();
        assertEquals(storedHtml, candidateTemplate.get("html_content"));
        var candidateHtml = Jsoup.parse(candidateTemplate.get("html_content").toString());
        assertEquals(png, candidateHtml.selectFirst("img.pdf-artwork").attr("src"));
        assertEquals(nameGeometry, candidateHtml.selectFirst("[data-cv-field=fullName]").attr("style"));
        assertEquals(
            storedHtml,
            db.scalar("SELECT html_content FROM resume_templates WHERE id=?", UUID.fromString(templateId))
        );
        assertEquals(before.get("resume_templates") + 1, count("resume_templates"));
        assertEquals(before.get("ai_usage_logs"), count("ai_usage_logs"));
    }

    private Map<String, Object> templateBody(
        String html,
        boolean active) {
        return Map.of(
            "name",
            "Imported reviewed template",
            "language",
            "en",
            "active",
            active,
            "trialEnabled",
            true,
            "htmlContent",
            html
        );
    }

    private Map<String, Long> counts() {
        return Map.of(
            "resume_templates",
            count("resume_templates"),
            "audit_logs",
            count("audit_logs"),
            "ai_usage_logs",
            count("ai_usage_logs")
        );
    }

    private long count(
        String table) {
        return ((Number) db.scalar("SELECT count(*) FROM " + table)).longValue();
    }

    private boolean listed(
        String templateId)
        throws Exception {
        List<?> templates = mapper.readValue(
            mvc.perform(get("/api/v1/resumes/templates")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(),
            List.class
        );
        return templates.stream().anyMatch(value -> templateId.equals(((Map<?, ?>) value).get("id")));
    }

    private String token(
        String role) {
        UUID id = db.insert(
            "users",
            Map.of(
                "email",
                "document-import-" + UUID.randomUUID() + "@example.test",
                "full_name",
                "Document Import Test",
                "password_hash",
                "test-only-unusable-hash",
                "status",
                "ACTIVE",
                "role",
                role
            )
        );
        return "Bearer " + tokens.issue(id).get("accessToken");
    }

    private Map<String, Object> response(
        MvcResult result)
        throws Exception {
        return mapper.readValue(result.getResponse().getContentAsString(), Map.class);
    }

    private MockMultipartFile file(
        String filename,
        String mime,
        byte[] bytes) {
        return new MockMultipartFile("file", filename, mime, bytes);
    }

    private byte[] docx() throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var title = document.createParagraph();
            title.setStyle("Heading1");
            title.createRun().setText("Example name");
            document.createParagraph().createRun().setText("<script>alert('source')</script> Summary example");
            document.write(output);
            return output.toByteArray();
        }
    }

    private byte[] pdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700);
                content.showText("<not-markup> Java experience");
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] pdfWithArtwork() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setNonStrokingColor(new Color(200, 215, 220));
                content.addRect(0, 0, 120, page.getMediaBox().getHeight());
                content.fill();
                content.setNonStrokingColor(Color.BLACK);
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700);
                content.showText("Example candidate name");
                content.newLineAtOffset(0, -40);
                content.showText("Example summary for mapping");
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
