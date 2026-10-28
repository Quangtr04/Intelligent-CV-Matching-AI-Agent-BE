package com.example.intelligent_cv_matching_ai_agent.admin;

import static org.junit.jupiter.api.Assertions.*;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.admin.resume.ResumeTemplateDocumentConverter;
import com.example.intelligent_cv_matching_ai_agent.util.admin.resume.ResumeTemplateHtml;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ResumeTemplateDocumentConverterTests {

    private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final ResumeTemplateDocumentConverter converter = new ResumeTemplateDocumentConverter();

    @Test
    void convertsAnActualLegacyWordBinaryIntoEditableHtml() throws Exception {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/template-import/poi-simple.doc")) {
            assertNotNull(input);
            bytes = input.readAllBytes();
        }
        Map<String, Object> result = converter.convert(file("legacy.doc", "application/msword", bytes), "en");
        assertEquals("DOC", result.get("source_format"));
        assertNull(result.get("page_count"));
        var document = Jsoup.parse((String) result.get("html_content"));
        assertFalse(document.body().text().isBlank());
        assertFalse(document.select("p span").isEmpty());
        assertTrue(document.select("script,img,a,iframe,[data-cv-field],[data-cv-section]").isEmpty());
        assertFalse(((List<?>) result.get("warnings")).isEmpty());
        assertBlocksMatch(result);
        assertDoesNotThrow(() -> ResumeTemplateHtml.normalizeDraft((String) result.get("html_content")));
    }

    @Test
    void convertsPdfIntoEditableTextInsteadOfAnImageAndRetainsBasicFont() throws Exception {
        byte[] bytes = pdf(1, "Backend developer", false);
        Map<String, Object> result = converter.convert(file("resume.pdf", "application/pdf", bytes), "en");
        assertEquals("PDF", result.get("source_format"));
        assertEquals(1, result.get("page_count"));
        assertEquals(true, result.get("requires_review"));
        var document = Jsoup.parse((String) result.get("html_content"));
        assertEquals("en", document.selectFirst("html").attr("lang"));
        assertTrue(document.body().text().contains("Backend developer"));
        assertTrue(document.selectFirst("p").attr("style").contains("font-weight:700"));
        assertTrue(document.select("img,svg,script,a,link,iframe").isEmpty());
        assertTrue(document.select("[data-cv-field],[data-cv-section]").isEmpty());
        assertFalse(((List<?>) result.get("warnings")).isEmpty());
        assertBlocksMatch(result);
        assertDoesNotThrow(() -> ResumeTemplateHtml.normalizeDraft((String) result.get("html_content")));
    }

    @Test
    void convertsDocxParagraphsTablesAndStylingWithoutInterpretingTextAsMarkup() throws Exception {
        byte[] bytes;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var title = document.createParagraph();
            title.setStyle("Heading1");
            title.setAlignment(ParagraphAlignment.CENTER);
            var titleRun = title.createRun();
            titleRun.setText("Nguyễn An");
            titleRun.setBold(true);
            titleRun.setFontFamily("Arial");
            titleRun.setFontSize(22);
            var paragraph = document.createParagraph();
            var run = paragraph.createRun();
            run.setText("<script>alert('x')</script> & Java");
            run.setItalic(true);
            var table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("Experience");
            table.getRow(0).getCell(1).setText("Spring Boot and SQL");
            document.write(output);
            bytes = output.toByteArray();
        }
        Map<String, Object> result = converter.convert(file("C:\\private\\resume.docx", DOCX_MIME, bytes), "vi");
        assertEquals("resume.docx", result.get("source_file_name"));
        assertEquals("DOCX", result.get("source_format"));
        assertNull(result.get("page_count"));
        var document = Jsoup.parse((String) result.get("html_content"));
        assertEquals("Nguyễn An", document.selectFirst("h1").text());
        assertTrue(document.selectFirst("h1").attr("style").contains("text-align:center"));
        assertTrue(document.selectFirst("h1 span").attr("style").contains("font-size:22.0pt"));
        assertTrue(document.body().text().contains("<script>alert('x')</script> & Java"));
        assertTrue(document.select("script").isEmpty());
        assertEquals(2, document.select("table td").size());
        assertTrue(document.body().text().contains("Spring Boot and SQL"));
        assertBlocksMatch(result);
        assertDoesNotThrow(() -> ResumeTemplateHtml.normalizeDraft((String) result.get("html_content")));
    }

    @Test
    void convertsHyperlinkLabelsToPlainTextWithoutExternalResources() throws Exception {
        byte[] bytes;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createHyperlinkRun("https://example.invalid/track").setText("Portfolio");
            document.createParagraph().createRun().setText("Summary");
            document.write(output);
            bytes = output.toByteArray();
        }
        String html = (String) converter.convert(file("resume.docx", DOCX_MIME, bytes), "en").get("html_content");
        assertTrue(Jsoup.parse(html).body().text().contains("Portfolio"));
        assertFalse(html.contains("example.invalid"));
        assertTrue(Jsoup.parse(html).select("a,link").isEmpty());
    }

    @Test
    void rejectsFontCssInjectionAndDoesNotInventBindings() throws Exception {
        byte[] bytes;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var run = document.createParagraph().createRun();
            run.setFontFamily("Arial';background:url(https://example.invalid/x)");
            run.setText("Alice - example resume");
            document.write(output);
            bytes = output.toByteArray();
        }
        String html = (String) converter.convert(file("resume.docx", DOCX_MIME, bytes), "en").get("html_content");
        assertFalse(html.contains("url("));
        assertFalse(html.contains("data-cv-"));
        assertTrue(Jsoup.parse(html).body().text().contains("Alice - example resume"));
        assertDoesNotThrow(() -> ResumeTemplateHtml.normalizeDraft(html));
    }

    @Test
    void rejectsScannedOrEmptyPdfInsteadOfPretendingToConvertIt() throws Exception {
        ApiException error = assertThrows(
            ApiException.class,
            () -> converter.convert(file("scan.pdf", "application/pdf", pdf(1, null, false)), "vi")
        );
        assertEquals(400, error.status());
        assertTrue(error.getMessage().contains("no extractable text"));
    }

    @Test
    void rejectsEncryptedPdfAndTooManyPages() throws Exception {
        ApiException encrypted = assertThrows(
            ApiException.class,
            () -> converter.convert(file("locked.pdf", "application/pdf", pdf(1, "Summary", true)), "en")
        );
        assertEquals(400, encrypted.status());
        ApiException pages = assertThrows(
            ApiException.class,
            () -> converter.convert(file("long.pdf", "application/pdf", pdf(11, "Summary", false)), "en")
        );
        assertEquals(400, pages.status());
        assertTrue(pages.getMessage().contains("10 pages"));
    }

    @Test
    void rejectsDocxWithMoreThanTenExplicitPages() throws Exception {
        byte[] bytes;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var run = document.createParagraph().createRun();
            run.setText("Summary");
            for (int index = 0; index < 10; index++) {
                run.addBreak(BreakType.PAGE);
            }
            document.write(output);
            bytes = output.toByteArray();
        }
        ApiException error =
            assertThrows(ApiException.class, () -> converter.convert(file("long.docx", DOCX_MIME, bytes), "en"));
        assertEquals(400, error.status());
        assertTrue(error.getMessage().contains("10 explicit pages"));
    }

    @Test
    void rejectsEmptyDocxAndOversizedHtmlWithoutTruncation() throws Exception {
        byte[] empty;
        byte[] oversized;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph();
            document.write(output);
            empty = output.toByteArray();
        }
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var random = new java.util.Random(42);
            StringBuilder text = new StringBuilder();
            for (int index = 0; index < 20000; index++) {
                text.append("<&>").append((char) ('a' + random.nextInt(26)));
            }
            document.createParagraph().createRun().setText(text.toString());
            document.write(output);
            oversized = output.toByteArray();
        }
        assertEquals(
            400,
            assertThrows(ApiException.class, () -> converter.convert(file("empty.docx", DOCX_MIME, empty), "en"))
                .status()
        );
        ApiException error =
            assertThrows(ApiException.class, () -> converter.convert(file("large.docx", DOCX_MIME, oversized), "en"));
        assertTrue(error.getMessage().contains("200000 HTML"));
    }

    @Test
    void rejectsMissingWrongFormatMagicMimeLanguageAndOversizedFile() {
        assertEquals(400, assertThrows(ApiException.class, () -> converter.convert(null, "en")).status());
        byte[] text = "Summary".getBytes(StandardCharsets.UTF_8);
        assertEquals(
            400,
            assertThrows(ApiException.class, () -> converter.convert(file("resume.txt", "text/plain", text), "en"))
                .status()
        );
        assertEquals(
            400,
            assertThrows(ApiException.class, () -> converter.convert(file("resume.pdf", "application/pdf", text), "en"))
                .status()
        );
        assertEquals(
            400,
            assertThrows(
                ApiException.class,
                () -> converter.convert(file("resume.doc", "application/msword", text), "en")
            ).status()
        );
        assertEquals(
            400,
            assertThrows(ApiException.class, () -> converter.convert(file("resume.docx", DOCX_MIME, text), "en"))
                .status()
        );
        assertEquals(
            400,
            assertThrows(
                ApiException.class,
                () -> converter.convert(file("resume.pdf", "application/msword", text), "en")
            ).status()
        );
        assertEquals(
            400,
            assertThrows(
                ApiException.class,
                () -> converter.convert(file("resume.pdf", "application/pdf", text), "en\" onclick=\"x")
            ).status()
        );
        assertEquals(
            413,
            assertThrows(
                ApiException.class,
                () -> converter.convert(
                    file(
                        "resume.pdf",
                        "application/pdf",
                        new byte[(int) ResumeTemplateDocumentConverter.MAX_BYTES + 1]
                    ),
                    "en"
                )
            ).status()
        );
    }

    @Test
    void rejectsExpandedArchiveAndWrongZipDocumentStructure() throws Exception {
        byte[] wrong;
        byte[] expanded;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
            ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("other.xml"));
            zip.write("not Word".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            wrong = output.toByteArray();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
            ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(new byte[20 * 1024 * 1024 + 1]);
            zip.closeEntry();
            zip.finish();
            expanded = output.toByteArray();
        }
        assertEquals(
            400,
            assertThrows(ApiException.class, () -> converter.convert(file("wrong.docx", DOCX_MIME, wrong), "en"))
                .status()
        );
        ApiException error =
            assertThrows(ApiException.class, () -> converter.convert(file("expanded.docx", DOCX_MIME, expanded), "en"));
        assertTrue(error.getMessage().contains("expanded content"));
    }

    private void assertBlocksMatch(
        Map<String, Object> result) {
        var document = Jsoup.parse((String) result.get("html_content"));
        List<?> blocks = (List<?>) result.get("blocks");
        HashSet<String> ids = new HashSet<>();
        assertFalse(blocks.isEmpty());
        for (Object value : blocks) {
            Map<?, ?> block = (Map<?, ?>) value;
            String id = (String) block.get("id");
            assertTrue(id.matches("import-block-[1-9][0-9]*"));
            assertTrue(ids.add(id));
            var element = document.getElementById(id);
            assertNotNull(element);
            assertEquals(block.get("tag"), element.normalName());
            assertEquals(block.get("text"), element.text());
        }
        assertEquals(blocks.size(), document.select("[id]").size());
    }

    private MockMultipartFile file(
        String filename,
        String mime,
        byte[] bytes) {
        return new MockMultipartFile("file", filename, mime, bytes);
    }

    private byte[] pdf(
        int pages,
        String value,
        boolean encrypted)
        throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int index = 0; index < pages; index++) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (value != null) {
                    try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                        content.beginText();
                        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 18);
                        content.newLineAtOffset(40, 740);
                        content.showText(value);
                        content.endText();
                    }
                }
            }
            if (encrypted) {
                document
                    .protect(new StandardProtectionPolicy("owner-password", "reader-password", new AccessPermission()));
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
