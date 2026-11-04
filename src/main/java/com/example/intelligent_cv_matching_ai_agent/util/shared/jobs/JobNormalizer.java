package com.example.intelligent_cv_matching_ai_agent.util.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.ImportJobRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.JobSkillRequest;
import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;

import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;

/**
 * Deterministic ingestion normalization. Only explicit metadata and skills are canonicalized;
 * description text is never used to invent missing structured fields.
 */
public final class JobNormalizer {

    private static final Pattern HTML_TAG = Pattern.compile("<[a-zA-Z/!][^>]*>");

    private static final Set<String> LINE_BLOCKS = Set.of(
        "p",
        "div",
        "section",
        "article",
        "li",
        "ul",
        "ol",
        "tr",
        "table",
        "blockquote",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6"
    );

    private static final Map<String, String> LOCATION_ALIASES = Map.ofEntries(
        Map.entry("hcm", "Ho Chi Minh"),
        Map.entry("hcmc", "Ho Chi Minh"),
        Map.entry("tp hcm", "Ho Chi Minh"),
        Map.entry("tphcm", "Ho Chi Minh"),
        Map.entry("ho chi minh", "Ho Chi Minh"),
        Map.entry("ho chi minh city", "Ho Chi Minh"),
        Map.entry("tp ho chi minh", "Ho Chi Minh"),
        Map.entry("thanh pho ho chi minh", "Ho Chi Minh"),
        Map.entry("saigon", "Ho Chi Minh"),
        Map.entry("sai gon", "Ho Chi Minh"),
        Map.entry("ha noi", "Ha Noi"),
        Map.entry("hanoi", "Ha Noi"),
        Map.entry("tp ha noi", "Ha Noi"),
        Map.entry("thanh pho ha noi", "Ha Noi"),
        Map.entry("da nang", "Da Nang"),
        Map.entry("danang", "Da Nang"),
        Map.entry("tp da nang", "Da Nang"),
        Map.entry("can tho", "Can Tho"),
        Map.entry("cantho", "Can Tho"),
        Map.entry("tp can tho", "Can Tho"),
        Map.entry("hai phong", "Hai Phong"),
        Map.entry("haiphong", "Hai Phong"),
        Map.entry("tp hai phong", "Hai Phong"),
        Map.entry("hue", "Hue"),
        Map.entry("thua thien hue", "Hue"),
        Map.entry("remote", "Remote"),
        Map.entry("telecommute", "Remote"),
        Map.entry("work from home", "Remote")
    );

    private static final Set<String> VIETNAM_ALIASES = Set.of("vn", "vietnam", "viet nam");

    private static final Map<String, String> SKILL_ALIASES = Map.ofEntries(
        Map.entry("js", "JavaScript"),
        Map.entry("javascript", "JavaScript"),
        Map.entry("ts", "TypeScript"),
        Map.entry("typescript", "TypeScript"),
        Map.entry("nodejs", "Node.js"),
        Map.entry("node.js", "Node.js"),
        Map.entry("node js", "Node.js"),
        Map.entry("reactjs", "React"),
        Map.entry("react.js", "React"),
        Map.entry("react", "React"),
        Map.entry("vuejs", "Vue.js"),
        Map.entry("vue.js", "Vue.js"),
        Map.entry("vue", "Vue.js"),
        Map.entry("java", "Java"),
        Map.entry("python", "Python"),
        Map.entry("c#", "C#"),
        Map.entry("c sharp", "C#"),
        Map.entry("csharp", "C#"),
        Map.entry("c++", "C++"),
        Map.entry(".net", ".NET"),
        Map.entry("dotnet", ".NET"),
        Map.entry(".net core", ".NET Core"),
        Map.entry("dotnet core", ".NET Core"),
        Map.entry("asp.net", "ASP.NET"),
        Map.entry("postgres", "PostgreSQL"),
        Map.entry("postgresql", "PostgreSQL"),
        Map.entry("mysql", "MySQL"),
        Map.entry("sql server", "SQL Server"),
        Map.entry("mssql", "SQL Server"),
        Map.entry("spring boot", "Spring Boot"),
        Map.entry("springboot", "Spring Boot"),
        Map.entry("docker", "Docker"),
        Map.entry("kubernetes", "Kubernetes"),
        Map.entry("k8s", "Kubernetes"),
        Map.entry("aws", "AWS"),
        Map.entry("amazon web services", "AWS"),
        Map.entry("azure", "Azure"),
        Map.entry("git", "Git")
    );

    private JobNormalizer() {
    }

    public static ImportJobRequest normalize(
        ImportJobRequest input) {
        String sourceSite = plain(input.sourceSite()).toUpperCase(Locale.ROOT);
        String sourceUrl = JobUrlDiscovery.canonicalUrl(sourceSite, input.sourceUrl());
        List<JobSkillRequest> skills = new ArrayList<>();
        for (JobSkillRequest skill : input.skills() == null ? List.<JobSkillRequest>of() : input.skills()) {
            String name = plain(skill.name());
            String canonical = SKILL_ALIASES.getOrDefault(fold(name), name);
            skills.add(new JobSkillRequest(skill.skillId(), canonical, skill.required()));
        }

        return new ImportJobRequest(
            sourceSite,
            sourceUrl,
            plain(input.jobTitle()),
            optionalPlain(input.companyName()),
            normalizeLocation(input.location()),
            optionalPlain(input.salaryText()),
            plain(input.roleCategory()),
            input.content(),
            List.copyOf(skills),
            employmentType(input.employmentType()),
            optionalLines(input.requirements()),
            optionalLines(input.responsibilities()),
            optionalLines(input.benefits()),
            input.postedAt(),
            input.validThrough(),
            input.taskId()
        );
    }

    public static String contentHash(
        ImportJobRequest input) {
        ImportJobRequest normalized = normalize(input);
        StringBuilder value = new StringBuilder();
        append(value, normalized.sourceSite());
        append(value, normalized.sourceUrl());
        append(value, normalized.jobTitle());
        append(value, normalized.companyName());
        append(value, normalized.location());
        append(value, normalized.salaryText());
        append(value, normalized.roleCategory());
        append(value, plain(normalized.content()));
        List<String> skills = normalized.skills().stream().map(JobNormalizer::skillKey).distinct().sorted().toList();
        append(value, Integer.toString(skills.size()));
        for (String skill : skills) {
            append(value, skill);
        }
        append(value, normalized.employmentType());
        // Hashes compare single-line text so line breaks do not change identities of jobs imported earlier.
        append(value, optionalPlain(normalized.requirements()));
        append(value, optionalPlain(normalized.responsibilities()));
        append(value, optionalPlain(normalized.benefits()));
        append(value, instant(normalized.postedAt()));
        append(value, instant(normalized.validThrough()));
        return FlowSupport.hash(value.toString());
    }

    /**
     * Cross-source identity requires a company and identical normalized substantive content.
     * Source URLs, dates, salary and skills do not determine whether two postings describe the same job.
     */
    public static String fingerprint(
        ImportJobRequest input) {
        ImportJobRequest normalized = normalize(input);
        if (normalized.companyName() == null) {
            return null;
        }

        StringBuilder value = new StringBuilder();
        append(value, comparison(normalized.companyName()));
        append(value, comparison(normalized.jobTitle()));
        append(value, comparison(normalized.location()));
        append(value, comparison(plain(normalized.content())));
        append(value, comparison(normalized.requirements()));
        return FlowSupport.hash(value.toString());
    }

    public static String fold(
        String value) {
        String decomposed = Normalizer.normalize(plain(value), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
    }

    public static String normalizeLocation(
        String location) {
        String normalized = optionalPlain(location);
        if (normalized == null) {
            return null;
        }

        Set<String> locations = new LinkedHashSet<>();
        for (String group : normalized.split("[;|]")) {
            List<String> parts = new ArrayList<>();
            boolean knownCity = false;
            for (String part : group.split(",")) {
                String text = plain(part);
                if (text.isEmpty()) {
                    continue;
                }
                String canonical = LOCATION_ALIASES.get(locationKey(text));
                knownCity |= canonical != null && !"Remote".equals(canonical);
                parts.add(canonical == null ? text : canonical);
            }
            if (knownCity) {
                parts.removeIf(part -> VIETNAM_ALIASES.contains(fold(part)));
            }
            if (!parts.isEmpty() && parts.stream().allMatch(LOCATION_ALIASES::containsValue)) {
                locations.addAll(parts);
            } else if (!parts.isEmpty()) {
                locations.add(String.join(", ", parts));
            }
        }
        return locations.isEmpty() ? null : String.join("; ", locations);
    }

    /** A location is chosen per request; empty or an explicit all-locations label removes the filter. */
    public static String normalizeLocationFilter(
        String location) {
        String normalized = normalizeLocation(location);
        String key = fold(normalized);
        return switch (key) {
            case "", "all", "any", "all cities", "all locations", "toan quoc", "ca nuoc", "tat ca" -> null;
            default -> normalized;
        };
    }

    public static boolean matchesLocation(
        String actual,
        String requested) {
        String selected = normalizeLocationFilter(requested);
        return selected == null || fold(normalizeLocation(actual)).contains(fold(selected));
    }

    public static String employmentType(
        String value) {
        String normalized = optionalPlain(value);
        if (normalized == null) {
            return null;
        }

        String key = fold(normalized).replaceAll("[-\\s]+", "_").toUpperCase(Locale.ROOT);
        return switch (key) {
            case "FULL_TIME", "FULLTIME", "TOAN_THOI_GIAN" -> "FULL_TIME";
            case "PART_TIME", "PARTTIME", "BAN_THOI_GIAN" -> "PART_TIME";
            case "CONTRACT", "CONTRACTOR", "HOP_DONG" -> "CONTRACT";
            case "TEMPORARY", "THOI_VU" -> "TEMPORARY";
            case "INTERN", "INTERNSHIP", "THUC_TAP", "THUC_TAP_SINH" -> "INTERN";
            case "VOLUNTEER", "TINH_NGUYEN" -> "VOLUNTEER";
            case "PER_DIEM" -> "PER_DIEM";
            default -> "OTHER";
        };
    }

    private static String plain(
        String value) {
        if (value == null) {
            return "";
        }
        String text = HTML_TAG.matcher(value).find() || value.indexOf('&') >= 0 ? Jsoup.parse(value).text() : value;
        return text.replaceAll("[\\s\\p{Z}]+", " ").strip();
    }

    private static String optionalPlain(
        String value) {
        String normalized = plain(value);
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * Readable text that keeps one line per paragraph, list item, heading or line break.
     * Whitespace inside a line is collapsed like {@link #plain(String)}.
     */
    public static String lines(
        String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String text;
        if (HTML_TAG.matcher(value).find()) {
            StringBuilder out = new StringBuilder();
            Jsoup.parseBodyFragment(value).body().traverse(new NodeVisitor() {

                @Override
                public void head(
                    Node node,
                    int depth) {
                    if (node instanceof TextNode textNode) {
                        out.append(textNode.text());
                    } else if (node instanceof Element element) {
                        if (element.nameIs("br") || LINE_BLOCKS.contains(element.normalName())) {
                            out.append('\n');
                        }
                    }
                }

                @Override
                public void tail(
                    Node node,
                    int depth) {
                    if (node instanceof Element element && LINE_BLOCKS.contains(element.normalName())) {
                        out.append('\n');
                    }
                }
            });
            text = out.toString();
        } else {
            text = Jsoup.parse(value).wholeText();
        }

        List<String> result = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String normalized = line.replaceAll("[\\s\\p{Z}]+", " ").strip();
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return String.join("\n", result);
    }

    private static String optionalLines(
        String value) {
        String normalized = lines(value);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String comparison(
        String value) {
        return plain(value).toLowerCase(Locale.ROOT);
    }

    private static String locationKey(
        String value) {
        return fold(value).replace('.', ' ').replaceAll("\\s+", " ").strip();
    }

    private static String skillKey(
        JobSkillRequest skill) {
        StringBuilder value = new StringBuilder();
        append(value, skill.skillId() == null ? "" : skill.skillId().toString());
        append(value, comparison(skill.name()));
        append(value, Boolean.toString(skill.required()));
        return value.toString();
    }

    private static String instant(
        OffsetDateTime value) {
        return value == null ? "" : value.toInstant().toString();
    }

    private static void append(
        StringBuilder value,
        String field) {
        String text = field == null ? "" : field;
        value.append(text.length()).append(':').append(text);
    }
}
