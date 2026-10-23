package com.example.intelligent_cv_matching_ai_agent.util.admin.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.DocumentType;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

/** Strict document validation; candidate values are bound separately as escaped text. */
public final class ResumeTemplateHtml {

    private static final Set<String> TAGS = Set.of(
        "html",
        "head",
        "body",
        "title",
        "style",
        "meta",
        "div",
        "span",
        "p",
        "header",
        "footer",
        "main",
        "section",
        "article",
        "aside",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        "ul",
        "ol",
        "li",
        "dl",
        "dt",
        "dd",
        "strong",
        "b",
        "em",
        "i",
        "u",
        "s",
        "small",
        "sub",
        "sup",
        "br",
        "hr",
        "table",
        "thead",
        "tbody",
        "tfoot",
        "tr",
        "th",
        "td",
        "caption",
        "colgroup",
        "col",
        "blockquote",
        "pre",
        "code"
    );

    private static final Set<String> FIELDS = Set.of("fullName", "headline", "contact");

    private static final Set<String> SECTIONS =
        Set.of("summary", "experience", "education", "projects", "skills", "certifications");

    private static final Set<String> GLOBAL_ATTRIBUTES =
        Set.of("class", "id", "title", "lang", "dir", "style", "aria-label", "aria-hidden", "role");

    private static final Set<String> CSS_FUNCTIONS = Set.of(
        "rgb",
        "rgba",
        "hsl",
        "hsla",
        "calc",
        "min",
        "max",
        "clamp",
        "repeat",
        "minmax",
        "fit-content",
        "var",
        "nth-child",
        "nth-of-type",
        "not",
        "is",
        "where"
    );

    private static final Pattern CSS_CALL = Pattern.compile("(?<![@a-zA-Z0-9_-])([a-zA-Z_-][a-zA-Z0-9_-]*)\\(");

    private static final Pattern CSS_AT_RULE = Pattern.compile("@([a-zA-Z_-][a-zA-Z0-9_-]*)");

    private ResumeTemplateHtml() {
    }

    public static String normalize(
        String value) {
        return normalize(value, true);
    }

    /** Imported documents remain drafts until an administrator maps the CV slots. All safety rules still apply. */
    public static String normalizeDraft(
        String value) {
        return normalize(value, false);
    }

    private static String normalize(
        String value,
        boolean requireSlots) {
        if (value == null || value.isBlank()) {
            throw invalid("must be present and contain a complete template document");
        }
        if (value.length() > 200000 || hasForbiddenControl(value)) {
            throw invalid("must contain at most 200000 characters and no control characters");
        }
        Parser parser = Parser.htmlParser().setTrackErrors(100);
        Document document = Jsoup.parse(value.strip(), "", parser);
        if (!parser.getErrors().isEmpty()) {
            throw invalid("must be well-formed HTML without duplicate attributes or invalid markup");
        }
        Set<String> fields = new HashSet<>();
        Set<String> sections = new HashSet<>();
        for (Node node : document.nodeStream().toList()) {
            if (node instanceof Document) {
                continue;
            }
            if (node instanceof DocumentType type) {
                if (!"html".equalsIgnoreCase(type.name()) || !type.publicId().isEmpty() || !type.systemId().isEmpty()) {
                    throw invalid("supports only the HTML doctype without external identifiers");
                }
            } else if (node instanceof Comment) {
                node.remove();
            } else if (node instanceof Element element) {
                validateElement(element, fields, sections);
            } else if (node instanceof DataNode) {
                if (!(node.parent() instanceof Element parent) || !"style".equals(parent.normalName())) {
                    throw invalid("contains unsupported raw data");
                }
            } else if (!(node instanceof TextNode)) {
                throw invalid("contains unsupported markup");
            }
        }
        if (requireSlots && (!fields.contains("fullName") || sections.isEmpty())) {
            throw invalid("requires data-cv-field=fullName and at least one supported data-cv-section");
        }
        document.outputSettings().prettyPrint(false).charset(java.nio.charset.StandardCharsets.UTF_8);
        String normalized = document.outerHtml();
        if (normalized.length() > 200000) {
            throw invalid("normalized document exceeds 200000 characters");
        }
        return normalized;
    }

    private static void validateElement(
        Element element,
        Set<String> fields,
        Set<String> sections) {
        String tag = element.normalName();
        if (!TAGS.contains(tag)) {
            throw invalid("contains unsupported tag: " + tag);
        }
        for (var attribute : element.attributes()) {
            String name = attribute.getKey().toLowerCase(Locale.ROOT);
            String value = attribute.getValue();
            if ("data-cv-field".equals(name) || "data-cv-section".equals(name)) {
                boolean field = "data-cv-field".equals(name);
                if (!(field ? FIELDS : SECTIONS).contains(value)
                    || element.parents().stream()
                        .anyMatch(parent -> parent.hasAttr("data-cv-field") || parent.hasAttr("data-cv-section"))
                    || (field ? !fields.add(value) : !sections.add(value))
                    || Set.of("html", "head", "title", "style", "meta", "br", "hr", "col").contains(tag)
                    || element.hasAttr(field ? "data-cv-section" : "data-cv-field")) {
                    throw invalid("contains an unknown, duplicate or nested CV slot");
                }
            } else if ("style".equals(name)) {
                validateCss(value);
            } else if (GLOBAL_ATTRIBUTES.contains(name)) {
                if (value.length() > 2000 || hasForbiddenControl(value)) {
                    throw invalid("contains an invalid attribute value");
                }
            } else if ("charset".equals(name) && "meta".equals(tag)) {
                if (!"utf-8".equalsIgnoreCase(value)) {
                    throw invalid("supports only UTF-8 metadata");
                }
            } else if (Set.of("colspan", "rowspan", "span").contains(name)
                && Set.of("th", "td", "col", "colgroup").contains(tag) && value.matches("[1-9][0-9]?")
                || "scope".equals(name) && "th".equals(tag)
                    && Set.of("row", "col", "rowgroup", "colgroup").contains(value)) {
                // Static table geometry does not introduce resource or navigation capabilities.
            } else {
                throw invalid("contains unsupported attribute: " + name);
            }
        }
        if ("meta".equals(tag) && !element.hasAttr("charset")) {
            throw invalid("supports only meta charset=UTF-8");
        }
        if ("style".equals(tag)) {
            validateCss(element.data());
        }
    }

    private static void validateCss(
        String css) {
        String lower = css.toLowerCase(Locale.ROOT);
        if (hasForbiddenControl(css) || css.indexOf('\\') >= 0 || css.contains("/*") || css.contains("*/")
            || css.indexOf('<') >= 0 || css.indexOf('>') >= 0 || lower.contains("url") || lower.contains("import")
            || lower.contains("expression") || lower.contains("behavior") || lower.contains("binding")
            || lower.contains("javascript") || lower.contains("vbscript") || lower.contains("http:")
            || lower.contains("https:") || lower.contains("data:") || lower.contains("file:") || lower.contains("//")) {
            throw invalid("CSS cannot contain resource references, scripts, comments or escapes");
        }
        var functions = CSS_CALL.matcher(css);
        while (functions.find()) {
            if (!CSS_FUNCTIONS.contains(functions.group(1).toLowerCase(Locale.ROOT))) {
                throw invalid("contains unsupported CSS function");
            }
        }
        var atRules = CSS_AT_RULE.matcher(css);
        while (atRules.find()) {
            if (!Set.of("media", "page").contains(atRules.group(1).toLowerCase(Locale.ROOT))) {
                throw invalid("supports only @media and @page CSS rules");
            }
        }
    }

    private static boolean hasForbiddenControl(
        String value) {
        return value.codePoints().anyMatch(
            code -> code != '\n' && code != '\r' && code != '\t'
                && (Character.isISOControl(code) || Character.getType(code) == Character.FORMAT)
        );
    }

    private static ApiException invalid(
        String reason) {
        return ApiException.badRequest("htmlContent " + reason);
    }
}
