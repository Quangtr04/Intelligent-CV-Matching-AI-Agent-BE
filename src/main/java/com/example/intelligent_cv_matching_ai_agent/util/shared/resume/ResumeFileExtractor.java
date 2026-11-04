package com.example.intelligent_cv_matching_ai_agent.util.shared.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ExtractedResume;
import net.sourceforge.tess4j.Tesseract;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class ResumeFileExtractor {

    public static final long MAX_BYTES = 10 * 1024 * 1024;

    private final boolean ocrEnabled;

    private final String tessdata;

    private final String ocrLanguage;

    public ResumeFileExtractor(
        @Value("${app.ocr.enabled:false}")
        boolean ocrEnabled,
        @Value("${app.ocr.tessdata:}")
        String tessdata,
        @Value("${app.ocr.language:eng+vie}")
        String ocrLanguage) {
        this.ocrEnabled = ocrEnabled;
        this.tessdata = tessdata;
        this.ocrLanguage = ocrLanguage;
    }

    public ExtractedResume extract(
        MultipartFile file) {
        if (file == null || file.isEmpty())
            throw ApiException.badRequest("CV file is required");
        if (file.getSize() > MAX_BYTES)
            throw new ApiException(413, "CV exceeds 10 MB");

        String original = Objects.requireNonNullElse(file.getOriginalFilename(), "resume");
        String filename = original.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        filename = filename.replaceAll("[\\p{Cntrl}]", "");
        if (filename.isBlank() || filename.length() > 255)
            throw ApiException.badRequest("Invalid filename");

        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String mime = switch (extension) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "txt" -> "text/plain";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            default -> throw ApiException.badRequest("Supported CV formats: PDF, DOCX, TXT, PNG, JPEG");
        };
        String declared =
            Objects.requireNonNullElse(file.getContentType(), "application/octet-stream").split(";")[0].trim();
        if (!declared.equals(mime) && !declared.equals("application/octet-stream"))
            throw ApiException.badRequest("CV extension and MIME type do not match");

        try {
            byte[] bytes = file.getBytes();
            if (bytes.length > MAX_BYTES)
                throw new ApiException(413, "CV exceeds 10 MB");

            String text = "";
            boolean ocr = false;
            String parser = extension.toUpperCase(Locale.ROOT);

            switch (extension) {
                case "pdf" -> {
                    if (!PdfSignature.isValid(bytes))
                        throw ApiException.badRequest("Invalid PDF signature");

                    try (PDDocument pdf = Loader.loadPDF(bytes)) {
                        if (pdf.isEncrypted())
                            throw ApiException.badRequest("Encrypted PDFs are not supported");
                        if (pdf.getNumberOfPages() > 50)
                            throw ApiException.badRequest("CV exceeds 50 pages");

                        text = new PDFTextStripper().getText(pdf);

                        if (text.isBlank() && ocrEnabled) {
                            Tesseract engine = ocrEngine();
                            PDFRenderer renderer = new PDFRenderer(pdf);
                            StringBuilder content = new StringBuilder();

                            for (int page = 0; page < pdf.getNumberOfPages(); page++)
                                content.append(engine.doOCR(renderer.renderImageWithDPI(page, 150))).append('\n');

                            text = content.toString();
                            ocr = true;
                            parser = "PDF_TESSERACT";
                        }
                    }
                }
                case "docx" -> {
                    if (!starts(bytes, new byte[]{'P', 'K', 3, 4}) || !hasWordDocument(bytes))
                        throw ApiException.badRequest("Invalid DOCX signature or document structure");

                    try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
                        XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
                        text = extractor.getText();
                    }
                }
                case "txt" -> {
                    for (byte b : bytes)
                        if (b == 0)
                            throw ApiException.badRequest("TXT must contain plain UTF-8 text");

                    text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                }
                case "png", "jpg", "jpeg" -> {
                    boolean valid = extension.equals("png")
                        ? starts(bytes, new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10})
                        : starts(bytes, new byte[]{(byte) 255, (byte) 216, (byte) 255});
                    if (!valid)
                        throw ApiException.badRequest("Invalid image signature");
                    if (!ocrEnabled)
                        throw new ApiException(
                            503,
                            "Image CVs require APP_OCR_ENABLED and installed Tesseract language data"
                        );

                    java.awt.image.BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                    if (image == null || (long) image.getWidth() * image.getHeight() > 25_000_000)
                        throw ApiException.badRequest("Invalid or oversized CV image");

                    text = ocrEngine().doOCR(image);
                    ocr = true;
                    parser = "TESSERACT";
                }
            }

            if (text.length() > 200_000)
                throw ApiException.badRequest("Extracted CV text exceeds 200,000 characters");

            return new ExtractedResume(bytes, filename, mime, text.replace("\u0000", "").strip(), ocr, parser);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw ApiException.badRequest(
                ("The CV could not be read. Upload a valid, unencrypted file or enter " + "the CV manually.")
            );
        }
    }

    private Tesseract ocrEngine() {
        if (tessdata.isBlank())
            throw new ApiException(503, "Tesseract data directory is not configured");

        Tesseract engine = new Tesseract();
        engine.setDatapath(tessdata);
        engine.setLanguage(ocrLanguage);

        return engine;
    }

    private boolean starts(
        byte[] bytes,
        byte[] signature) {
        if (bytes.length < signature.length)
            return false;

        for (int i = 0; i < signature.length; i++)
            if (bytes[i] != signature[i])
                return false;

        return true;
    }

    private boolean hasWordDocument(
        byte[] bytes)
        throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            int entries = 0;
            long expanded = 0;
            boolean document = false;
            byte[] buffer = new byte[8192];

            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 2000)
                    throw ApiException.badRequest("DOCX has too many archive entries");

                if (entry.getName().equals("word/document.xml"))
                    document = true;

                int n;

                while ((n = zip.read(buffer)) != -1) {
                    expanded += n;
                    if (expanded > 50 * 1024 * 1024)
                        throw ApiException.badRequest("DOCX expanded content is too large");
                }
            }

            return document;
        }
    }
}
