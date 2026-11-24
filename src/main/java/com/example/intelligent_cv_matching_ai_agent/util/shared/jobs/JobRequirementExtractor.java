package com.example.intelligent_cv_matching_ai_agent.util.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.JobSkillRequest;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/** Bounded evidence extraction. Unknown clauses remain reviewable; no model calls. */
public final class JobRequirementExtractor {

    public static final String VERSION = "requirements-v6";

    private static final SkillCatalog CATALOG = SkillCatalog.get();

    private static final Map<String, Pattern> PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();

    public record Skill(
        String name,
        String category,
        String id,
        String type,
        String recognition,
        List<String> sourceReferences
    ) {
    }

    public record Bloom(
        String level,
        String evidence,
        String justification
    ) {
    }

    public record Requirement(
        String text,
        String section,
        String importance,
        String relation,
        List<String> types,
        List<Skill> skills,
        String status,
        List<Bloom> bloom
    ) {
    }

    public record Extraction(
        String version,
        String status,
        List<Requirement> requirements,
        int recognized,
        int reviewRequired,
        String catalogVersion
    ) {

        public List<JobSkillRequest> legacySkills() {
            Map<String, Boolean> found = new LinkedHashMap<>();
            for (Requirement row : requirements) {
                for (Skill skill : row.skills) {
                    boolean mandatory = row.importance.equals("MANDATORY") && row.relation.equals("AND");
                    found.merge(skill.name, mandatory, Boolean::logicalOr);
                }
            }
            return found.entrySet().stream().map(e -> new JobSkillRequest(null, e.getKey(), e.getValue())).toList();
        }
    }

    private JobRequirementExtractor() {
    }

    public static String fold(
        String value) {
        return SkillCatalog.fold(value);
    }

    public record SkillMention(
        String name,
        int start,
        int end
    ) {
    }

    public static List<SkillMention> mentions(
        String text) {
        return CATALOG.mentions(text).stream().map(m -> new SkillMention(m.skill().name(), m.start(), m.end()))
            .toList();
    }

    public static List<Skill> recognize(
        String text) {
        Set<String> literal = new HashSet<>();
        CATALOG.mentions(text).forEach(m -> literal.add(m.skill().id()));
        return CATALOG.recognize(text).stream()
            .map(
                skill -> new Skill(
                    skill.name(),
                    skill.type().equals("TECHNOLOGY") ? "TECHNICAL" : skill.type(),
                    skill.id(),
                    skill.type(),
                    literal.contains(skill.id()) ? "ALIAS" : "CONTEXTUAL",
                    skill.sourceReferences()
                )
            ).toList();
    }

    public static Extraction extract(
        String explicit,
        String requirements,
        String content) {
        LinkedHashMap<String, Requirement> rows = new LinkedHashMap<>();
        collect(requirements, "REQUIREMENTS", rows);
        collect(content, "UNSPECIFIED", rows);
        Map<String, Requirement> explicitRows = new LinkedHashMap<>();
        collect(explicit, "EXPLICIT", explicitRows);
        Set<String> qualified = new HashSet<>();
        rows.values().stream().filter(row -> !row.importance.equals("RESPONSIBILITY"))
            .flatMap(row -> row.skills.stream()).forEach(skill -> qualified.add(CATALOG.comparisonKey(skill.name)));
        for (var entry : explicitRows.entrySet()) {
            Requirement row = entry.getValue();
            var additional =
                row.skills.stream().filter(skill -> !qualified.contains(CATALOG.comparisonKey(skill.name))).toList();
            if (!additional.isEmpty() || row.skills.isEmpty()) {
                rows.putIfAbsent(
                    "explicit:" + entry.getKey(),
                    new Requirement(
                        row.text,
                        row.section,
                        row.importance,
                        row.relation,
                        row.types,
                        additional,
                        row.status,
                        row.bloom
                    )
                );
            }
        }
        int recognized = (int) rows.values().stream().filter(r -> r.status.equals("RECOGNIZED")).count();
        int review = rows.size() - recognized;
        return new Extraction(
            VERSION,
            rows.size() >= 300 || java.util.stream.Stream.of(explicit, requirements, content)
                .anyMatch(value -> value != null && value.length() > 100_000)
                    ? "TRUNCATED"
                    : rows.isEmpty() ? "MISSING_SOURCE" : review > 0 ? "REVIEW_REQUIRED" : "PARSED",
            List.copyOf(rows.values()),
            recognized,
            review,
            CATALOG.version()
        );
    }

    public static Extraction forJob(
        Map<String, Object> job) {
        Object snapshot = job.get("requirement_extraction");
        if (snapshot instanceof Map<?, ?> data
            && (VERSION.equals(data.get("version")) && CATALOG.version().equals(data.get("catalogVersion"))
                || Boolean.TRUE.equals(job.get("requirement_extraction_verified")))) {
            var saved =
                tools.jackson.databind.json.JsonMapper.builder().build().convertValue(snapshot, Extraction.class);
            if (saved.requirements().stream().allMatch(row -> row.bloom() == null || row.bloom().isEmpty()))
                return saved;
            return new Extraction(
                saved.version(),
                saved.status(),
                saved.requirements().stream()
                    .map(
                        row -> new Requirement(
                            row.text(),
                            row.section(),
                            row.importance(),
                            row.relation(),
                            row.types(),
                            row.skills(),
                            row.status(),
                            List.of()
                        )
                    ).toList(),
                saved.recognized(),
                saved.reviewRequired(),
                saved.catalogVersion()
            );
        }
        String original =
            com.example.intelligent_cv_matching_ai_agent.util.shared.Values.text(job.get("original_content"));
        if (original.isBlank())
            original = com.example.intelligent_cv_matching_ai_agent.util.shared.Values.text(job.get("raw_content"));
        return extract(
            "",
            com.example.intelligent_cv_matching_ai_agent.util.shared.Values.text(job.get("parsed_requirements")),
            original
        );
    }

    private static String section(
        String text) {
        String value = PATTERNS.computeIfAbsent("[:\\s]+$", Pattern::compile).matcher(fold(text)).replaceAll("");
        value = PATTERNS.computeIfAbsent("^\\d+[.)]\\s*", Pattern::compile).matcher(value).replaceAll("");
        if (has(
            value,
            ("^(?:about us|company overview|our company|who we are|top 3 reasons to "
                + "join us|benefits|why you.*love working here|what we offer|quyen "
                + "loi(?: ung vien| duoc huong)?|phuc loi|salary|muc luong)$")
        ))
            return "EXCLUDED";
        if (has(value, "^(?:preferred qualifications|nice.to.have|uu tien)$"))
            return "PREFERRED";
        if (has(
            value,
            ("^(?:your skills(?: (?:and|&) experience)?|requirements(?: and "
                + "qualifications)?|qualifications|technical skills|candidate "
                + "requirements|required skills|what you need|what you will need|what "
                + "you'll need|yeu cau(?: ung vien| cong viec)?|skills and " + "experience|must have)$")
        ))
            return "REQUIREMENTS";
        if (has(
            value,
            ("^(?:key responsibilities|the job|responsibilities|your "
                + "responsibilities|duties|what you will do|what you'll do|job "
                + "description|mo ta(?: cong viec)?|trach nhiem|nhiem vu)$")
        ))
            return "RESPONSIBILITIES";
        return null;
    }

    private static void collect(
        String html,
        String initial,
        Map<String, Requirement> rows) {
        if (html == null || html.isBlank())
            return;
        String bounded = html.substring(0, Math.min(html.length(), 100_000));
        if (!bounded.contains("<")) {
            bounded = bounded.replaceAll(
                ("(?i)(Your Skills and Experience|Why You.ll Love Working "
                    + "Here|Requirements:|Qualifications:|Responsibilities:|Yêu cầu ứng " + "viên|Quyền lợi)"),
                "\n$1\n"
            );
        }
        var document = Jsoup.parseBodyFragment(bounded);
        document.select("script,style,nav,footer,form,button,iframe,header").remove();
        // Retain block and line boundaries without splitting names such as Node.js or C++.
        document.select("br").append("\n");
        // Imported descriptions can mix bare text headings with HTML lists.
        var textNodes = document.getAllElements().stream().flatMap(e -> e.textNodes().stream()).toList();
        for (var node : textNodes) {
            if (node.parent() instanceof Element parent && parent.tagName().matches("h[1-5]"))
                continue;
            String[] lines = node.getWholeText().split("[\\r\\n]+");
            if (Arrays.stream(lines).noneMatch(line -> section(line.strip()) != null))
                continue;
            Element replacement = new Element("div");
            for (String line : lines) {
                if (line.isBlank())
                    continue;
                replacement.appendElement(section(line.strip()) == null ? "p" : "h3").text(line.strip());
            }
            node.replaceWith(replacement);
        }
        String current = initial;
        if (initial.equals("UNSPECIFIED") && document.getAllElements().stream()
            .anyMatch(element -> element.text().length() < 90 && "REQUIREMENTS".equals(section(element.text())))) {
            current = "EXCLUDED";
        }
        var blocks = document.select("h1,h2,h3,h4,h5,p,li,div,section,pre");
        List<Element> elements = blocks.isEmpty() ? List.of(document.body()) : blocks;
        for (Element block : elements) {
            boolean nested = block.select("h1,h2,h3,h4,h5,p,li,div,section,pre").size() > 1;
            String text = nested ? block.ownText() : block.wholeText();
            if (text.isBlank())
                continue;
            String heading = section(text);
            if ((block.tagName().matches("h[1-5]") || text.length() < 90) && heading != null) {
                current = heading;
                continue;
            }

            for (String clause : text.split("[\\r\\n;]+|\\s+\\+\\s+|(?<=[.!?])\\s+(?=[A-ZÀ-Ỹ])")) {
                String original = clause.strip().replaceAll("^[•*\\-]\\s*", "");
                if (original.length() < 2 || rows.size() >= 300)
                    continue;
                String lineSection = section(original);
                if (lineSection != null && original.length() < 60 && !original.endsWith(".")) {
                    current = lineSection;
                    continue;
                }
                if (current.equals("EXCLUDED"))
                    continue;
                String key = fold(original).replaceAll("\\s+", " ");
                if (rows.containsKey(key))
                    continue;
                String f = fold(original);
                List<Skill> skills = recognize(original);
                boolean requirementContext = !current.equals("UNSPECIFIED");
                if (!requirementContext
                    && !f.matches(".*\\b(must|required|knowledge|experience|ability|yeu cau|kinh nghiem)\\b.*")
                    && (skills.isEmpty()
                        || !has(f, "\\b(build|work with|develop|design|maintain|use|xay dung|su dung)\\b")))
                    continue;
                String importance = current.equals("RESPONSIBILITIES") ? "RESPONSIBILITY" : "MANDATORY";
                if (current.equals("PREFERRED") || has(f, "preferred|nice.to.have|is a plus|optional|uu tien|loi the"))
                    importance = "PREFERRED";
                if (has(f, "willingness|willing to|san sang|or.*learn|hoac.*hoc"))
                    importance = "FLEXIBLE";
                if (has(f, "not required|no .*required|khong bat buoc|khong yeu cau"))
                    importance = "PREFERRED";
                boolean conditional = has(f, "\\bif\\b|provided that|neu |tuy thuoc");
                String relation = has(f, "\\bor\\b|\\bhoac\\b|one of|mot trong|it nhat (?:1|mot)") ? "OR" : "AND";
                boolean mixed = relation.equals("OR") && has(f, "\\band\\b|\\bva\\b") && skills.size() > 2;
                boolean ambiguousSlash = f.contains("/")
                    && (skills.size() > 1 || skills.stream().noneMatch(skill -> skill.name().contains("/")));
                boolean nestedAlternatives = relation.equals("OR") && f.contains("(") && f.contains(")")
                    && !recognize(f.substring(0, f.indexOf('('))).isEmpty();
                if (mixed || nestedAlternatives)
                    relation = "MIXED";
                else if (ambiguousSlash && relation.equals("AND"))
                    relation = "AMBIGUOUS";
                boolean constraint = has(
                    f,
                    ("years?|nam kinh nghiem|degree|bachelor|graduate|students?|tot " + "nghiep|sinh vien|bang cap")
                );
                boolean uncoveredConjunct = Arrays.stream(f.split("\\b(?:and|or|va|hoac)\\b|,"))
                    .anyMatch(part -> part.strip().length() > 5 && recognize(part).isEmpty());
                if (has(f, "\\b(intermediate|fluent|proficient|ielts|toeic|trung cap|thanh thao)\\b")) {
                    constraint = true;
                }
                if (has(f, "must not|khong duoc")) {
                    skills = List.of();
                }
                String status =
                    nestedAlternatives || ambiguousSlash || uncoveredConjunct || skills.isEmpty() || conditional
                        || mixed || constraint || has(f, "must not|khong duoc") ? "REVIEW_REQUIRED" : "RECOGNIZED";
                if (conditional)
                    importance = "CONDITIONAL";
                if (current.equals("UNSPECIFIED") && !has(f, "must|required|knowledge|experience|yeu cau|kinh nghiem"))
                    importance = "UNSPECIFIED";
                if (importance.equals("UNSPECIFIED"))
                    status = "REVIEW_REQUIRED";
                rows.put(
                    key,
                    new Requirement(
                        original,
                        current,
                        importance,
                        relation,
                        types(original, skills),
                        skills,
                        status,
                        List.of()
                    )
                );
            }
        }
    }

    private static List<String> types(
        String text,
        List<Skill> skills) {
        Set<String> result = new LinkedHashSet<>();
        skills.forEach(s -> result.add(s.category));
        String f = fold(text);
        if (has(f, "years?|experience|kinh nghiem"))
            result.add("EXPERIENCE");
        if (has(f, "degree|bachelor|graduate|students?|tot nghiep|sinh vien|bang cap"))
            result.add("EDUCATION");
        if (result.isEmpty())
            result.add("UNCLASSIFIED");
        return List.copyOf(result);
    }

    private static boolean has(
        String text,
        String regex) {
        return PATTERNS.computeIfAbsent(regex, Pattern::compile).matcher(text).find();
    }

    /** Keep the extraction contract while delegating cognitive classification to the Bloom engine. */
    public static List<Bloom> bloom(
        String original) {
        return BloomRuleEngine.classifyRequirement(original).stream()
            .map(prediction -> new Bloom(prediction.level(), prediction.evidence(), prediction.justification()))
            .toList();
    }
}
