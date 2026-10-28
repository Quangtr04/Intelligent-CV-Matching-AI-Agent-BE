package com.example.intelligent_cv_matching_ai_agent.util.admin.resume;

import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Standard fallback font advances keep unusual source fonts inside their original PDF line box. */
final class TemplatePdfTextFit {

    private TemplatePdfTextFit() {
    }

    static double width(
        List<ResumeTemplatePdfLayout.Run> runs) {
        double width = 0;
        for (var run : runs) {
            String style = run.style();
            boolean bold = style.contains("font-weight:700");
            boolean italic = style.contains("font-style:italic");
            Standard14Fonts.FontName name;
            if (style.contains("font-family:monospace")) {
                name = Standard14Fonts.FontName.COURIER;
            } else if (style.contains("font-family:'Times New Roman'")) {
                name = bold ? Standard14Fonts.FontName.TIMES_BOLD : Standard14Fonts.FontName.TIMES_ROMAN;
            } else {
                name = bold
                    ? (italic
                        ? Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE
                        : Standard14Fonts.FontName.HELVETICA_BOLD)
                    : (italic ? Standard14Fonts.FontName.HELVETICA_OBLIQUE : Standard14Fonts.FontName.HELVETICA);
            }
            int start = style.indexOf("font-size:") + "font-size:".length();
            int end = style.indexOf("pt;", start);
            if (start < "font-size:".length() || end < start) {
                return 0;
            }
            double size = Double.parseDouble(style.substring(start, end));
            try {
                width += new PDType1Font(name).getStringWidth(run.text()) / 1000.0 * size;
            } catch (IllegalArgumentException | IOException unsupported) {
                // Non-WinAnsi scripts cannot be measured with these fallback metrics; retain their geometry.
                return 0;
            }
        }
        return width;
    }
}
