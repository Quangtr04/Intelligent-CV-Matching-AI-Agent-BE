package com.example.intelligent_cv_matching_ai_agent.admin;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.admin.resume.ResumeTemplateHtml;
import java.util.stream.Stream;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class ResumeTemplateHtmlTests {

    private static final String SLOTS =
        "<h1 data-cv-field=\"fullName\">Sample name</h1><section data-cv-section=\"summary\"></section>";

    @Test
    void absentHtmlIsRejected() {
        assertThrows(ApiException.class, () -> ResumeTemplateHtml.normalize(null));
        assertThrows(ApiException.class, () -> ResumeTemplateHtml.normalize(" \r\n\t "));
    }

    @Test
    void semanticPrintDocumentPreservesSlotsGridFlexAndUtf8WithoutResources() {
        String html = "<!DOCTYPE html><html lang=\"vi\"><head><meta charset=\"UTF-8\"><title>CV</title>"
            + "<style>*{box-sizing:border-box}body{font-family:Arial,Georgia;display:grid;"
            + "grid-template-columns:69% 31%}header{display:flex;gap:12px}"
            + "@page{size:A4;margin:0}@media print{body{margin:0}}"
            + "@media(max-width:560px){body{grid-template-columns:1fr}}</style></head><body>"
            + "<!-- administrator explanation -->" + SLOTS + "<p>Nguyễn &amp; Trần</p></body></html>";
        String normalized = ResumeTemplateHtml.normalize(html);
        var document = Jsoup.parse(normalized);
        assertEquals("fullName", document.selectFirst("h1").attr("data-cv-field"));
        assertEquals("summary", document.selectFirst("section").attr("data-cv-section"));
        assertEquals("UTF-8", document.selectFirst("meta").attr("charset"));
        assertTrue(normalized.contains("grid-template-columns:69% 31%"));
        assertTrue(normalized.contains("@media(max-width:560px)"));
        assertTrue(document.body().text().contains("Nguyễn & Trần"));
        assertFalse(normalized.contains("administrator explanation"));
        assertEquals(normalized, ResumeTemplateHtml.normalize(normalized));
    }

    @ParameterizedTest
    @MethodSource("unsafeMarkup")
    void executableResourceNavigationAndCssBypassesAreRejected(
        String markup) {
        assertEquals(
            400,
            assertThrows(ApiException.class, () -> ResumeTemplateHtml.normalize(document(markup))).status()
        );
    }

    private static Stream<String> unsafeMarkup() {
        return Stream.of(
            "<script>alert(1)</script>",
            "<div onclick=\"alert(1)\">Click</div>",
            "<div onload=\"alert(1)\"></div>",
            "<iframe srcdoc=\"&lt;script&gt;alert(1)&lt;/script&gt;\"></iframe>",
            "<object data=\"https://example.test/x\"></object>",
            "<embed src=\"https://example.test/x\">",
            "<form action=\"https://example.test\"><input name=\"x\"></form>",
            "<a href=\"https://example.test\">Visit</a>",
            "<img src=\"https://example.test/a.png\">",
            "<link rel=\"stylesheet\" href=\"https://example.test/a.css\">",
            "<meta http-equiv=\"refresh\" content=\"0;https://example.test\">",
            "<base href=\"https://example.test\">",
            "<svg><script>alert(1)</script></svg>",
            "<math><mtext>unsupported foreign content</mtext></math>",
            "<div style=\"background:u&#114;l(https://example.test/x)\"></div>",
            "<div style=\"background:u\\72l(https://example.test/x)\"></div>",
            "<style>@import 'https://example.test/x.css';</style>",
            "<style>p{background:u/**/rl(https://example.test/x)}</style>",
            "<style>p{background:image-set('remote.png' 1x)}</style>",
            "<style>@font-face{font-family:X;src:'remote.woff'}</style>",
            "<style>p{width:expression(alert(1))}</style>",
            "<style>p{-moz-binding:danger}</style>",
            "<style>p{behavior:danger}</style>",
            "<div data-cv-field=\"socialUrl\"></div>",
            "<section data-cv-section=\"salary\"></section>",
            "<div data-cv-field=\"fullName\"></div>",
            "<section data-cv-section=\"experience\"><div data-cv-field=\"contact\"></div></section>",
            "<div data-cv-field=\"contact\" data-cv-section=\"projects\"></div>",
            "<div data-cv-field=\"headline\" data-cv-field=\"contact\"></div>",
            "<p>Forbidden\0text</p>"
        );
    }

    @Test
    void meaningfulSlotsAndBothStorageLengthBoundsAreRequired() {
        assertThrows(ApiException.class, () -> ResumeTemplateHtml.normalize("<!DOCTYPE html><p>No slots</p>"));
        assertThrows(
            ApiException.class,
            () -> ResumeTemplateHtml.normalize("<!DOCTYPE html><h1 data-cv-field=\"fullName\"></h1>")
        );
        assertThrows(ApiException.class, () -> ResumeTemplateHtml.normalize("x".repeat(200001)));
        String prefix = "<!doctype html><html><head></head><body>" + SLOTS + "<p>";
        String suffix = "</p></body></html>";
        String exact = prefix + "x".repeat(200000 - prefix.length() - suffix.length()) + suffix;
        assertEquals(200000, ResumeTemplateHtml.normalize(exact).length());
    }

    private static String document(
        String extra) {
        return "<!DOCTYPE html><html><head></head><body>" + SLOTS + extra + "</body></html>";
    }
}
