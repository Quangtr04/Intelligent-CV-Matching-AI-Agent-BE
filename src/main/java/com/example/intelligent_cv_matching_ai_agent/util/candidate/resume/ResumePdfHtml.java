package com.example.intelligent_cv_matching_ai_agent.util.candidate.resume;

import com.example.intelligent_cv_matching_ai_agent.util.admin.resume.ResumeTemplateHtml;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.object;

/** Binds reviewed facts as escaped text into the same template slots used by the editor. */
public final class ResumePdfHtml {

    private ResumePdfHtml() {
    }

    public static String render(
        Map<String, Object> resume,
        String template) {
        Map<String, Object> data = object(resume.get("structured_data"));
        boolean english = "en".equals(resume.get("language"));
        Document document = Jsoup.parse(
            template == null || template.isBlank() ? defaultTemplate(english) : ResumeTemplateHtml.normalize(template)
        );
        document.outputSettings().charset(java.nio.charset.StandardCharsets.UTF_8);
        document.select("meta[http-equiv]").remove();
        document.head().prependElement("meta").attr("charset", "utf-8");
        document.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy")
            .attr("content", "default-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src 'none'");
        document.title(text(resume, "title"));
        setField(document, "fullName", text(data, "full_name"));
        setField(document, "headline", text(data, "position"));
        Map<String, Object> presentation = object(data.get("frontend_fields"));
        String contact = java.util.stream.Stream.of(
            text(data, "email"),
            text(data, "phone"),
            text(data, "location"),
            text(presentation, "website"),
            text(presentation, "linkedin"),
            text(presentation, "github")
        ).filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.joining(" | "));
        setField(document, "contact", contact);
        document.select(".cv-avatar").forEach(element -> element.text(initials(text(data, "full_name"))));
        for (String section : List.of("summary", "experience", "education", "skills", "projects", "certifications")) {
            Element content = new Element("div");
            if (section.equals("summary")) {
                String summary = text(data, "summary");
                boolean structured =
                    !summary.isBlank() || List.of("experience", "education", "projects", "skills", "certifications")
                        .stream().anyMatch(key -> data.get(key) instanceof List<?> rows && !rows.isEmpty());
                if (!structured) {
                    summary = text(resume, "raw_content");
                }
                if (!summary.isBlank()) {
                    content.appendElement("p").addClass("cv-entry-text").text(summary);
                }
            } else if (data.get(section) instanceof List<?> entries) {
                for (Object value : entries) {
                    appendEntry(content, section, object(value), english);
                }
            }
            for (Element slot : document.select("[data-cv-section=" + section + "]")) {
                if (content.children().isEmpty()) {
                    removeEmptySection(slot);
                    continue;
                }
                Element heading = slot.children().stream().filter(child -> child.tagName().matches("h[1-4]"))
                    .findFirst().orElse(null);
                Element savedHeading = heading == null ? null : heading.clone();
                slot.empty();
                if (savedHeading != null) {
                    slot.appendChild(savedHeading);
                }
                for (Element child : content.children()) {
                    slot.appendChild(child.clone());
                }
            }
        }
        document.head().appendElement("style").text("""
            @page { size: A4; margin: 12mm; }
            @media print { html, body { width: auto !important; min-height: 0 !important; }
              * { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
              [data-cv-section] { height: auto !important; overflow: visible !important; }
              h1, h2, h3, h4 { break-after: avoid; } }
            .cv-entry-text { white-space: pre-wrap; overflow-wrap: anywhere; }
            .cv-entry { margin: 0 0 12px; break-inside: avoid; }
            .cv-entry-title { font-weight: 700; }
            .cv-entry-period { font-size: .9em; color: #475569; }
            .cv-entry-items { padding-left: 18px; }
            """);
        return "<!DOCTYPE html>\n" + document.outerHtml();
    }

    private static void appendEntry(
        Element parent,
        String section,
        Map<String, Object> row,
        boolean english) {
        Element entry = parent.appendElement("div").addClass("cv-entry");
        List<String> headings = switch (section) {
            case "experience" -> List.of("job_title", "company_name");
            case "education" -> List.of("degree", "major", "school_name");
            case "projects" -> List.of("project_name", "role");
            default -> List.of("name", "issuer");
        };
        String heading = headings.stream().map(key -> text(row, key)).filter(value -> !value.isBlank())
            .collect(java.util.stream.Collectors.joining(" - "));
        entry.appendElement("div").addClass("cv-entry-title").text(heading);
        String from = text(row, "display_start_date");
        if (from.isBlank()) {
            from = text(row, section.equals("certifications") ? "issue_date" : "start_date");
        }
        String to = Boolean.TRUE.equals(row.get("is_current"))
            ? (english ? "Present" : "Hiện tại")
            : text(row, "display_end_date");
        if (to.isBlank()) {
            to = text(row, section.equals("certifications") ? "expiry_date" : "end_date");
        }
        if (!from.isBlank() || !to.isBlank()) {
            entry.appendElement("div").addClass("cv-entry-period").text(from + (to.isBlank() ? "" : " - " + to));
        }
        for (String key : List.of("location", "description", "gpa", "project_url", "credential_url")) {
            String value = text(row, key);
            if (!value.isBlank()) {
                entry.appendElement("p").addClass("cv-entry-text").text(value);
            }
        }
        if (row.get("technologies") instanceof List<?> technologies && !technologies.isEmpty()) {
            entry.appendElement("p").text(String.join(", ", technologies.stream().map(Object::toString).toList()));
        }
        if (row.get("highlights") instanceof List<?> highlights && !highlights.isEmpty()) {
            Element list = entry.appendElement("ul").addClass("cv-entry-items");
            highlights.forEach(value -> list.appendElement("li").text(value.toString()));
        }
    }

    private static void setField(
        Document document,
        String field,
        String value) {
        document.select("[data-cv-field=" + field + "]").forEach(element -> {
            if (value.isBlank()) {
                element.remove();
            } else {
                element.text(value);
            }
        });
    }

    private static void removeEmptySection(
        Element slot) {
        if (slot.is("section, .cv-section")) {
            slot.remove();
            return;
        }
        Element section = slot.parent() == null ? null : slot.parent().closest("section, .cv-section");
        if (section != null && section.select("[data-cv-section]").size() == 1) {
            section.remove();
            return;
        }
        Element previous = slot.previousElementSibling();
        if (previous != null && previous.tagName().matches("h[1-4]")) {
            previous.remove();
        }
        slot.remove();
    }

    private static String text(
        Map<String, Object> data,
        String key) {
        return Objects.toString(data.get(key), "").strip();
    }

    private static String initials(
        String name) {
        String[] words = name.split("\\s+");
        StringBuilder result = new StringBuilder();
        for (int index = Math.max(0, words.length - 2); index < words.length; index++) {
            if (!words[index].isEmpty()) {
                result.appendCodePoint(words[index].codePointAt(0));
            }
        }
        return result.isEmpty() ? "CV" : result.toString().toUpperCase(java.util.Locale.ROOT);
    }

    private static String defaultTemplate(
        boolean english) {
        String[] headings = english
            ? new String[]{"Profile", "Experience", "Education", "Skills", "Projects", "Certifications"}
            : new String[]{"Giới thiệu", "Kinh nghiệm", "Học vấn", "Kỹ năng", "Dự án", "Chứng chỉ"};
        StringBuilder html = new StringBuilder("""
            <html><head><meta charset="utf-8"><style>
            body { font: 11pt/1.5 Arial, sans-serif; color: #172b40; margin: 0; }
            header { padding-bottom: 16px; border-bottom: 3px solid #197585; margin-bottom: 20px; }
            h1 { font-size: 27pt; line-height: 1.2; margin: 0 0 7px; }
            [data-cv-field=headline] { font-size: 14pt; color: #197585; margin: 0 0 8px; }
            [data-cv-field=contact] { font-size: 9pt; color: #475569; margin: 0; overflow-wrap: anywhere; }
            h2 { font-size: 12pt; color: #197585; border-bottom: 1px solid #dce7eb; padding-bottom: 4px; }
            p { margin: 4px 0; } section { margin-bottom: 16px; }
            </style></head><body><header><h1 data-cv-field="fullName"></h1>
            <p data-cv-field="headline"></p><p data-cv-field="contact"></p></header>
            """);
        List<String> sections = List.of("summary", "experience", "education", "skills", "projects", "certifications");
        for (int index = 0; index < sections.size(); index++) {
            html.append("<section class=\"cv-section\"><h2>").append(headings[index])
                .append("</h2><div data-cv-section=\"").append(sections.get(index)).append("\"></div></section>");
        }
        return html.append("</body></html>").toString();
    }
}
