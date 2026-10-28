package com.example.intelligent_cv_matching_ai_agent.util.admin.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

/** Editable text geometry over a bounded raster layer containing only the PDF's static artwork. */
final class ResumeTemplatePdfLayout {

    private static final int MAX_GLYPHS = 100000;

    private ResumeTemplatePdfLayout() {
    }

    static Page read(
        PDDocument document,
        int pageIndex)
        throws IOException {
        var page = document.getPage(pageIndex);
        double width = page.getCropBox().getWidth();
        double height = page.getCropBox().getHeight();
        if (page.getRotation() % 180 != 0) {
            double temporary = width;
            width = height;
            height = temporary;
        }
        if (!Double.isFinite(width) || !Double.isFinite(height) || width < 1 || height < 1 || width > 1440
            || height > 1440) {
            throw ApiException.badRequest("PDF template page dimensions must be between 1 and 1440 points");
        }
        GraphicsRenderer renderer = new GraphicsRenderer(document);
        renderer.setSubsamplingAllowed(true);
        renderer.setAnnotationsFilter(annotation -> false);
        // 72 dpi keeps page geometry in PDF points and limits both decoded pixels and HTML payload.
        BufferedImage artwork = renderer.renderImage(pageIndex, 1, ImageType.RGB);
        renderer.restoreOccludedGlyphs(document, pageIndex, artwork);
        String png = null;
        if (hasArtwork(artwork)) {
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                BufferedImage indexed = indexedArtwork(artwork);
                ImageIO.write(indexed, "png", output);
                indexed.flush();
                png = EmbeddedTemplatePng.dataUri(output.toByteArray());
            }
        }
        artwork.flush();
        return new Page(width, height, png, lines(renderer.editableGlyphs()));
    }

    private static BufferedImage indexedArtwork(
        BufferedImage source) {
        return TemplateArtworkPalette.indexed(source);
    }

    private static boolean hasArtwork(
        BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xffffff) != 0xffffff) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Line> lines(
        List<Glyph> glyphs) {
        var baselines = new LinkedHashMap<String, List<Glyph>>();
        glyphs.stream().sorted(Comparator.comparingDouble(Glyph::y).thenComparingDouble(Glyph::x)).forEach(glyph -> {
            double radians = Math.toRadians(glyph.angle());
            double baseline = -glyph.x() * Math.sin(radians) + glyph.y() * Math.cos(radians);
            String key = Math.round(baseline * 2) + ":" + Math.round(glyph.angle() * 100);
            baselines.computeIfAbsent(key, ignored -> new ArrayList<>()).add(glyph);
        });
        List<Line> result = new ArrayList<>();
        for (var row : baselines.values()) {
            double radians = Math.toRadians(row.getFirst().angle());
            row.sort(
                Comparator.comparingDouble(glyph -> glyph.x() * Math.cos(radians) + glyph.y() * Math.sin(radians))
            );
            List<Glyph> fragment = new ArrayList<>();
            Glyph previous = null;
            for (Glyph glyph : row) {
                if (previous != null) {
                    double distance = (glyph.x() - previous.x()) * Math.cos(radians)
                        + (glyph.y() - previous.y()) * Math.sin(radians) - previous.width();
                    // A large gap is a column boundary, never a run of spaces connecting two columns.
                    if (distance > Math.max(10, Math.min(previous.size(), glyph.size()) * 1.2)) {
                        addLine(result, fragment);
                        fragment = new ArrayList<>();
                    }
                }
                fragment.add(glyph);
                previous = glyph;
            }
            addLine(result, fragment);
        }
        result.sort(Comparator.comparingDouble(Line::top).thenComparingDouble(Line::left));
        return result;
    }

    private static void addLine(
        List<Line> result,
        List<Glyph> glyphs) {
        if (glyphs.isEmpty() || glyphs.stream().allMatch(glyph -> glyph.text().isBlank())) {
            return;
        }
        Glyph first = glyphs.getFirst();
        double radians = Math.toRadians(first.angle());
        double size = glyphs.stream().mapToDouble(Glyph::size).max().orElse(first.size());
        double left = first.x() + Math.sin(radians) * size * .84;
        double top = first.y() - Math.cos(radians) * size * .84;
        Glyph last = glyphs.getLast();
        double width =
            (last.x() - first.x()) * Math.cos(radians) + (last.y() - first.y()) * Math.sin(radians) + last.width();
        List<Run> runs = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        String style = null;
        Glyph previous = null;
        for (Glyph glyph : glyphs) {
            if (previous != null) {
                double gap = (glyph.x() - previous.x()) * Math.cos(radians)
                    + (glyph.y() - previous.y()) * Math.sin(radians) - previous.width();
                if (gap > Math.min(previous.size(), glyph.size()) * .12 && !previous.text().endsWith(" ")
                    && !glyph.text().startsWith(" ")) {
                    text.append(' ');
                }
            }
            if (style != null && !style.equals(glyph.style())) {
                runs.add(new Run(text.toString(), style));
                text.setLength(0);
            }
            style = glyph.style();
            text.append(glyph.text());
            previous = glyph;
        }
        if (!text.isEmpty()) {
            runs.add(new Run(text.toString(), style));
        }
        result.add(new Line(left, top, Math.max(1, width), size, first.angle(), first.style(), runs));
    }

    static String number(
        double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    record Page(
        double width,
        double height,
        String png,
        List<Line> lines
    ) {
    }

    record Line(
        double left,
        double top,
        double width,
        double height,
        double angle,
        String style,
        List<Run> runs
    ) {
    }

    record Run(
        String text,
        String style
    ) {
    }

    private record Glyph(
        double x,
        double y,
        double width,
        double size,
        double angle,
        String text,
        String style,
        Rectangle2D footprint
    ) {
    }

    private static final class GraphicsRenderer extends PDFRenderer {

        private final List<Glyph> glyphs = new ArrayList<>();

        private final LinkedHashSet<Glyph> occluded = new LinkedHashSet<>();

        private int seenGlyphs;

        private long overlapChecks;

        private GraphicsRenderer(
            PDDocument document) {
            super(document);
        }

        private List<Glyph> editableGlyphs() {
            return glyphs.stream().filter(glyph -> !occluded.contains(glyph)).toList();
        }

        private void checkOverlapBudget() {
            if (++overlapChecks > 2000000) {
                throw ApiException.badRequest("PDF template has too much overlapping artwork to convert safely");
            }
        }

        private void restoreOccludedGlyphs(
            PDDocument document,
            int pageIndex,
            BufferedImage artwork)
            throws IOException {
            if (occluded.isEmpty()) {
                return;
            }
            // Copying a patch also copies any other text underneath it. Include that text in the static layer.
            List<Glyph> pending = new ArrayList<>(occluded);
            for (int index = 0; index < pending.size(); index++) {
                Rectangle2D footprint = pending.get(index).footprint();
                for (Glyph glyph : glyphs) {
                    checkOverlapBudget();
                    if (!occluded.contains(glyph)) {
                        if (footprint.intersects(glyph.footprint())) {
                            occluded.add(glyph);
                            pending.add(glyph);
                        }
                    }
                }
            }
            PDFRenderer originalRenderer = new PDFRenderer(document);
            originalRenderer.setSubsamplingAllowed(true);
            originalRenderer.setAnnotationsFilter(annotation -> false);
            BufferedImage original = originalRenderer.renderImage(pageIndex, 1, ImageType.RGB);
            try {
                for (Glyph glyph : occluded) {
                    Rectangle2D bounds = glyph.footprint();
                    int left = Math.max(0, (int) Math.floor(bounds.getMinX()));
                    int top = Math.max(0, (int) Math.floor(bounds.getMinY()));
                    int right = Math.min(artwork.getWidth(), (int) Math.ceil(bounds.getMaxX()));
                    int bottom = Math.min(artwork.getHeight(), (int) Math.ceil(bounds.getMaxY()));
                    for (int y = top; y < bottom; y++) {
                        for (int x = left; x < right; x++) {
                            artwork.setRGB(x, y, original.getRGB(x, y));
                        }
                    }
                }
            } finally {
                original.flush();
            }
        }

        @Override
        protected PageDrawer createPageDrawer(
            PageDrawerParameters parameters)
            throws IOException {
            return new PageDrawer(parameters) {

                @Override
                public void fillPath(
                    int windingRule)
                    throws IOException {
                    if (getGraphicsState().getNonStrokeAlphaConstant() > 0) {
                        markOccluded(getLinePath());
                    }
                    super.fillPath(windingRule);
                }

                @Override
                public void strokePath() throws IOException {
                    if (getGraphicsState().getAlphaConstant() > 0) {
                        markOccluded(strokedPath());
                    }
                    super.strokePath();
                }

                @Override
                public void fillAndStrokePath(
                    int windingRule)
                    throws IOException {
                    if (getGraphicsState().getNonStrokeAlphaConstant() > 0) {
                        markOccluded(getLinePath());
                    }
                    if (getGraphicsState().getAlphaConstant() > 0) {
                        markOccluded(strokedPath());
                    }
                    super.fillAndStrokePath(windingRule);
                }

                @Override
                public void drawImage(
                    PDImage image)
                    throws IOException {
                    if (getGraphicsState().getNonStrokeAlphaConstant() > 0) {
                        Shape imageBounds = getGraphicsState().getCurrentTransformationMatrix().createAffineTransform()
                            .createTransformedShape(new Rectangle2D.Double(0, 0, 1, 1));
                        markOccluded(imageBounds);
                    }
                    super.drawImage(image);
                }

                @Override
                public void shadingFill(
                    COSName shadingName)
                    throws IOException {
                    if (getGraphicsState().getNonStrokeAlphaConstant() > 0) {
                        markOccluded(getGraphicsState().getCurrentClippingPath());
                    }
                    super.shadingFill(shadingName);
                }

                private Shape strokedPath() {
                    float width = transformWidth(getGraphicsState().getLineWidth());
                    if (!Float.isFinite(width) || width < 0 || width > 10000) {
                        throw ApiException.badRequest("PDF contains unsupported stroke geometry");
                    }
                    return new BasicStroke(Math.max(.5f, width), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                        .createStrokedShape(getLinePath());
                }

                private void markOccluded(
                    Shape painted) {
                    Area area = new Area(painted);
                    area.intersect(getGraphicsState().getCurrentClippingPath());
                    if (area.isEmpty()) {
                        return;
                    }
                    Shape deviceShape = getGraphics().getTransform().createTransformedShape(area);
                    for (Glyph glyph : glyphs) {
                        checkOverlapBudget();
                        if (!occluded.contains(glyph)) {
                            if (deviceShape.intersects(glyph.footprint())) {
                                occluded.add(glyph);
                            }
                        }
                    }
                }

                @Override
                protected void showFontGlyph(
                    Matrix matrix,
                    PDFont font,
                    int code,
                    Vector displacement)
                    throws IOException {
                    if (!recordGlyph(matrix, font, code, displacement)) {
                        markStaticGlyph(matrix, displacement);
                        super.showFontGlyph(matrix, font, code, displacement);
                        return;
                    }
                    // Preserve PDF text clipping without painting the old text into the static artwork.
                    var state = getGraphicsState().getTextState();
                    RenderingMode mode = state.getRenderingMode();
                    if (mode.isClip()) {
                        state.setRenderingMode(RenderingMode.NEITHER_CLIP);
                        try {
                            super.showFontGlyph(matrix, font, code, displacement);
                        } finally {
                            state.setRenderingMode(mode);
                        }
                    }
                }

                @Override
                protected void showType3Glyph(
                    Matrix matrix,
                    PDType3Font font,
                    int code,
                    Vector displacement)
                    throws IOException {
                    if (!recordGlyph(matrix, font, code, displacement)) {
                        markStaticGlyph(matrix, displacement);
                        super.showType3Glyph(matrix, font, code, displacement);
                    }
                }

                private void markStaticGlyph(
                    Matrix matrix,
                    Vector displacement) {
                    var state = getGraphicsState();
                    RenderingMode mode = state.getTextState().getRenderingMode();
                    if (mode.isFill() && state.getNonStrokeAlphaConstant() > 0
                        || mode.isStroke() && state.getAlphaConstant() > 0) {
                        markOccluded(
                            matrix.createAffineTransform().createTransformedShape(
                                new Rectangle2D.Double(0, -.2, Math.max(.001, displacement.getX()), 1.2)
                            )
                        );
                    }
                }

                private boolean recordGlyph(
                    Matrix matrix,
                    PDFont font,
                    int code,
                    Vector displacement)
                    throws IOException {
                    var state = getGraphicsState();
                    if (++seenGlyphs > MAX_GLYPHS) {
                        throw ApiException.badRequest("PDF template contains too many text glyphs");
                    }
                    RenderingMode mode = state.getTextState().getRenderingMode();
                    if (!mode.isFill() && !mode.isStroke()) {
                        return false;
                    }
                    String text = font.toUnicode(code);
                    if (text == null || text.isEmpty()) {
                        return false;
                    }
                    AffineTransform transform = new AffineTransform(getGraphics().getTransform());
                    transform.concatenate(matrix.createAffineTransform());
                    var bounds = matrix.createAffineTransform().createTransformedShape(
                        new Rectangle2D.Double(0, -.2, Math.max(.001, displacement.getX()), 1.2)
                    );
                    if (!state.getCurrentClippingPath().contains(bounds.getBounds2D())) {
                        // Clipped/edge glyphs remain static rather than exposing hidden PDF content in HTML.
                        return false;
                    }
                    Point2D origin = transform.transform(new Point2D.Double(0, 0), null);
                    Point2D end =
                        transform.transform(new Point2D.Double(displacement.getX(), displacement.getY()), null);
                    Point2D up = transform.deltaTransform(new Point2D.Double(0, 1), null);
                    Point2D right = transform.deltaTransform(new Point2D.Double(1, 0), null);
                    double size = Math.hypot(up.getX(), up.getY());
                    double angle = Math.toDegrees(Math.atan2(right.getY(), right.getX()));
                    if (!Double.isFinite(size) || size < 1 || size > 200 || !Double.isFinite(origin.getX())
                        || !Double.isFinite(origin.getY())) {
                        throw ApiException.badRequest("PDF contains unsupported text geometry");
                    }
                    String name = font.getName().toLowerCase(Locale.ROOT);
                    String family = name.contains("courier") || name.contains("mono")
                        ? "monospace"
                        : name.contains("times") || name.contains("georgia")
                            ? "'Times New Roman',serif"
                            : "Arial,sans-serif";
                    int color = (mode.isFill() ? state.getNonStrokingColor() : state.getStrokingColor()).toRGB();
                    String style = "font-family:" + family + ";font-size:" + number(size) + "pt;color:"
                        + String.format(Locale.ROOT, "#%06x", color & 0xffffff) + ";font-weight:"
                        + (name.contains("bold") || name.contains("black") ? "700" : "400") + ";font-style:"
                        + (name.contains("italic") || name.contains("oblique") ? "italic" : "normal") + ";" + "opacity:"
                        + number(mode.isFill() ? state.getNonStrokeAlphaConstant() : state.getAlphaConstant()) + ";";
                    Rectangle2D rawFootprint =
                        getGraphics().getTransform().createTransformedShape(bounds).getBounds2D();
                    // Include antialiasing and italic overhangs when restoring the final painted pixels.
                    double padding = Math.max(1, size * .15);
                    Rectangle2D footprint = new Rectangle2D.Double(
                        rawFootprint.getX() - padding,
                        rawFootprint.getY() - padding,
                        rawFootprint.getWidth() + 2 * padding,
                        rawFootprint.getHeight() + 2 * padding
                    );
                    glyphs.add(
                        new Glyph(
                            origin.getX(),
                            origin.getY(),
                            origin.distance(end),
                            size,
                            angle,
                            text,
                            style,
                            footprint
                        )
                    );
                    return true;
                }
            };
        }
    }
}
