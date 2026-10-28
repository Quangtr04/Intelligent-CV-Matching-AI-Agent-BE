package com.example.intelligent_cv_matching_ai_agent.admin;

import static org.junit.jupiter.api.Assertions.*;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.admin.resume.EmbeddedTemplatePng;
import com.example.intelligent_cv_matching_ai_agent.util.admin.resume.ResumeTemplateHtml;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmbeddedTemplatePngTests {

    @Test
    void boundedGeneratedPngSurvivesDraftAndPublishedTemplateWithoutAnExternalRequest() throws Exception {
        String uri = EmbeddedTemplatePng.dataUri(png());
        String html = "<!doctype html><main><h1 data-cv-field=fullName>Candidate</h1>"
            + "<section data-cv-section=summary></section><img class=pdf-artwork src=\"" + uri
            + "\" width=2 height=2 alt=\"Static PDF decoration\" aria-hidden=true "
            + "style=\"position:absolute;left:0;top:0;pointer-events:none\"></main>";
        String normalized = ResumeTemplateHtml.normalize(html);
        assertEquals(uri, Jsoup.parse(normalized).selectFirst("img").attr("src"));
        assertEquals(normalized, ResumeTemplateHtml.normalize(normalized));
        assertEquals(normalized, ResumeTemplateHtml.normalizeDraft(normalized));
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "https://example.test/photo.png",
            "//example.test/photo.png",
            "file:///secret.png",
            "data:image/svg+xml;base64,PHN2Zz48c2NyaXB0Lz48L3N2Zz4=",
            "data:text/html;base64,PHNjcmlwdD4=",
            "data:image/png;base64,PHNjcmlwdD4=",
            "data:image/png;base64,",
            "data:image/png;base64,!!!!",
            "data:image/png;base64,AA_A",
            "data:image/png;base64,AAAA\n"}
    )
    void externalUrlsActiveFormatsAndMalformedRasterPayloadsAreRejected(
        String src) {
        assertEquals(400, assertThrows(ApiException.class, () -> EmbeddedTemplatePng.validate(src)).status());
        assertThrows(ApiException.class, () -> ResumeTemplateHtml.normalizeDraft("<img src=\"" + src + "\">"));
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "onerror=alert(1)",
            "srcset=\"https://example.test/photo.png 2x\"",
            "loading=lazy",
            "crossorigin=anonymous",
            "data-cv-field=fullName",
            "data-cv-section=summary",
            "width=9999",
            "height=0"}
    )
    void imageCannotIntroduceEventsAlternateResourcesOrDataSlots(
        String attribute)
        throws Exception {
        String uri = EmbeddedTemplatePng.dataUri(png());
        assertThrows(
            ApiException.class,
            () -> ResumeTemplateHtml.normalizeDraft("<img src=\"" + uri + "\" " + attribute + ">")
        );
    }

    @Test
    void missingSourceIsRejected() {
        assertThrows(ApiException.class, () -> ResumeTemplateHtml.normalizeDraft("<img alt=\"Photo\">"));
    }

    @Test
    void staticTextRotationIsPreservedWithoutPermittingResourceLoadingCss() {
        String html = "<p style=\"position:absolute;transform:rotate(90deg);transform-origin:0 0\">CV</p>";
        assertTrue(ResumeTemplateHtml.normalizeDraft(html).contains("transform:rotate(90deg)"));
        assertThrows(
            ApiException.class,
            () -> ResumeTemplateHtml.normalizeDraft("<p style=\"transform:rotate(url(x))\">CV</p>")
        );
    }

    @Test
    void headerBoundsAreCheckedBeforeDecompressingImagePixels() throws Exception {
        for (int[] dimensions : new int[][]{{0, 2}, {2049, 2}, {2001, 2001}, {Integer.MAX_VALUE, 2}}) {
            byte[] bytes = png();
            ByteBuffer.wrap(bytes, 16, 8).putInt(dimensions[0]).putInt(dimensions[1]);
            updateCrc(bytes, 8);
            assertThrows(ApiException.class, () -> EmbeddedTemplatePng.dataUri(bytes));
        }
    }

    @Test
    void rejectsOversizedRawBytesBeforeEncodingAndOversizedBase64BeforeDecoding() {
        assertThrows(ApiException.class, () -> EmbeddedTemplatePng.dataUri(new byte[200001]));
        assertThrows(
            ApiException.class,
            () -> EmbeddedTemplatePng.validate("data:image/png;base64," + "A".repeat(266672))
        );
    }

    @Test
    void truncatedCorruptAndTrailingPolyglotBytesAreRejected() throws Exception {
        byte[] valid = png();
        assertThrows(ApiException.class, () -> EmbeddedTemplatePng.dataUri(Arrays.copyOf(valid, valid.length - 1)));
        byte[] corrupted = valid.clone();
        corrupted[42] ^= 1;
        assertThrows(ApiException.class, () -> EmbeddedTemplatePng.dataUri(corrupted));
        byte[] appended = Arrays.copyOf(valid, valid.length + 8);
        System.arraycopy("<script>".getBytes(StandardCharsets.US_ASCII), 0, appended, valid.length, 8);
        assertThrows(ApiException.class, () -> EmbeddedTemplatePng.dataUri(appended));
    }

    @Test
    void forgedPngWithCorrectChunkCrcButInvalidCompressedDataIsRejected() throws Exception {
        byte[] invalid = png();
        int cursor = 8;
        while (cursor < invalid.length) {
            int length = ByteBuffer.wrap(invalid, cursor, 4).getInt();
            if ("IDAT".equals(new String(invalid, cursor + 4, 4, StandardCharsets.US_ASCII))) {
                Arrays.fill(invalid, cursor + 8, cursor + 8 + length, (byte) 0);
                updateCrc(invalid, cursor);
                break;
            }
            cursor += length + 12;
        }
        assertThrows(ApiException.class, () -> EmbeddedTemplatePng.dataUri(invalid));
    }

    @Test
    void animatedPngAndUnboundedMetadataChunksAreRejected() throws Exception {
        byte[] valid = png();
        for (String type : new String[]{"acTL", "fcTL", "fdAT", "iCCP", "zTXt"}) {
            byte[] bytes = new byte[valid.length + 12];
            System.arraycopy(valid, 0, bytes, 0, 33);
            System.arraycopy(type.getBytes(StandardCharsets.US_ASCII), 0, bytes, 37, 4);
            updateCrc(bytes, 33);
            System.arraycopy(valid, 33, bytes, 45, valid.length - 33);
            assertThrows(ApiException.class, () -> EmbeddedTemplatePng.dataUri(bytes));
        }
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff112233);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, "PNG", output));
            return output.toByteArray();
        } finally {
            image.flush();
        }
    }

    private static void updateCrc(
        byte[] bytes,
        int cursor) {
        int length = ByteBuffer.wrap(bytes, cursor, 4).getInt();
        CRC32 checksum = new CRC32();
        checksum.update(bytes, cursor + 4, length + 4);
        ByteBuffer.wrap(bytes, cursor + 8 + length, 4).putInt((int) checksum.getValue());
    }
}
