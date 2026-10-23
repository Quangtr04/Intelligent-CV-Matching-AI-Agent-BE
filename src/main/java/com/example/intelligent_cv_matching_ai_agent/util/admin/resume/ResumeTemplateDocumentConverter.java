package com.example.intelligent_cv_matching_ai_agent.util.admin.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.CharacterRun;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableCell;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** Converts local document content into an editable draft; it never creates CV bindings or persists files. */
@Component
public class ResumeTemplateDocumentConverter {

    public static final long MAX_BYTES = 10L * 1024 * 1024;

    private static final int MAX_PAGES = 10;

    private static final int MAX_BLOCKS = 2000;

    private static final int MAX_RUNS = 20000;

    private static final int MAX_HTML = 200000;

    private static final int MAX_TEXT = 100000;

    private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static final String CSS =
        "@page{size:A4;margin:16mm;}" + "*{box-sizing:border-box;}body{margin:0;background:#ffffff;color:#202124;"
            + "font-family:Arial,sans-serif;font-size:11pt;line-height:1.4;}"
            + "main{max-width:210mm;margin:0 auto;padding:16mm;}p{margin:0 0 8pt;white-space:pre-wrap;}"
            + "h1,h2,h3,h4,h5,h6{margin:12pt 0 6pt;}table{width:100%;border-collapse:collapse;margin:8pt 0;}"
            + "td,th{vertical-align:top;border:1px solid #d1d5db;padding:6pt;}td p{margin-bottom:4pt;}"
            + ".document-page{break-after:page;}.document-page:last-child{break-after:auto;}"
            + ".document-list{padding-left:14pt;}@media print{main{max-width:none;padding:0;}}";

    public Map<String, Object> convert(
        MultipartFile file,
        String language) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("A PDF, DOC or DOCX template file is required");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(413, "Template document exceeds 10 MB");
        }
        String filename = filename(file.getOriginalFilename());
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String expectedMime = switch (extension) {
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> DOCX_MIME;
            default -> throw ApiException.badRequest("Supported template documents: PDF, DOC, DOCX");
        };
        String mime = Objects.requireNonNullElse(file.getContentType(), "application/octet-stream").split(";")[0]
            .strip().toLowerCase(Locale.ROOT);
        if (!mime.equals(expectedMime) && !mime.equals("application/octet-stream")) {
            throw ApiException.badRequest("Template document extension and MIME type do not match");
        }
        if (language != null && !language.isBlank() && !language.matches("[a-zA-Z]{2,8}(-[a-zA-Z0-9]{2,8})?")) {
            throw ApiException.badRequest("Template language must be a language code such as vi or en");
        }
        try {
            byte[] bytes;
            try (var input = file.getInputStream()) {
                bytes = input.readNBytes((int) MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) {
                throw new ApiException(413, "Template document exceeds 10 MB");
            }
            Draft draft = new Draft(language);
            switch (extension) {
                case "pdf" -> pdf(bytes, draft);
                case "docx" -> docx(bytes, draft);
                case "doc" -> doc(bytes, draft);
                default -> throw ApiException.badRequest("Unsupported template document");
            }
            if (draft.textCharacters == 0 || draft.document.body().text().isBlank()) {
                throw ApiException.badRequest(
                    "The document has no extractable text. Scanned PDFs require OCR; "
                        + "upload a text-based PDF/Word document or an HTML template"
                );
            }
            draft
                .warn("Review the converted layout and replace example content with CV fields/sections before saving.");
            String html = draft.document.outerHtml();
            if (html.length() > MAX_HTML) {
                throw ApiException.badRequest("Converted template exceeds 200000 HTML characters");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("source_file_name", filename);
            result.put("source_format", extension.toUpperCase(Locale.ROOT));
            result.put("html_content", html);
            result.put("warnings", List.copyOf(draft.warnings));
            result.put("requires_review", true);
            result.put("page_count", draft.pages);
            result.put("blocks", draft.blocks());
            return result;
        } catch (ApiException error) {
            throw error;
        } catch (Exception error) {
            throw ApiException.badRequest(
                "The template document could not be read. Upload a valid, unencrypted " + "PDF, DOC or DOCX file"
            );
        }
    }

    private void pdf(
        byte[] bytes,
        Draft draft)
        throws IOException {
        if (!starts(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            throw ApiException.badRequest("Invalid PDF document signature");
        }
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) {
                throw ApiException.badRequest("Encrypted PDF templates are not supported");
            }
            draft.pages = document.getNumberOfPages();
            validatePages(draft.pages);
            draft.warn(
                "PDF text is converted into editable lines. Columns, tables, images and exact page geometry "
                    + "may differ; review and adjust the HTML/CSS. Images are not imported."
            );
            PositionedPdfStripper stripper = new PositionedPdfStripper(draft);
            stripper.setSortByPosition(true);
            stripper.getText(document);
        }
    }

    private void docx(
        byte[] bytes,
        Draft draft)
        throws IOException {
        if (!starts(bytes, new byte[]{'P', 'K', 3, 4})) {
            throw ApiException.badRequest("Invalid or encrypted DOCX document signature");
        }
        inspectDocxArchive(bytes);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            int declaredPages = document.getProperties().getExtendedProperties().getUnderlyingProperties().getPages();
            if (declaredPages > 0) {
                validatePages(declaredPages);
            }
            draft.warn(
                "Word content is reflowed as HTML; exact pagination, columns, drawings and advanced styles "
                    + "must be reviewed. Page count cannot be verified without a Word layout engine."
            );
            if (!document.getAllPictures().isEmpty()) {
                draft.warn("Embedded images are not imported. Recreate any visual decoration with static CSS.");
            }
            if (!document.getHeaderList().isEmpty() || !document.getFooterList().isEmpty()) {
                draft.warn(
                    "Word headers and footers are not imported; review the original document for missing content."
                );
            }
            docxElements(document.getBodyElements(), draft.main, draft, 0);
        }
    }

    private void docxElements(
        List<IBodyElement> elements,
        Element parent,
        Draft draft,
        int depth) {
        if (depth > 8) {
            throw ApiException.badRequest("Word template has too many nested tables");
        }
        for (IBodyElement element : elements) {
            if (element instanceof XWPFParagraph paragraph) {
                docxParagraph(paragraph, parent, draft);
            } else if (element instanceof XWPFTable table) {
                Element htmlTable = draft.block(parent, "table");
                Element body = htmlTable.appendElement("tbody");
                for (var row : table.getRows()) {
                    Element htmlRow = body.appendElement("tr");
                    for (var cell : row.getTableCells()) {
                        Element htmlCell = draft.block(htmlRow, "td");
                        docxElements(cell.getBodyElements(), htmlCell, draft, depth + 1);
                    }
                }
            } else {
                draft.warn("Some Word content controls or embedded objects were omitted; review the source document.");
            }
        }
    }

    private void docxParagraph(
        XWPFParagraph paragraph,
        Element parent,
        Draft draft) {
        String style = Objects.requireNonNullElse(paragraph.getStyle(), "").toLowerCase(Locale.ROOT);
        String tag = style.matches("heading[1-6]") ? "h" + style.charAt(style.length() - 1) : "p";
        Element target = draft.block(parent, tag);
        String alignment = switch (paragraph.getAlignment()) {
            case CENTER -> "center";
            case RIGHT -> "right";
            case BOTH, DISTRIBUTE -> "justify";
            default -> "left";
        };
        target.attr("style", "text-align:" + alignment + ";");
        if (paragraph.getNumID() != null) {
            target.addClass("document-list");
            target.appendText("• ");
            draft.warn("Word list numbering is represented by editable bullets; check the original numbering.");
        }
        if (paragraph.isPageBreak() && ++draft.explicitPageBreaks >= MAX_PAGES) {
            throw ApiException.badRequest("Template document exceeds 10 explicit pages");
        }
        for (XWPFRun run : paragraph.getRuns()) {
            draft.run();
            if (run.isVanish()) {
                continue;
            }
            draft.explicitPageBreaks += (int) run.getCTR().getBrList().stream()
                .filter(lineBreak -> lineBreak.getType() != null && "page".equals(lineBreak.getType().toString()))
                .count();
            if (draft.explicitPageBreaks >= MAX_PAGES) {
                throw ApiException.badRequest("Template document exceeds 10 explicit pages");
            }
            Element span = target.appendElement("span");
            span.attr(
                "style",
                runStyle(run.getFontFamily(), run.getFontSizeAsDouble(), run.isBold(), run.isItalic(), run.getColor())
            );
            draft.text(span, run.text());
        }
    }

    private void doc(
        byte[] bytes,
        Draft draft)
        throws IOException {
        if (!starts(
            bytes,
            new byte[]{(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1}
        )) {
            throw ApiException.badRequest("Invalid DOC document signature");
        }
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(bytes))) {
            int declaredPages =
                document.getSummaryInformation() == null ? 0 : document.getSummaryInformation().getPageCount();
            if (declaredPages > 0) {
                validatePages(declaredPages);
            }
            draft.warn(
                "Legacy Word content is reflowed as HTML; page count cannot be verified. Review tables, "
                    + "lists, columns, headers/footers and styles. Images and embedded objects are not imported."
            );
            Range range = document.getRange();
            if (range.numParagraphs() > MAX_BLOCKS) {
                throw ApiException.badRequest("Word template contains too many paragraphs");
            }
            for (int index = 0; index < range.numParagraphs(); index++) {
                Paragraph paragraph = range.getParagraph(index);
                if (paragraph.isInTable()) {
                    Table table = range.getTable(paragraph);
                    docTable(table, draft.main, draft);
                    while (index + 1 < range.numParagraphs()
                        && range.getParagraph(index + 1).getStartOffset() < table.getEndOffset()) {
                        index++;
                    }
                } else {
                    docParagraph(paragraph, draft.main, draft);
                }
            }
        }
    }

    private void docTable(
        Table table,
        Element parent,
        Draft draft) {
        Element body = draft.block(parent, "table").appendElement("tbody");
        for (int rowIndex = 0; rowIndex < table.numRows(); rowIndex++) {
            var row = table.getRow(rowIndex);
            Element htmlRow = body.appendElement("tr");
            for (int cellIndex = 0; cellIndex < row.numCells(); cellIndex++) {
                TableCell cell = row.getCell(cellIndex);
                Element htmlCell = draft.block(htmlRow, "td");
                for (int paragraphIndex = 0; paragraphIndex < cell.numParagraphs(); paragraphIndex++) {
                    docParagraph(cell.getParagraph(paragraphIndex), htmlCell, draft);
                }
            }
        }
    }

    private void docParagraph(
        Paragraph paragraph,
        Element parent,
        Draft draft) {
        Element target = draft.block(parent, "p");
        String alignment = switch (paragraph.getJustification()) {
            case 1 -> "center";
            case 2 -> "right";
            case 3 -> "justify";
            default -> "left";
        };
        target.attr("style", "text-align:" + alignment + ";");
        if (paragraph.isInList()) {
            target.addClass("document-list").appendText("• ");
            draft.warn("Word list numbering is represented by editable bullets; check the original numbering.");
        }
        for (int index = 0; index < paragraph.numCharacterRuns(); index++) {
            draft.run();
            CharacterRun run = paragraph.getCharacterRun(index);
            if (run.isVanished() || run.isMarkedDeleted() || run.isFldVanished()) {
                continue;
            }
            String text = Range.stripFields(run.text());
            long pageBreaks = text.chars().filter(character -> character == '\f').count();
            draft.explicitPageBreaks += (int) pageBreaks;
            if (draft.explicitPageBreaks >= MAX_PAGES) {
                throw ApiException.badRequest("Template document exceeds 10 explicit pages");
            }
            Element span = target.appendElement("span");
            span.attr(
                "style",
                runStyle(run.getFontName(), run.getFontSize() / 2.0, run.isBold(), run.isItalic(), null)
            );
            draft.text(span, text);
        }
    }

    private void inspectDocxArchive(
        byte[] bytes)
        throws IOException {
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            Set<String> names = new LinkedHashSet<>();
            long expanded = 0;
            boolean hasDocument = false;
            boolean hasTypes = false;
            byte[] buffer = new byte[8192];
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (!names.add(entry.getName()) || names.size() > 2000) {
                    throw ApiException.badRequest("DOCX has duplicate or too many archive entries");
                }
                hasDocument |= entry.getName().equals("word/document.xml");
                hasTypes |= entry.getName().equals("[Content_Types].xml");
                int count;
                while ((count = archive.read(buffer)) != -1) {
                    expanded += count;
                    if (expanded > 20L * 1024 * 1024) {
                        throw ApiException.badRequest("DOCX expanded content exceeds 20 MB");
                    }
                }
            }
            if (!hasDocument || !hasTypes) {
                throw ApiException.badRequest("DOCX is missing its Word document structure");
            }
        }
    }

    private static String runStyle(
        String font,
        Double size,
        boolean bold,
        boolean italic,
        String color) {
        StringBuilder style = new StringBuilder();
        if (font != null && font.matches("[a-zA-Z0-9 _-]{1,80}")
            && Set.of("url", "import", "expression", "behavior", "binding", "javascript", "vbscript").stream()
                .noneMatch(font.toLowerCase(Locale.ROOT)::contains)) {
            style.append("font-family:'").append(font).append("',sans-serif;");
        }
        if (size != null && Double.isFinite(size) && size >= 6 && size <= 72) {
            style.append("font-size:").append(String.format(Locale.ROOT, "%.1f", size)).append("pt;");
        }
        if (bold) {
            style.append("font-weight:700;");
        }
        if (italic) {
            style.append("font-style:italic;");
        }
        if (color != null && color.matches("[a-fA-F0-9]{6}")) {
            style.append("color:#").append(color).append(';');
        }
        return style.toString();
    }

    private static String filename(
        String original) {
        String filename = Objects.requireNonNullElse(original, "").replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        if (filename.isBlank() || filename.length() > 255 || filename.lastIndexOf('.') < 1) {
            throw ApiException.badRequest("Invalid template document filename");
        }
        return filename;
    }

    private static boolean starts(
        byte[] bytes,
        byte[] signature) {
        if (bytes.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (bytes[index] != signature[index]) {
                return false;
            }
        }
        return true;
    }

    private static void validatePages(
        int pages) {
        if (pages < 1 || pages > MAX_PAGES) {
            throw ApiException.badRequest("Template document must contain between 1 and 10 pages");
        }
    }

    private static final class Draft {

        private final Document document = Document.createShell("");

        private final Element main;

        private final Set<String> warnings = new LinkedHashSet<>();

        private final List<Element> elements = new ArrayList<>();

        private Integer pages;

        private int textCharacters;

        private int runs;

        private int explicitPageBreaks;

        private Draft(
            String language) {
            document.outputSettings().prettyPrint(false).charset(StandardCharsets.UTF_8);
            document.prependChild(new org.jsoup.nodes.DocumentType("html", "", ""));
            document.selectFirst("html").attr("lang", language == null || language.isBlank() ? "vi" : language);
            document.head().appendElement("meta").attr("charset", "UTF-8");
            document.head().appendElement("title").text("Imported resume template draft");
            document.head().appendElement("style").appendChild(new org.jsoup.nodes.DataNode(CSS));
            main = document.body().appendElement("main").addClass("document-body");
        }

        private Element block(
            Element parent,
            String tag) {
            if (elements.size() >= MAX_BLOCKS) {
                throw ApiException.badRequest("Converted template exceeds 2000 editable blocks");
            }
            Element element = parent.appendElement(tag).attr("id", "import-block-" + (elements.size() + 1));
            elements.add(element);
            return element;
        }

        private void text(
            Element parent,
            String value) {
            StringBuilder clean = new StringBuilder();
            Objects.requireNonNullElse(value, "").codePoints().forEach(code -> {
                if (code == '\r' || code == '\f' || code == 11) {
                    clean.append('\n');
                } else if (code == '\n' || code == '\t'
                    || !Character.isISOControl(code) && Character.getType(code) != Character.FORMAT) {
                    clean.appendCodePoint(code);
                }
            });
            textCharacters += clean.length();
            if (textCharacters > MAX_TEXT) {
                throw ApiException.badRequest("Converted template text exceeds 100000 characters");
            }
            parent.appendChild(new TextNode(clean.toString()));
        }

        private void run() {
            if (++runs > MAX_RUNS) {
                throw ApiException.badRequest("Template document contains too many text runs");
            }
        }

        private void warn(
            String message) {
            warnings.add(message);
        }

        private List<Map<String, Object>> blocks() {
            return elements.stream().map(element -> {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("id", element.id());
                block.put("tag", element.normalName());
                block.put("text", element.text());
                return block;
            }).toList();
        }
    }

    private static final class PositionedPdfStripper extends PDFTextStripper {

        private final Draft draft;

        private Element page;

        private Element line;

        private PositionedPdfStripper(
            Draft draft)
            throws IOException {
            this.draft = draft;
        }

        @Override
        protected void startPage(
            PDPage ignored) {
            page = draft.main.appendElement("section").addClass("document-page");
            line = null;
        }

        @Override
        protected void writeString(
            String text,
            List<TextPosition> positions) {
            if (text.isBlank()) {
                return;
            }
            draft.run();
            if (line == null) {
                line = draft.block(page, "p");
            }
            Element span = line.appendElement("span");
            if (!positions.isEmpty()) {
                TextPosition first = positions.getFirst();
                String font = first.getFont().getName().replaceFirst("^[A-Z]{6}\\+", "");
                String lowerFont = font.toLowerCase(Locale.ROOT);
                span.attr(
                    "style",
                    runStyle(
                        font,
                        (double) first.getFontSizeInPt(),
                        lowerFont.contains("bold"),
                        lowerFont.contains("italic") || lowerFont.contains("oblique"),
                        null
                    )
                );
            }
            draft.text(span, text);
        }

        @Override
        protected void writeWordSeparator() {
            if (line != null) {
                draft.text(line, " ");
            }
        }

        @Override
        protected void writeLineSeparator() {
            line = null;
        }
    }
}
