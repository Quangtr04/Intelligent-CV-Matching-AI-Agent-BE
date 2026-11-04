package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeFileExtractor;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ResumeFileExtractorTests {

    private final ResumeFileExtractor extractor = new ResumeFileExtractor(false, "", "eng");

    @Test
    void extractsRealPdfText() throws Exception {
        byte[] bytes = textPdf();
        var result = extractor.extract(new MockMultipartFile("file", "resume.pdf", "application/pdf", bytes));
        assertTrue(result.text().contains("PostgreSQL"));
        assertFalse(result.ocrUsed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", " \t\r\n\f", "\uFEFF", "\uFEFF\r\n"})
    void extractsPdfAfterExportWhitespaceWithoutChangingStoredBytes(
        String prefix)
        throws Exception {
        byte[] bytes = prefixedPdf(prefix);
        var result = extractor.extract(new MockMultipartFile("file", "resume.pdf", "application/pdf", bytes));
        assertTrue(result.text().contains("PostgreSQL"));
        assertArrayEquals(bytes, result.bytes());
        assertFalse(result.ocrUsed());
    }

    @Test
    void rejectsEmbeddedPdfAfterNonWhitespaceAndExcessivePadding() throws Exception {
        for (String prefix : new String[]{"<html>\n", "not a PDF\n", "\u0000", " ".repeat(1025)}) {
            byte[] bytes = prefixedPdf(prefix);
            ApiException error = assertThrows(
                ApiException.class,
                () -> extractor.extract(new MockMultipartFile("file", "resume.pdf", "application/pdf", bytes))
            );
            assertEquals(400, error.status());
            assertEquals("Invalid PDF signature", error.getMessage());
        }
    }

    @Test
    void rejectsTruncatedPdfEvenWithRecognizedHeader() {
        byte[] bytes = "\n%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        ApiException error = assertThrows(
            ApiException.class,
            () -> extractor.extract(new MockMultipartFile("file", "resume.pdf", "application/pdf", bytes))
        );
        assertEquals(400, error.status());
    }

    @Test
    void extractsDocxAndSanitizesClientPath() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Nguyễn An - Backend Java");
            doc.write(output);
            bytes = output.toByteArray();
        }
        var result = extractor.extract(
            new MockMultipartFile(
                "file",
                "../../secret/resume.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                bytes
            )
        );
        assertEquals("resume.docx", result.filename());
        assertTrue(result.text().contains("Nguyễn An"));
    }

    @Test
    void rejectsDisguisedPdfAndMismatchedMime() {
        assertThrows(
            ApiException.class,
            () -> extractor.extract(
                new MockMultipartFile(
                    "file",
                    "resume.pdf",
                    "application/pdf",
                    "not a pdf".getBytes(StandardCharsets.UTF_8)
                )
            )
        );
        assertThrows(
            ApiException.class,
            () -> extractor.extract(
                new MockMultipartFile("file", "resume.txt", "application/pdf", "Java".getBytes(StandardCharsets.UTF_8))
            )
        );
    }

    @Test
    void rejectsOversizedUploadAndInvalidUtf8() {
        assertThrows(
            ApiException.class,
            () -> extractor.extract(
                new MockMultipartFile(
                    "file",
                    "resume.txt",
                    "text/plain",
                    new byte[(int) ResumeFileExtractor.MAX_BYTES + 1]
                )
            )
        );
        assertThrows(
            ApiException.class,
            () -> extractor.extract(
                new MockMultipartFile("file", "resume.txt", "text/plain", new byte[]{(byte) 0xc3, (byte) 0x28})
            )
        );
    }

    @Test
    void scannedPdfRequiresReviewWithoutInventedOcr() throws Exception {
        byte[] bytes;
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage());
            pdf.save(output);
            bytes = output.toByteArray();
        }
        var result = extractor.extract(new MockMultipartFile("file", "scan.pdf", "application/pdf", bytes));
        assertEquals("", result.text());
        assertFalse(result.ocrUsed());
    }

    private byte[] prefixedPdf(
        String prefix)
        throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            output.write(prefix.getBytes(StandardCharsets.UTF_8));
            output.write(textPdf());
            return output.toByteArray();
        }
    }

    private byte[] textPdf() throws Exception {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream text = new PDPageContentStream(pdf, page)) {
                text.beginText();
                text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                text.newLineAtOffset(30, 700);
                text.showText("Java Spring engineer with PostgreSQL experience");
                text.endText();
            }
            pdf.save(output);
            return output.toByteArray();
        }
    }
}
