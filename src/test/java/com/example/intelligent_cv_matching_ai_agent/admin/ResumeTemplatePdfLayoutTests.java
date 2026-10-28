package com.example.intelligent_cv_matching_ai_agent.admin;

import static org.junit.jupiter.api.Assertions.*;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.admin.resume.ResumeTemplateDocumentConverter;
import com.example.intelligent_cv_matching_ai_agent.util.admin.resume.ResumeTemplateHtml;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ResumeTemplatePdfLayoutTests {

    private final ResumeTemplateDocumentConverter converter = new ResumeTemplateDocumentConverter();

    @Test
    void separatesColumnsOnTheSameBaselineAndKeepsTheirActualPageCoordinates() throws Exception {
        Map<String, Object> result = convert(
            new PDRectangle(200, 200),
            null,
            0,
            (
                document,
                content) -> {
                text(content, 20, 160, "CONTACT", Standard14Fonts.FontName.HELVETICA, Color.BLACK);
                text(content, 120, 160, "SUMMARY", Standard14Fonts.FontName.HELVETICA, Color.BLACK);
            }
        );
        var html = Jsoup.parse(result.get("html_content").toString());
        var lines = html.select("p.pdf-line");
        assertEquals(2, lines.size(), "Two PDF columns cannot become one connected HTML line");
        Element left = lines.stream().filter(line -> line.text().equals("CONTACT")).findFirst().orElseThrow();
        Element right = lines.stream().filter(line -> line.text().equals("SUMMARY")).findFirst().orElseThrow();
        assertEquals(20, cssPoints(left, "left"), .01);
        assertEquals(120, cssPoints(right, "left"), .01);
        assertEquals(cssPoints(left, "top"), cssPoints(right, "top"), .01);
        assertEquals(29.92, cssPoints(left, "top"), .01);
        assertTrue(html.select("img").isEmpty(), "A text-only PDF does not need a raster copy of its text");
        assertEquals(1, result.get("page_count"));
        assertDoesNotThrow(() -> ResumeTemplateHtml.normalizeDraft(result.get("html_content").toString()));
    }

    @Test
    void retainsDifferentFontsAndColorsWithinOneEditableLine() throws Exception {
        Map<String, Object> result = convert(
            new PDRectangle(200, 200),
            null,
            0,
            (
                document,
                content) -> {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.setNonStrokingColor(Color.BLUE);
                content.newLineAtOffset(20, 160);
                content.showText("Blue ");
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 12);
                content.setNonStrokingColor(Color.RED);
                content.showText("Red");
                content.endText();
            }
        );
        var html = Jsoup.parse(result.get("html_content").toString());
        var line = html.selectFirst("p.pdf-line");
        assertNotNull(line);
        assertEquals("Blue Red", line.text());
        assertEquals(2, line.select("span").size());
        assertTrue(line.attr("style").contains("color:#0000ff"));
        assertTrue(line.attr("style").contains("font-weight:400"));
        assertTrue(line.select("span").getLast().attr("style").contains("color:#ff0000"));
        assertTrue(line.select("span").getLast().attr("style").contains("font-weight:700"));
    }

    @Test
    void embedsSidebarLinesAndPhotoButNeverBurnsTheExampleTextIntoTheArtwork() throws Exception {
        Map<String, Object> result = convert(
            new PDRectangle(200, 200),
            null,
            0,
            (
                document,
                content) -> {
                content.setNonStrokingColor(new Color(40, 90, 140));
                content.addRect(0, 0, 50, 200);
                content.fill();
                content.setStrokingColor(Color.RED);
                content.setLineWidth(2);
                content.moveTo(60, 70);
                content.lineTo(180, 70);
                content.stroke();
                BufferedImage photo = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < 4; y++) {
                    for (int x = 0; x < 4; x++) {
                        photo.setRGB(x, y, Color.GREEN.getRGB());
                    }
                }
                content.drawImage(LosslessFactory.createFromImage(document, photo), 100, 30, 20, 20);
                photo.flush();
                text(content, 60, 150, "OLD SAMPLE NAME", Standard14Fonts.FontName.HELVETICA_BOLD, Color.BLACK);
            }
        );
        var html = Jsoup.parse(result.get("html_content").toString());
        assertEquals("OLD SAMPLE NAME", html.selectFirst("p.pdf-line").text());
        Element image = html.selectFirst("img.pdf-artwork");
        assertNotNull(image);
        assertEquals("true", image.attr("aria-hidden"));
        String source = image.attr("src");
        assertTrue(source.startsWith("data:image/png;base64,"));
        byte[] bytes = Base64.getDecoder().decode(source.substring(source.indexOf(',') + 1));
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            BufferedImage artwork = ImageIO.read(input);
            assertEquals(200, artwork.getWidth());
            assertEquals(200, artwork.getHeight());
            assertEquals(new Color(40, 90, 140).getRGB(), artwork.getRGB(20, 80));
            assertEquals(Color.RED.getRGB(), artwork.getRGB(90, 130));
            assertEquals(Color.GREEN.getRGB(), artwork.getRGB(110, 160));
            for (int y = 35; y < 56; y++) {
                for (int x = 60; x < 195; x++) {
                    assertEquals(Color.WHITE.getRGB(), artwork.getRGB(x, y), "Old text must remain replaceable HTML");
                }
            }
            artwork.flush();
        }
        String normalized = ResumeTemplateHtml.normalizeDraft(result.get("html_content").toString());
        assertEquals(source, Jsoup.parse(normalized).selectFirst("img").attr("src"));
    }

    @Test
    void escapesPdfTextAndPreservesUnicodeWithoutImportingHiddenText() throws Exception {
        Map<String, Object> result = convert(
            new PDRectangle(300, 200),
            null,
            0,
            (
                document,
                content) -> {
                text(content, 20, 160, "<script> & Résumé", Standard14Fonts.FontName.HELVETICA, Color.BLACK);
                content.beginText();
                content.setRenderingMode(RenderingMode.NEITHER);
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(20, 130);
                content.showText("HIDDEN OCR CONTENT");
                content.endText();
            }
        );
        String normalized = ResumeTemplateHtml.normalizeDraft(result.get("html_content").toString());
        var html = Jsoup.parse(normalized);
        assertEquals("<script> & Résumé", html.selectFirst("p.pdf-line").text());
        assertTrue(html.select("script").isEmpty());
        assertFalse(html.body().text().contains("HIDDEN OCR CONTENT"));
    }

    @Test
    void honorsNonzeroCropOriginsWithoutShiftingTheEditableTextOutsideItsPage() throws Exception {
        Map<String, Object> result = convert(
            new PDRectangle(400, 300),
            new PDRectangle(50, 25, 200, 150),
            0,
            (
                document,
                content) -> text(content, 70, 145, "CROPPED", Standard14Fonts.FontName.HELVETICA, Color.BLACK)
        );
        var html = Jsoup.parse(result.get("html_content").toString());
        var page = html.selectFirst(".pdf-page");
        var line = html.selectFirst("p.pdf-line");
        assertEquals(200, cssPoints(page, "width"), .01);
        assertEquals(150, cssPoints(page, "min-height"), .01);
        assertEquals(20, cssPoints(line, "left"), .01);
        assertEquals(19.92, cssPoints(line, "top"), .01);
    }

    @Test
    void rotatesTheTextTogetherWithTheCroppedPageInsteadOfOnlySwappingPageDimensions() throws Exception {
        Map<String, Object> result = convert(
            new PDRectangle(400, 300),
            new PDRectangle(50, 25, 200, 150),
            90,
            (
                document,
                content) -> text(content, 70, 145, "CROPPED", Standard14Fonts.FontName.HELVETICA, Color.BLACK)
        );
        String normalized = ResumeTemplateHtml.normalizeDraft(result.get("html_content").toString());
        var html = Jsoup.parse(normalized);
        var page = html.selectFirst(".pdf-page");
        var line = html.selectFirst("p.pdf-line");
        assertEquals(150, cssPoints(page, "width"), .01);
        assertEquals(200, cssPoints(page, "min-height"), .01);
        assertEquals(130.08, cssPoints(line, "left"), .01);
        assertEquals(20, cssPoints(line, "top"), .01);
        assertTrue(line.attr("style").contains("transform:rotate(90.00deg)"));
    }

    @Test
    void rejectsOversizedPagesBeforeAllocatingTheirRasterLayer() throws Exception {
        ApiException error = assertThrows(
            ApiException.class,
            () -> convert(
                new PDRectangle(5000, 5000),
                null,
                0,
                (
                    document,
                    content) -> {
                    text(content, 20, 160, "BOUNDED PAGE", Standard14Fonts.FontName.HELVETICA, Color.BLACK);
                }
            )
        );
        assertEquals(400, error.status());
        assertTrue(error.getMessage().contains("dimensions"));
    }

    @Test
    void clippedGlyphsStayStaticAndTheirHiddenTextIsNotExposedAsEditableHtml() throws Exception {
        Map<String, Object> result = convert(
            new PDRectangle(200, 200),
            null,
            0,
            (
                document,
                content) -> {
                content.saveGraphicsState();
                content.addRect(20, 150, 4, 20);
                content.clip();
                text(content, 20, 160, "PRIVATE", Standard14Fonts.FontName.HELVETICA_BOLD, Color.BLACK);
                content.restoreGraphicsState();
                text(content, 20, 100, "VISIBLE", Standard14Fonts.FontName.HELVETICA, Color.BLACK);
            }
        );
        var html = Jsoup.parse(ResumeTemplateHtml.normalizeDraft(result.get("html_content").toString()));
        assertEquals("VISIBLE", html.body().text());
        Element image = html.selectFirst("img.pdf-artwork");
        assertNotNull(image, "The visible sliver of the clipped glyph must remain in the static artwork");
        String source = image.attr("src");
        byte[] bytes = Base64.getDecoder().decode(source.substring(source.indexOf(',') + 1));
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            BufferedImage artwork = ImageIO.read(input);
            int visiblePixels = 0;
            for (int y = 30; y < 50; y++) {
                for (int x = 20; x < 24; x++) {
                    if (artwork.getRGB(x, y) != Color.WHITE.getRGB()) {
                        visiblePixels++;
                    }
                }
            }
            assertTrue(visiblePixels > 0, "Clipping must retain the visible part of the PDF glyph");
            for (int y = 20; y < 60; y++) {
                for (int x = 25; x < 110; x++) {
                    assertEquals(Color.WHITE.getRGB(), artwork.getRGB(x, y), "Content outside the clip stays hidden");
                }
            }
            artwork.flush();
        }
    }

    @Test
    void fullyTransparentPdfTextDoesNotBecomeVisibleAfterConversion() throws Exception {
        Map<String, Object> result = convert(
            new PDRectangle(200, 200),
            null,
            0,
            (
                document,
                content) -> {
                content.saveGraphicsState();
                PDExtendedGraphicsState transparent = new PDExtendedGraphicsState();
                transparent.setNonStrokingAlphaConstant(0f);
                content.setGraphicsStateParameters(transparent);
                text(content, 20, 160, "TRANSPARENT", Standard14Fonts.FontName.HELVETICA, Color.BLACK);
                content.restoreGraphicsState();
                text(content, 20, 100, "VISIBLE", Standard14Fonts.FontName.HELVETICA, Color.BLACK);
            }
        );
        var html = Jsoup.parse(ResumeTemplateHtml.normalizeDraft(result.get("html_content").toString()));
        var visible = html.select("p.pdf-line").stream().filter(line -> line.text().equals("VISIBLE")).findFirst();
        assertTrue(visible.isPresent());
        assertEquals(1, effectiveOpacity(visible.orElseThrow().selectFirst("span")), .001);
        var transparent =
            html.select("p.pdf-line").stream().filter(line -> line.text().equals("TRANSPARENT")).findFirst();
        if (transparent.isPresent()) {
            assertTrue(transparent.orElseThrow().select("span").stream().allMatch(span -> effectiveOpacity(span) == 0));
        }
        assertTrue(html.select("img").isEmpty(), "Transparent text must not become visible raster artwork");
    }

    @Test
    void halfTransparentPdfTextKeepsHalfOpacityRatherThanMultiplyingItOnParentAndChild() throws Exception {
        Map<String, Object> result = convert(
            new PDRectangle(200, 200),
            null,
            0,
            (
                document,
                content) -> {
                PDExtendedGraphicsState halfTransparent = new PDExtendedGraphicsState();
                halfTransparent.setNonStrokingAlphaConstant(.5f);
                content.setGraphicsStateParameters(halfTransparent);
                text(content, 20, 160, "HALF TRANSPARENT", Standard14Fonts.FontName.HELVETICA, Color.BLACK);
            }
        );
        var html = Jsoup.parse(ResumeTemplateHtml.normalizeDraft(result.get("html_content").toString()));
        assertEquals("HALF TRANSPARENT", html.body().text());
        for (Element span : html.select("p.pdf-line span")) {
            assertEquals(.5, effectiveOpacity(span), .001, "PDF alpha is applied once to each editable text run");
        }
    }

    @Test
    void rotated270DegreePageKeepsItsTextInsideTheSwappedCropDimensions() throws Exception {
        Map<String, Object> result = convert(
            new PDRectangle(400, 300),
            new PDRectangle(50, 25, 200, 150),
            270,
            (
                document,
                content) -> text(content, 70, 145, "CROPPED", Standard14Fonts.FontName.HELVETICA, Color.BLACK)
        );
        var html = Jsoup.parse(ResumeTemplateHtml.normalizeDraft(result.get("html_content").toString()));
        var page = html.selectFirst(".pdf-page");
        var line = html.selectFirst("p.pdf-line");
        assertEquals(150, cssPoints(page, "width"), .01);
        assertEquals(200, cssPoints(page, "min-height"), .01);
        assertEquals(19.92, cssPoints(line, "left"), .01);
        assertEquals(180, cssPoints(line, "top"), .01);
        assertTrue(line.attr("style").contains("transform:rotate(-90.00deg)"));
    }

    @Test
    void opaqueArtworkDrawnAfterTextCannotRevealTheCoveredSourceTextInHtml() throws Exception {
        Map<String, Object> result = convert(
            new PDRectangle(200, 200),
            null,
            0,
            (
                document,
                content) -> {
                text(content, 20, 160, "SECRET", Standard14Fonts.FontName.HELVETICA_BOLD, Color.BLACK);
                content.setNonStrokingColor(Color.WHITE);
                content.addRect(15, 145, 100, 30);
                content.fill();
                text(content, 20, 100, "VISIBLE", Standard14Fonts.FontName.HELVETICA, Color.BLACK);
            }
        );
        var html = Jsoup.parse(ResumeTemplateHtml.normalizeDraft(result.get("html_content").toString()));
        assertEquals("VISIBLE", html.body().text(), "Text covered by later PDF artwork must remain hidden");
        assertTrue(html.select("img").isEmpty(), "White artwork must not restore the hidden text as bitmap pixels");
    }

    @Test
    void partiallyCoveredTextUsesFinalPdfPixelsInsteadOfReappearingAboveTheCoveringShape() throws Exception {
        byte[] source = pdfBytes(
            new PDRectangle(200, 200),
            null,
            0,
            (
                document,
                content) -> {
                text(content, 20, 160, "PARTIALLY COVERED", Standard14Fonts.FontName.HELVETICA_BOLD, Color.BLACK);
                content.setNonStrokingColor(Color.BLUE);
                content.addRect(70, 145, 110, 30);
                content.fill();
                text(content, 20, 100, "VISIBLE", Standard14Fonts.FontName.HELVETICA, Color.BLACK);
            }
        );
        var result = converter.convert(new MockMultipartFile("file", "occluded.pdf", "application/pdf", source), "en");
        var html = Jsoup.parse(ResumeTemplateHtml.normalizeDraft(result.get("html_content").toString()));
        assertEquals("VISIBLE", html.body().text(), "The partly obscured source line belongs to static artwork");
        String uri = html.selectFirst("img.pdf-artwork").attr("src");
        byte[] png = Base64.getDecoder().decode(uri.substring(uri.indexOf(',') + 1));
        try (PDDocument document = Loader.loadPDF(source); ByteArrayInputStream input = new ByteArrayInputStream(png)) {
            BufferedImage original = new PDFRenderer(document).renderImage(0, 1, ImageType.RGB);
            BufferedImage artwork = ImageIO.read(input);
            assertEquals(Color.BLUE.getRGB(), artwork.getRGB(100, 35));
            int visibleTextPixels = 0;
            for (int y = 20; y < 55; y++) {
                for (int x = 15; x < 185; x++) {
                    int expected = original.getRGB(x, y);
                    int actual = artwork.getRGB(x, y);
                    for (int shift : new int[]{0, 8, 16}) {
                        assertTrue(
                            Math.abs((expected >> shift & 255) - (actual >> shift & 255)) <= 52,
                            "Static partial text must match the original final paint order within PNG palette tolerance"
                        );
                    }
                    if (x < 65 && expected != Color.WHITE.getRGB()) {
                        visibleTextPixels++;
                    }
                }
            }
            assertTrue(visibleTextPixels > 0, "The uncovered prefix must remain visible in the static layer");
            original.flush();
            artwork.flush();
        }
    }

    @Test
    void imagePaintedAfterTextAlsoKeepsTheCoveredSourceTextOutOfEditableHtml() throws Exception {
        Map<String, Object> result = convert(
            new PDRectangle(200, 200),
            null,
            0,
            (
                document,
                content) -> {
                text(content, 20, 160, "SECRET", Standard14Fonts.FontName.HELVETICA_BOLD, Color.BLACK);
                BufferedImage covering = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
                covering.setRGB(0, 0, Color.BLUE.getRGB());
                content.drawImage(LosslessFactory.createFromImage(document, covering), 15, 145, 100, 30);
                covering.flush();
                text(content, 20, 100, "VISIBLE", Standard14Fonts.FontName.HELVETICA, Color.BLACK);
            }
        );
        var html = Jsoup.parse(ResumeTemplateHtml.normalizeDraft(result.get("html_content").toString()));
        assertEquals("VISIBLE", html.body().text());
        String uri = html.selectFirst("img.pdf-artwork").attr("src");
        byte[] png = Base64.getDecoder().decode(uri.substring(uri.indexOf(',') + 1));
        try (ByteArrayInputStream input = new ByteArrayInputStream(png)) {
            BufferedImage artwork = ImageIO.read(input);
            assertEquals(Color.BLUE.getRGB(), artwork.getRGB(35, 35));
            artwork.flush();
        }
    }

    private Map<String, Object> convert(
        PDRectangle media,
        PDRectangle crop,
        int rotation,
        PageContent writer)
        throws Exception {
        byte[] bytes = pdfBytes(media, crop, rotation, writer);
        return converter.convert(new MockMultipartFile("file", "layout.pdf", "application/pdf", bytes), "en");
    }

    private byte[] pdfBytes(
        PDRectangle media,
        PDRectangle crop,
        int rotation,
        PageContent writer)
        throws Exception {
        byte[] bytes;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(media);
            if (crop != null) {
                page.setCropBox(crop);
            }
            page.setRotation(rotation);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                writer.write(document, content);
            }
            document.save(output);
            bytes = output.toByteArray();
        }
        return bytes;
    }

    private static void text(
        PDPageContentStream content,
        float x,
        float y,
        String value,
        Standard14Fonts.FontName font,
        Color color)
        throws Exception {
        content.beginText();
        content.setFont(new PDType1Font(font), 12);
        content.setNonStrokingColor(color);
        content.newLineAtOffset(x, y);
        content.showText(value);
        content.endText();
    }

    private static double cssPoints(
        Element element,
        String property) {
        var matcher = Pattern.compile("(?:^|;)" + Pattern.quote(property) + ":(-?[0-9.]+)pt(?:;|$)")
            .matcher(element.attr("style"));
        assertTrue(matcher.find(), () -> "Missing " + property + " in " + element.attr("style"));
        return Double.parseDouble(matcher.group(1));
    }

    private static double effectiveOpacity(
        Element element) {
        double opacity = 1;
        while (element != null) {
            var matcher = Pattern.compile("(?:^|;)opacity:([0-9.]+)(?:;|$)").matcher(element.attr("style"));
            if (matcher.find()) {
                opacity *= Double.parseDouble(matcher.group(1));
            }
            element = element.parent();
        }
        return opacity;
    }

    @FunctionalInterface
    private interface PageContent {

        void write(
            PDDocument document,
            PDPageContentStream content)
            throws Exception;
    }
}
