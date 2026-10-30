package com.example.intelligent_cv_matching_ai_agent.util.shared.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.ImportJobRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.JobSkillRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import tools.jackson.databind.json.JsonMapper;

/** Bounded, deterministic source parsing; no model calls or browser execution. */
public final class JobPostingParser {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final ZoneId SOURCE_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final int MAX_HTML_CHARACTERS = 2 * 1024 * 1024;

    private static final Pattern NUMERIC_SALARY = Pattern.compile(
        "\\d+(?:[.,]\\d+)*\\s*(?:million|m|billion|thousand|k)?"
            + "(?:\\s*[-\u2013\u2014]\\s*\\d+(?:[.,]\\d+)*\\s*(?:million|m|billion|thousand|k)?)?",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SALARY_PERIOD = Pattern.compile(
        "\\b(?:month|monthly|thang|tháng|year|yearly|annual|annually|annum|nam|năm|day|daily|ngay|ngày|"
            + "hour|hourly|gio|giờ|week|weekly|tuan|tuần|quarter|quy|quý)\\b|/\\s*[hdwmy]\\b",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final List<SkillAlias> SKILLS = List.of(
        skill("Java", "java"),
        skill("JavaScript", "javascript", "java script"),
        skill("TypeScript", "typescript", "type script"),
        skill("Python", "python"),
        skill("C#", "c#", "csharp", "c sharp"),
        skill("C++", "c++"),
        skill("Go", "golang", "go programming"),
        skill("PHP", "php"),
        skill("Kotlin", "kotlin"),
        skill("Swift", "swift"),
        skill("Dart", "dart"),
        skill("SQL", "sql"),
        skill("MySQL", "mysql"),
        skill("PostgreSQL", "postgresql", "postgres"),
        skill("SQL Server", "sql server", "mssql"),
        skill("Oracle", "oracle"),
        skill("Spring Boot", "spring boot", "springboot"),
        skill("Spring", "spring", "springmvc"),
        skill("React", "react", "reactjs", "react.js"),
        skill("Angular", "angular", "angularjs"),
        skill("Vue.js", "vue.js", "vuejs"),
        skill("Node.js", "node.js", "nodejs", "node js"),
        skill(".NET", ".net", "dotnet"),
        skill("ASP.NET", "asp.net"),
        skill("Laravel", "laravel"),
        skill("Flutter", "flutter"),
        skill("Android", "android"),
        skill("iOS", "ios"),
        skill("Git", "git"),
        skill("Docker", "docker"),
        skill("Kubernetes", "kubernetes", "k8s"),
        skill("AWS", "aws", "amazon web services"),
        skill("Azure", "azure"),
        skill("GCP", "gcp", "google cloud platform"),
        skill("Linux", "linux"),
        skill("Redis", "redis"),
        skill("Kafka", "kafka"),
        skill("RabbitMQ", "rabbitmq", "rabbit mq"),
        skill("MongoDB", "mongodb", "mongo db"),
        skill("HTML", "html", "html5"),
        skill("CSS", "css", "css3"),
        skill("Jenkins", "jenkins"),
        skill("Terraform", "terraform"),
        skill("JUnit", "junit"),
        skill("Mockito", "mockito"),
        skill("Hibernate", "hibernate"),
        skill("Maven", "maven"),
        skill("Gradle", "gradle")
    );

    private JobPostingParser() {
    }

    public record ParsedPosting(
        ImportJobRequest job,
        String quality
    ) {
    }

    private record SkillAlias(
        String name,
        List<String> aliases
    ) {
    }

    public static Optional<ParsedPosting> parse(
        String html,
        String sourceSite,
        String url,
        String roleCategory) {
        if (html == null || html.length() > MAX_HTML_CHARACTERS)
            throw ApiException.badRequest("Source HTML is missing or exceeds 2 MiB characters");
        String source = required(sourceSite, 50, "sourceSite").toUpperCase(Locale.ROOT);
        String canonicalUrl = JobUrlDiscovery.canonicalUrl(source, url);
        String category = required(roleCategory, 100, "roleCategory");
        Document document = Jsoup.parse(html);
        List<Map<String, Object>> postings = new ArrayList<>();

        for (Element script : document.select("script[type=application/ld+json]")) {
            try {
                findPostings(JSON.readValue(script.data(), Object.class), postings, 0);
            } catch (RuntimeException ignored) {
                // An unrelated malformed JSON-LD block does not hide a valid later block.
            }
        }
        if (postings.size() > 1)
            throw ApiException.badRequest("Expected one JobPosting per supplied detail page");
        if (!postings.isEmpty())
            return Optional
                .of(new ParsedPosting(fromJson(postings.getFirst(), source, canonicalUrl, category), "json_ld"));

        return fromHtml(document, source, canonicalUrl, category);
    }

    private static ImportJobRequest fromJson(
        Map<String, Object> posting,
        String source,
        String url,
        String category) {
        String title = required(plain(textValue(posting.get("title"))), 255, "title");
        String description = required(textValue(posting.get("description")), 100000, "description");
        if (plain(description).length() < 30)
            throw ApiException.badRequest("JobPosting description must contain at least 30 characters");
        Map<String, String> sections = sections(Jsoup.parseBodyFragment(description).body());
        String requirements = firstNonblank(joinedText(posting.get("qualifications")), sections.get("requirements"));
        String experience = joinedText(posting.get("experienceRequirements"));
        String education = joinedText(posting.get("educationRequirements"));
        requirements = combine(requirements, experience, education);
        String responsibilities =
            firstNonblank(joinedText(posting.get("responsibilities")), sections.get("responsibilities"));
        String benefits = firstNonblank(joinedText(posting.get("jobBenefits")), sections.get("benefits"));
        String location = locations(posting.get("jobLocation"));
        if (location.isBlank() && "TELECOMMUTE".equals(textValue(posting.get("jobLocationType"))))
            location = "Remote";

        return JobNormalizer.normalize(
            new ImportJobRequest(
                source,
                url,
                title,
                optionalText(asMap(posting.get("hiringOrganization")).get("name"), 255),
                location,
                salary(posting),
                category,
                description,
                extractSkills(joinedText(posting.get("skills")), requirements, description),
                employment(posting.get("employmentType")),
                requirements,
                responsibilities,
                benefits,
                date(posting.get("datePosted"), false),
                date(posting.get("validThrough"), true)
            )
        );
    }

    private static Optional<ParsedPosting> fromHtml(
        Document document,
        String source,
        String url,
        String category) {
        if (!JobUrlDiscovery.isDetailUrl(source, url))
            return Optional.empty();
        Element scope = document.selectFirst(
            source.equals("TOPCV")
                ? ".job-detail, #job-detail, main, [itemtype$=JobPosting]"
                : ".job-details, .job-detail, .job-show, #job-detail, main, [itemtype$=JobPosting]"
        );
        if (scope == null)
            return Optional.empty();
        String titleSelector = source.equals("TOPCV")
            ? "h1.job-detail__info--title, h1.job-title, h1[itemprop=title]"
            : "h1.job-title, h1.ipt-xl, h1[itemprop=title]";
        var titles = scope.select(titleSelector);
        if (titles.isEmpty())
            titles = scope.select("h1");
        if (titles.size() != 1)
            return Optional.empty();
        String title = required(titles.getFirst().text(), 255, "title");
        Map<String, String> sections = sections(scope);
        String description = sections.get("description");
        if (description == null || plain(description).length() < 30) {
            Element descriptionElement = scope
                .selectFirst("[itemprop=description], #job-description, .job-description__content, .job_description");
            if (descriptionElement != null)
                description = cleanHtml(descriptionElement);
        }
        if (description == null || plain(description).length() < 30)
            return Optional.empty();

        String requirements = sections.get("requirements");
        String responsibilities = sections.get("responsibilities");
        String benefits = sections.get("benefits");
        String content = combineMissing(description, requirements, responsibilities, benefits);
        if (content.length() > 100000)
            throw ApiException.badRequest("JobPosting description exceeds 100000 characters");
        String company = htmlValue(
            scope,
            source.equals("TOPCV")
                ? "[itemprop=hiringOrganization] [itemprop=name], .job-detail__company--name, .company-name"
                : "[itemprop=hiringOrganization] [itemprop=name], .company-name, .job-header__company-name"
        );
        String location = firstNonblank(
            htmlValue(scope, "[itemprop=jobLocation], [data-field=location], .job-location, .job-address"),
            labeledValue(scope, "dia diem", "location", "noi lam viec", "dia diem lam viec")
        );
        String salary = firstNonblank(
            htmlValue(scope, "[itemprop=baseSalary], [data-field=salary], .job-salary"),
            labeledValue(scope, "muc luong", "salary", "luong")
        );
        String employment = firstNonblank(
            htmlValue(scope, "[itemprop=employmentType], [data-field=employment-type]"),
            labeledValue(scope, "hinh thuc lam viec", "loai cong viec", "employment type", "job type")
        );
        String posted = htmlValue(scope, "[itemprop=datePosted], time[data-field=posted-at]");
        String expires = firstNonblank(
            htmlValue(scope, "[itemprop=validThrough], [data-field=valid-through]"),
            labeledValue(scope, "han nop ho so", "han ung tuyen", "deadline")
        );
        ImportJobRequest job = JobNormalizer.normalize(
            new ImportJobRequest(
                source,
                url,
                title,
                optionalText(company, 255),
                optionalText(location, 255),
                optionalText(salary, 255),
                category,
                content,
                extractSkills("", requirements, content),
                employment,
                requirements,
                responsibilities,
                benefits,
                htmlDate(posted, false),
                htmlDate(expires, true)
            )
        );

        return Optional.of(new ParsedPosting(job, "html"));
    }

    private static Map<String, String> sections(
        Element scope) {
        Map<String, String> result = new LinkedHashMap<>();
        int checked = 0;
        for (Element heading : scope.select("h2,h3,h4")) {
            if (++checked > 100)
                break;
            if (Set.of("related jobs", "similar jobs", "more jobs for you", "viec lam lien quan", "viec lien quan")
                .contains(JobNormalizer.fold(heading.text()).replaceAll("[:\\s]+$", "")))
                break;
            String kind = sectionKind(heading.text());
            if (kind == null)
                continue;
            String html = afterHeading(heading);
            if (html == null || plain(html).isBlank())
                continue;
            result.merge(kind, html, JobPostingParser::combine);
        }

        return result;
    }

    private static String sectionKind(
        String heading) {
        String text = JobNormalizer.fold(heading).replaceAll("[:\\s]+$", "");
        if (Set.of("job description", "mo ta cong viec", "mo ta").contains(text))
            return "description";
        if (Set.of(
            "your skills and experience",
            "yeu cau ung vien",
            "yeu cau cong viec",
            "yeu cau",
            "qualifications",
            "requirements",
            "skills and experience"
        ).contains(text))
            return "requirements";
        if (Set.of("responsibilities", "what you will do", "trach nhiem", "nhiem vu").contains(text))
            return "responsibilities";
        if (Set.of(
            "why you'll love working here",
            "benefits",
            "quyen loi",
            "quyen loi ung vien",
            "quyen loi duoc huong",
            "what we offer"
        ).contains(text))
            return "benefits";

        return null;
    }

    private static String afterHeading(
        Element heading) {
        StringBuilder html = new StringBuilder();
        int level = heading.tagName().charAt(1) - '0';
        for (Element sibling = heading.nextElementSibling(); sibling != null; sibling = sibling.nextElementSibling()) {
            Element nextHeading = sibling.is("h1,h2,h3,h4") ? sibling : sibling.selectFirst("h1,h2,h3,h4");
            if (nextHeading != null && nextHeading.tagName().charAt(1) - '0' <= level)
                break;
            html.append(cleanHtml(sibling));
            if (html.length() > 100000)
                throw ApiException.badRequest("JobPosting section exceeds 100000 characters");
        }
        if (html.isEmpty()) {
            Element parent = heading.parent();
            if (parent != null && parent.is("section,div") && parent.select("h1,h2,h3,h4").size() == 1) {
                Element copy = parent.clone();
                copy.select("h1,h2,h3,h4").remove();
                html.append(cleanHtml(copy));
            }
        }

        return html.isEmpty() ? null : html.toString();
    }

    private static String cleanHtml(
        Element element) {
        Element copy = element.clone();
        copy.select("script,style,nav,footer,form,button,iframe").remove();
        return copy.outerHtml();
    }

    private static String htmlValue(
        Element scope,
        String selector) {
        Element element = scope.selectFirst(selector);
        if (element == null)
            return "";
        for (String attribute : List.of("content", "datetime", "data-value")) {
            if (element.hasAttr(attribute))
                return element.attr(attribute).strip();
        }

        return element.text().strip();
    }

    private static String labeledValue(
        Element scope,
        String... labels) {
        Set<String> expected = Set.of(labels);
        int checked = 0;
        for (Element item : scope.select(
            ".job-detail__info--section, .job-overview__item, .job-detail__information-detail--item,"
                + " [data-field], dl > div, tr"
        )) {
            if (++checked > 100)
                break;
            Element label = item.selectFirst(".label,.job-detail__info--section-title,.title,dt,th");
            if (label == null)
                continue;
            if (!expected.contains(JobNormalizer.fold(label.text()).replaceAll("[:\\s]+$", "")))
                continue;
            Element value = item.selectFirst(".job-detail__info--section-content,.value,dd,td");
            if (value != null && value != label)
                return value.text().strip();
        }

        return "";
    }

    private static List<JobSkillRequest> extractSkills(
        String explicit,
        String requirements,
        String description) {
        Map<String, Boolean> found = new LinkedHashMap<>();
        scanSkills(explicit, true, found);
        scanSkills(requirements, true, found);
        scanSkills(description, false, found);

        return found.entrySet().stream().map(entry -> new JobSkillRequest(null, entry.getKey(), entry.getValue()))
            .toList();
    }

    private static void scanSkills(
        String html,
        boolean defaultRequired,
        Map<String, Boolean> found) {
        if (html == null || html.isBlank())
            return;
        Document document = Jsoup.parseBodyFragment(html);
        var blocks = document.select("h1,h2,h3,h4,h5,p,li,pre,div,section");
        List<Element> elements = blocks.isEmpty() ? List.of(document.body()) : blocks;
        boolean optionalSection = false;
        for (Element block : elements) {
            boolean hasNestedBlocks = block.select("h1,h2,h3,h4,h5,p,li,pre,div,section").size() > 1;
            if (hasNestedBlocks && block.ownText().isBlank())
                continue;
            String folded = JobNormalizer.fold(hasNestedBlocks ? block.ownText() : block.text());
            if (block.is("h1,h2,h3,h4,h5")) {
                if (optionalHint(folded))
                    optionalSection = true;
                else if (requiredHint(folded) || sectionKind(block.text()) != null)
                    optionalSection = false;
            }
            for (String sentence : folded.split("(?<=[.!?])\\s+|[\\r\\n;]+")) {
                boolean required =
                    !optionalSection && !optionalHint(sentence) && (defaultRequired || requiredHint(sentence));
                for (SkillAlias skill : SKILLS) {
                    boolean match = skill.aliases().stream().anyMatch(
                        alias -> Pattern.compile(
                            "(?<![\\p{L}\\p{N}_])" + Pattern.quote(alias) + "(?![\\p{L}\\p{N}_])",
                            Pattern.CASE_INSENSITIVE
                        ).matcher(sentence).find()
                    );
                    if (match)
                        found.merge(skill.name(), required, Boolean::logicalOr);
                }
            }
        }
    }

    private static boolean optionalHint(
        String text) {
        return text.contains("nice to have") || text.contains("nice-to-have") || text.contains("preferred")
            || text.contains("optional") || text.contains("is a plus") || text.contains("not required")
            || text.contains("uu tien") || text.contains("loi the") || text.contains("khong bat buoc");
    }

    private static boolean requiredHint(
        String text) {
        return text.contains("must") || text.contains("required") || text.contains("requirements")
            || text.contains("experience") || text.contains("proficient") || text.contains("knowledge")
            || text.contains("familiar") || text.contains("yeu cau") || text.contains("kinh nghiem")
            || text.contains("thanh thao") || text.contains("bat buoc");
    }

    private static SkillAlias skill(
        String name,
        String... aliases) {
        return new SkillAlias(name, List.of(aliases));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(
        Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static void findPostings(
        Object value,
        List<Map<String, Object>> postings,
        int depth) {
        if (depth > 10)
            return;
        if (value instanceof List<?> list) {
            for (Object item : list)
                findPostings(item, postings, depth + 1);
        } else if (value instanceof Map<?, ?>) {
            Map<String, Object> object = asMap(value);
            Object type = object.get("@type");
            List<?> types = type instanceof List<?> list ? list : Collections.singletonList(type);
            if (types.stream().anyMatch(
                item -> Set.of("JobPosting", "https://schema.org/JobPosting", "http://schema.org/JobPosting")
                    .contains(textValue(item))
            ))
                postings.add(object);
            if (object.containsKey("@graph"))
                findPostings(object.get("@graph"), postings, depth + 1);
        }
    }

    private static String locations(
        Object value) {
        List<?> entries = value instanceof List<?> list ? list : Collections.singletonList(value);
        List<String> result = new ArrayList<>();
        for (Object entry : entries) {
            Map<String, Object> place = asMap(entry);
            Map<String, Object> address = asMap(place.get("address"));
            List<String> parts = new ArrayList<>();
            for (String key : List.of("addressLocality", "addressRegion", "addressCountry")) {
                Object part = address.get(key);
                String name = optionalText(part instanceof Map<?, ?> ? asMap(part).get("name") : part, 120);
                if (!name.isBlank() && !parts.contains(name))
                    parts.add(name);
            }
            String location = parts.isEmpty() ? optionalText(place.get("name"), 255) : String.join(", ", parts);
            if (!location.isBlank() && !result.contains(location))
                result.add(location);
        }

        return optionalText(String.join("; ", result), 255);
    }

    private static String salary(
        Map<String, Object> posting) {
        Object base = posting.get("baseSalary");
        if (base instanceof String || base instanceof Number)
            return optionalText(base, 255);
        Map<String, Object> amount = asMap(base);
        Object value = amount.get("value");
        Map<String, Object> range = asMap(value);
        String number = textValue(value);
        if (number.isBlank()) {
            number = textValue(range.get("value"));
            if (number.isBlank())
                number = String.join(" - ", List.of(textValue(range.get("minValue")), textValue(range.get("maxValue"))))
                    .replaceAll("^ - | - $", "");
        }
        if (number.isBlank())
            return "";
        // Some boards put display text in QuantitativeValue.value and supply a default currency.
        // Preserve explicit currencies, Vietnamese units and undisclosed salary text verbatim;
        // appending that default can turn "35tr" into a contradictory "35tr USD".
        String period = textValue(range.get("unitText"));
        String periodSuffix = period.isBlank() || SALARY_PERIOD.matcher(number).find() ? "" : " / " + period;
        if (!NUMERIC_SALARY.matcher(number.strip()).matches())
            return optionalText(number + (number.codePoints().anyMatch(Character::isDigit) ? periodSuffix : ""), 255);
        return optionalText(
            number + " " + textValue(amount.getOrDefault("currency", posting.get("salaryCurrency"))) + periodSuffix,
            255
        );
    }

    private static String employment(
        Object value) {
        List<?> values = value instanceof List<?> list ? list : Collections.singletonList(value);
        String unknown = null;
        for (Object item : values) {
            String type = JobNormalizer.employmentType(textValue(item));
            if (type != null && !type.equals("OTHER"))
                return type;
            if (type != null)
                unknown = type;
        }

        return unknown;
    }

    private static OffsetDateTime date(
        Object value,
        boolean endOfDay) {
        String date = textValue(value);
        if (date.isBlank())
            return null;
        try {
            return OffsetDateTime.parse(date);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(date).atZone(SOURCE_ZONE).toOffsetDateTime();
            } catch (DateTimeParseException ignoredAgain) {
                try {
                    LocalDate day = LocalDate.parse(date);
                    return (endOfDay ? day.plusDays(1) : day).atStartOfDay(SOURCE_ZONE).toOffsetDateTime();
                } catch (DateTimeParseException ex) {
                    throw ApiException.badRequest("JobPosting contains an invalid date");
                }
            }
        }
    }

    private static OffsetDateTime htmlDate(
        String value,
        boolean endOfDay) {
        if (value == null || value.isBlank())
            return null;
        var localDate = Pattern.compile("\\b([0-9]{2}/[0-9]{2}/[0-9]{4})\\b").matcher(value);
        if (localDate.find()) {
            try {
                LocalDate day = LocalDate.parse(
                    localDate.group(1),
                    DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT)
                );
                return (endOfDay ? day.plusDays(1) : day).atStartOfDay(SOURCE_ZONE).toOffsetDateTime();
            } catch (DateTimeParseException ex) {
                throw ApiException.badRequest("JobPosting contains an invalid date");
            }
        }

        return date(value, endOfDay);
    }

    private static String joinedText(
        Object value) {
        if (value instanceof List<?> list)
            return String
                .join("\n", list.stream().map(JobPostingParser::joinedText).filter(text -> !text.isBlank()).toList());
        if (value instanceof Map<?, ?>)
            return textValue(asMap(value).get("name"));

        return textValue(value);
    }

    private static String textValue(
        Object value) {
        return value instanceof String || value instanceof Number ? value.toString() : "";
    }

    private static String optionalText(
        Object value,
        int max) {
        String text = plain(textValue(value));
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static String required(
        String value,
        int max,
        String field) {
        String text = value == null ? "" : value.strip();
        if (text.isBlank() || text.length() > max)
            throw ApiException.badRequest("JobPosting " + field + " is missing or too long");

        return text;
    }

    private static String plain(
        String value) {
        return value == null ? "" : Jsoup.parse(value).text().strip();
    }

    private static String firstNonblank(
        String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank())
                return value;
        }

        return "";
    }

    /**
     * Appends each section only when the description does not already contain it. When the parser falls back to the
     * whole description container, that container already holds the requirement and benefit sections.
     */
    private static String combineMissing(
        String description,
        String... sections) {
        List<String> values = new ArrayList<>();
        values.add(description);
        String seen = plain(description);
        for (String section : sections) {
            String text = section == null ? "" : plain(section);
            if (text.isEmpty() || seen.contains(text)) {
                continue;
            }
            values.add(section);
            seen = seen + " " + text;
        }
        return combine(values.toArray(String[]::new));
    }

    private static String combine(
        String... values) {
        return String.join(
            "\n",
            java.util.Arrays.stream(values).filter(value -> value != null && !value.isBlank()).distinct().toList()
        );
    }
}
