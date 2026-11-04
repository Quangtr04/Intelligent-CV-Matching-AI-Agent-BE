package com.example.intelligent_cv_matching_ai_agent.util.shared.resume;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative extraction from already decoded text. No model calls, inferred qualifications or generated prose.
 * Original text remains authoritative until the candidate reviews the extracted fields.
 */
public final class ResumeTextParser {

    private static final int MAX_TEXT = 200_000;

    private static final Pattern EMAIL = Pattern.compile(
        "(?i)(?<![A-Z0-9.!#$%&'*+/=?^_`{|}~-])[A-Z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@"
            + "(?:[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?\\.){1,10}[A-Z]{2,63}(?![A-Z0-9.-])"
    );

    private static final Pattern PHONE = Pattern.compile("(?<![\\p{L}\\p{N}])[+()]?\\d[\\d ().-]{6,}\\d");

    private static final Pattern URL =
        Pattern.compile("(?i)(?:https?://|www\\.)[^\\s<>]+|(?:linkedin\\.com/in/|github\\.com/)[^\\s<>]+");

    private static final String DATE_TOKEN = "(?:\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}/\\d{1,2}/(?:19|20)\\d{2}|"
        + "\\d{1,2}[/.-](?:19|20)\\d{2}|(?:19|20)\\d{2}[-/]\\d{1,2}|"
        + "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|"
        + "aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?|th[aá]ng\\s+\\d{1,2})"
        + "\\s*[,/]?\\s*(?:19|20)\\d{2}|(?:19|20)\\d{2})";

    private static final String PRESENT_TOKEN =
        "(?:present|current|now|ongoing|hi[eệ]n\\s+t[aạ]i|[đd][eế]n\\s+nay|nay)";

    private static final Pattern DATE_RANGE = Pattern.compile(
        "(?<![\\p{L}\\p{N}])(" + DATE_TOKEN + ")\\s*(?:[-–—~]|to|until|[đd][eế]n)\\s*(" + DATE_TOKEN + "|"
            + PRESENT_TOKEN + ")(?![\\p{L}\\p{N}])",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern SINGLE_DATE = Pattern.compile(
        "(?<![\\p{L}\\p{N}])(" + DATE_TOKEN + ")(?![\\p{L}\\p{N}])",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern ROLE = Pattern.compile(
        "\\b(?:developer|engineer|designer|analyst|consultant|manager|specialist|architect|tester|"
            + "intern|accountant|recruiter|administrator|technician|scientist|officer|executive|"
            + "lap trinh vien|ky su|chuyen vien|thuc tap sinh|nhan vien|truong nhom|giam doc|ke toan)\\b"
    );

    private static final Pattern SCHOOL =
        Pattern.compile("\\b(?:university|college|institute|academy|high school|truong|dai hoc|cao dang|hoc vien)\\b");

    private static final Pattern COMPANY =
        Pattern.compile("\\b(?:company|corporation|corp|inc|ltd|limited|llc|jsc|co\\.|cong ty|cty|tap doan)\\b");

    private static final Pattern DEGREE = Pattern
        .compile("\\b(?:bachelor|master|phd|doctor|b\\.sc|m\\.sc|bsc|msc|cu nhan|thac si|tien si|bang ky su)\\b");

    private static final Map<String, Section> HEADINGS = headings();

    private ResumeTextParser() {
    }

    public static Map<String, Object> parse(
        String rawText) {
        String text = rawText == null ? "" : Normalizer.normalize(rawText, Normalizer.Form.NFC);
        text = limit(text.replace("\u0000", "").replace('\u00a0', ' '), MAX_TEXT);
        Map<String, Object> result = new LinkedHashMap<>();
        if (text.isBlank()) {
            return result;
        }

        List<String> header = new ArrayList<>();
        Map<Section, List<String>> sections = new LinkedHashMap<>();
        Section section = Section.HEADER;
        for (String original : text.split("\\R", -1)) {
            String line = original.strip();
            Heading heading = heading(line);
            if (section == Section.REFERENCES && heading != null && heading.section() == Section.HEADER) {
                continue;
            }
            // Technologies inside a project describe that project, rather than starting a global skills section.
            if (heading != null && heading.section() == Section.SKILLS && colon(line) >= 0
                && (section == Section.PROJECTS || section == Section.EXPERIENCE || section == Section.EDUCATION)) {
                heading = null;
            }
            if (heading != null) {
                section = heading.section();
                // Singular labels such as "Project: API" identify an entry, including repeated entries.
                if (field(section, label(line)) == null) {
                    line = heading.content();
                }
                if (line.isBlank()) {
                    continue;
                }
            } else if (unknownHeading(line)) {
                section = Section.IGNORE;
                continue;
            }
            if (section == Section.HEADER) {
                header.add(line);
            } else if (section != Section.IGNORE && section != Section.REFERENCES) {
                sections.computeIfAbsent(section, ignored -> new ArrayList<>()).add(line);
            }
        }

        contacts(String.join("\n", header), header, result);
        identity(header, result);
        List<String> summary = sections.getOrDefault(Section.SUMMARY, List.of());
        put(result, "summary", String.join("\n", summary).strip(), 20_000);

        for (Section kind : List.of(Section.EXPERIENCE, Section.EDUCATION, Section.PROJECTS, Section.CERTIFICATIONS)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (List<String> block : blocks(kind, sections.getOrDefault(kind, List.of()))) {
                Map<String, Object> row = entry(kind, block);
                if (!row.isEmpty()) {
                    rows.add(row);
                }
                if (rows.size() == 100) {
                    break;
                }
            }
            result.put(kind.key, rows);
        }

        Set<String> skills = new LinkedHashSet<>();
        for (Map.Entry<Section, List<String>> group : sections.entrySet()) {
            if (group.getKey() == Section.SUMMARY) {
                continue;
            }
            for (String line : group.getValue()) {
                if (skillEvidence(group.getKey(), line)) {
                    skills.addAll(ResumeSkillMatcher.find(line));
                }
            }
        }
        result.put("skills", skills.stream().limit(100).map(name -> Map.of("name", name)).toList());
        return result;
    }

    private static boolean skillEvidence(
        Section section,
        String line) {
        if (section == Section.SKILLS) {
            return true;
        }
        if (section != Section.EXPERIENCE && section != Section.PROJECTS && section != Section.EDUCATION) {
            return false;
        }
        Label label = label(line);
        if (label != null) {
            return Set.of(
                "technologies",
                "technology",
                "tech stack",
                "technical skills",
                "skills",
                "cong nghe",
                "ky nang",
                "ky nang chuyen mon",
                "description",
                "responsibilities",
                "achievements",
                "mo ta",
                "trach nhiem"
            ).contains(label.name());
        }
        return isBullet(line) || isSentence(line);
    }

    private static void contacts(
        String text,
        List<String> header,
        Map<String, Object> result) {
        if (text.indexOf('@') >= 0) {
            Matcher email = EMAIL.matcher(text);
            while (email.find()) {
                String candidate = email.group();
                String local = candidate.substring(0, candidate.indexOf('@'));
                if (candidate.length() <= 254 && !local.startsWith(".") && !local.endsWith(".")
                    && !local.contains("..")) {
                    put(result, "email", candidate, 500);
                    break;
                }
            }
        }
        for (String line : text.split("\\R")) {
            Matcher phone = PHONE.matcher(line);
            while (phone.find()) {
                String candidate = phone.group().strip();
                String digits = candidate.replaceAll("\\D", "");
                boolean vietnamese = digits.matches("0[35789]\\d{8}|84[35789]\\d{8}");
                boolean international =
                    (candidate.startsWith("+") || phoneLabel(line)) && digits.length() >= 8 && digits.length() <= 15;
                if ((vietnamese || international) && !DATE_RANGE.matcher(candidate).find()
                    && !candidate.matches(".*\\d{4}\\s*[-.]\\s*\\d{4}.*")) {
                    put(result, "phone", candidate, 500);
                    break;
                }
            }
            if (result.containsKey("phone")) {
                break;
            }
        }

        Map<String, Object> frontend = new LinkedHashMap<>();
        Matcher urls = URL.matcher(text);
        while (urls.find()) {
            String url = cleanUrl(urls.group());
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.matches("(?:https?://)?(?:www\\.)?linkedin\\.com/in/.+")) {
                frontend.putIfAbsent("linkedin", withScheme(url));
            } else if (lower.matches("(?:https?://)?(?:www\\.)?github\\.com/.+")) {
                frontend.putIfAbsent("github", withScheme(url));
            }
        }
        for (String line : header) {
            Label label = label(line);
            if (label != null && Set.of("website", "portfolio", "web", "trang web").contains(label.name())) {
                String url = cleanUrl(label.value());
                if (url.matches("(?i)(?:https?://)?(?:www\\.)?[A-Z0-9.-]+\\.[A-Z]{2,63}(?:/\\S*)?")) {
                    frontend.putIfAbsent("website", withScheme(url));
                }
            } else if (label == null) {
                Matcher standalone = URL.matcher(line);
                if (standalone.matches() && !fold(line).contains("github.com/")
                    && !fold(line).contains("linkedin.com/")) {
                    frontend.putIfAbsent("website", withScheme(cleanUrl(line)));
                }
            }
        }
        if (!frontend.isEmpty()) {
            result.put("frontend_fields", frontend);
        }
    }

    private static void identity(
        List<String> lines,
        Map<String, Object> result) {
        for (String line : lines) {
            Label label = label(line);
            if (label == null) {
                continue;
            }
            String field = switch (label.name()) {
                case "ho va ten", "ho ten", "full name", "name" -> "full_name";
                case "position", "job title", "target role", "chuc danh", "vi tri", "vi tri ung tuyen" -> "position";
                case "location", "address", "city", "dia chi", "dia diem", "thanh pho" -> "location";
                default -> null;
            };
            if (field != null) {
                put(result, field, label.value(), 500);
            }
        }
        int checked = 0;
        for (String original : lines) {
            if (original.isBlank()) {
                continue;
            }
            if (++checked > 12) {
                break;
            }
            for (String line : original.split("\\s*\\|\\s*")) {
                String folded = fold(line);
                if (!result.containsKey("position") && looksRole(line) && line.length() <= 100
                    && line.split("\\s+").length <= 10 && label(line) == null) {
                    put(result, "position", line, 500);
                } else if (!result.containsKey("location") && looksLocation(folded)) {
                    put(result, "location", line, 500);
                } else if (!result.containsKey("full_name") && looksName(line)) {
                    put(result, "full_name", line, 500);
                }
            }
        }
    }

    private static boolean phoneLabel(
        String line) {
        Label label = label(line);
        return label != null && Set.of("phone", "mobile", "tel", "telephone", "sdt", "dien thoai", "so dien thoai")
            .contains(label.name());
    }

    private static boolean looksName(
        String line) {
        String folded = fold(line);
        return line.length() <= 100 && line.matches("[\\p{L}][\\p{L}'’ -]+") && line.strip().split("\\s+").length >= 2
            && line.strip().split("\\s+").length <= 6 && !looksRole(line) && !SCHOOL.matcher(folded).find()
            && !COMPANY.matcher(folded).find() && !looksLocation(folded) && !HEADINGS.containsKey(folded)
            && !folded.matches(
                ".*\\b(?:curriculum|vitae|resume|profile|contact|candidate|curriculum vitae|"
                    + "thong tin|ung vien|so yeu|ly lich|experienced|passionate|professional|seeking)\\b.*"
            ) && ResumeSkillMatcher.find(line).isEmpty();
    }

    private static boolean looksRole(
        String line) {
        return ROLE.matcher(fold(line)).find();
    }

    private static boolean looksLocation(
        String folded) {
        return folded.matches(
            "(?:ho chi minh|ha noi|hanoi|da nang|danang|hcmc|tp\\.? hcm|"
                + "can tho|hai phong|hue|vietnam|viet nam|singapore|london|new york)(?:[, ].*)?"
        );
    }

    private static List<List<String>> blocks(
        Section section,
        List<String> lines) {
        List<List<String>> result = new ArrayList<>();
        List<String> current = new ArrayList<>();
        Set<String> identityKeys = new LinkedHashSet<>();
        boolean blank = false;
        boolean hasRange = false;
        boolean hasBullet = false;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) {
                blank = true;
                continue;
            }
            boolean range = DATE_RANGE.matcher(line).find();
            String key = field(section, label(line));
            boolean repeatedIdentity = key != null && identityField(section, key) && identityKeys.contains(key);
            boolean newHeader = !isBullet(line)
                && (looksRole(line) || SCHOOL.matcher(fold(line)).find() || COMPANY.matcher(fold(line)).find())
                && !line.endsWith(".") && line.length() <= 200 && hasRange && (range || nearbyRange(lines, index));
            boolean separateProject = blank && (section == Section.PROJECTS || section == Section.CERTIFICATIONS)
                && !isBullet(line) && label(line) == null;
            boolean separateCertificate = section == Section.CERTIFICATIONS && isBullet(line) && hasBullet;
            if (!current.isEmpty()
                && (repeatedIdentity || newHeader || range && hasRange || separateProject || separateCertificate)) {
                result.add(List.copyOf(current));
                current.clear();
                identityKeys.clear();
                hasRange = false;
                hasBullet = false;
            }
            current.add(line);
            hasRange |= range;
            hasBullet |= isBullet(line);
            if (key != null) {
                identityKeys.add(key);
            }
            blank = false;
            if (result.size() == 99) {
                for (int rest = index + 1; rest < lines.size(); rest++) {
                    if (!lines.get(rest).isBlank()) {
                        current.add(lines.get(rest));
                    }
                }
                break;
            }
        }
        if (!current.isEmpty()) {
            result.add(current);
        }
        return result;
    }

    private static boolean nearbyRange(
        List<String> lines,
        int index) {
        for (int next = index + 1; next < Math.min(index + 4, lines.size()); next++) {
            if (DATE_RANGE.matcher(lines.get(next)).find()) {
                return true;
            }
            if (isBullet(lines.get(next))) {
                return false;
            }
        }
        return false;
    }

    private static boolean identityField(
        Section section,
        String field) {
        return switch (section) {
            case EXPERIENCE -> field.equals("job_title") || field.equals("company_name");
            case EDUCATION -> field.equals("school_name");
            case PROJECTS -> field.equals("project_name");
            case CERTIFICATIONS -> field.equals("name");
            default -> false;
        };
    }

    private static Map<String, Object> entry(
        Section section,
        List<String> lines) {
        Map<String, Object> row = new LinkedHashMap<>();
        List<String> highlights = new ArrayList<>();
        String original = String.join("\n", lines);
        for (String line : lines) {
            Label label = label(line);
            String key = field(section, label);
            if (key != null) {
                put(row, key, label.value(), key.equals("description") ? 20_000 : 255);
            }
            Matcher dates = DATE_RANGE.matcher(line);
            if (dates.find() && !row.containsKey("display_start_date") && section != Section.CERTIFICATIONS) {
                dates(row, dates.group(1), dates.group(2), section);
            }
            String content = stripBullet(line);
            if (key == null && !content.isBlank() && !DATE_RANGE.matcher(content).matches()) {
                if (highlights.size() < 100) {
                    highlights.add(limit(content, 20_000));
                }
                if (!isBullet(line)) {
                    implicitFields(section, content, row);
                }
            }
        }
        if (section == Section.CERTIFICATIONS) {
            certification(lines, row);
            return row.containsKey("name") ? row : Map.of();
        }
        put(row, "description", original, 20_000);
        if (section == Section.EXPERIENCE || section == Section.EDUCATION) {
            row.put("highlights", highlights);
        }
        if (section == Section.PROJECTS) {
            String evidence = String.join("\n", lines.stream().filter(line -> skillEvidence(section, line)).toList());
            row.put("technologies", ResumeSkillMatcher.find(evidence).stream().limit(100).toList());
            Matcher urls = URL.matcher(original);
            if (urls.find()) {
                put(row, "project_url", withScheme(cleanUrl(urls.group())), 255);
            }
        }
        // ISO fields must remain a valid ordered pair even when the source has a typo.
        if (row.get("start_date") instanceof String start && row.get("end_date") instanceof String end
            && end.compareTo(start) < 0) {
            row.remove("start_date");
            row.remove("end_date");
        }
        return row;
    }

    private static void implicitFields(
        Section section,
        String line,
        Map<String, Object> row) {
        String withoutDates = DATE_RANGE.matcher(line).replaceAll("").replaceAll("[|–—-]+\\s*$", "").strip();
        String[] parts = withoutDates.split("\\s*\\|\\s*|\\s+@\\s+|\\s+(?:at|tại)\\s+");
        if (section == Section.EXPERIENCE) {
            for (String part : parts) {
                if (part.length() <= 150 && looksRole(part) && !isSentence(part)) {
                    row.putIfAbsent("job_title", part.strip());
                } else if (COMPANY.matcher(fold(part)).find() && part.length() <= 255 && !isSentence(part)) {
                    row.putIfAbsent("company_name", part.strip());
                }
            }
            if (parts.length == 2 && looksRole(parts[0]) && !looksRole(parts[1]) && !parts[1].isBlank()
                && parts[1].length() <= 255 && !isSentence(parts[1])) {
                row.putIfAbsent("company_name", parts[1].strip());
            }
        } else if (section == Section.EDUCATION) {
            for (String part : parts) {
                if (SCHOOL.matcher(fold(part)).find() && part.length() <= 255 && !isSentence(part)) {
                    row.putIfAbsent("school_name", part.strip());
                } else if (DEGREE.matcher(fold(part)).find() && part.length() <= 255 && !isSentence(part)) {
                    row.putIfAbsent("degree", part.strip());
                }
            }
            Matcher gpa = Pattern.compile("(?i)\\bGPA\\s*[:：]?\\s*(\\d(?:[.,]\\d+)?(?:\\s*/\\s*\\d(?:[.,]\\d+)?)?)")
                .matcher(line);
            if (gpa.find()) {
                row.putIfAbsent("gpa", gpa.group(1));
            }
        } else if (section == Section.PROJECTS && !row.containsKey("project_name") && line.length() <= 150
            && !isBullet(line) && label(line) == null && !isSentence(line) && !withoutDates.isBlank()
            && !URL.matcher(line).find()) {
            put(row, "project_name", withoutDates, 255);
        }
    }

    private static boolean isSentence(
        String line) {
        String folded = fold(line);
        return line.length() > 200 || line.endsWith(".")
            || folded.matches(
                "^(?:built|developed|implemented|"
                    + "designed|worked|managed|responsible|created|maintained|improved|led|supported|"
                    + "xay dung|phat trien|thiet ke|trien khai|tham gia|quan ly|toi |i ).*"
            );
    }

    private static void certification(
        List<String> lines,
        Map<String, Object> row) {
        for (String original : lines) {
            String line = stripBullet(original);
            Label label = label(line);
            if (label == null && !row.containsKey("name") && !DATE_RANGE.matcher(line).matches()
                && !SINGLE_DATE.matcher(line).matches() && !URL.matcher(line).matches()) {
                String title = line.split("\\s*\\|\\s*", 2)[0].strip();
                put(row, "name", title, 255);
            }
            Matcher date = SINGLE_DATE.matcher(line);
            if (date.find() && !row.containsKey("display_date")) {
                put(row, "display_date", date.group(1), 50);
                String iso = isoDate(date.group(1));
                if (iso != null) {
                    row.put("issue_date", iso);
                }
            }
            Matcher urls = URL.matcher(line);
            if (urls.find()) {
                put(row, "credential_url", withScheme(cleanUrl(urls.group())), 255);
            }
        }
    }

    private static void dates(
        Map<String, Object> row,
        String start,
        String end,
        Section section) {
        if (section != Section.PROJECTS) {
            put(row, "display_start_date", start, 50);
            put(row, "display_end_date", end, 50);
        }
        String startIso = isoDate(start);
        String endIso = isoDate(end);
        if (startIso != null) {
            row.put("start_date", startIso);
        }
        if (Pattern.matches(PRESENT_TOKEN, fold(end))) {
            if (section == Section.EXPERIENCE) {
                row.put("is_current", true);
            }
        } else if (endIso != null) {
            row.put("end_date", endIso);
        }
    }

    private static String isoDate(
        String value) {
        for (String pattern : List.of("uuuu-M-d", "d/M/uuuu")) {
            try {
                return LocalDate.parse(
                    value,
                    DateTimeFormatter.ofPattern(pattern, Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
                ).toString();
            } catch (DateTimeParseException ignored) {
                // Keep partial or invalid dates for display rather than inventing a calendar date.
            }
        }
        return null;
    }

    private static String field(
        Section section,
        Label label) {
        if (label == null) {
            return null;
        }
        String name = label.name();
        return switch (section) {
            case EXPERIENCE -> switch (name) {
                case "job title", "position", "role", "title", "chuc danh", "vi tri" -> "job_title";
                case "company", "company name", "employer", "cong ty", "ten cong ty" -> "company_name";
                case "location", "dia diem" -> "location";
                default -> null;
            };
            case EDUCATION -> switch (name) {
                case "school", "school name", "university", "truong", "ten truong" -> "school_name";
                case "degree", "bang cap", "bang", "trinh do" -> "degree";
                case "major", "field of study", "nganh", "chuyen nganh" -> "major";
                case "gpa", "diem trung binh" -> "gpa";
                default -> null;
            };
            case PROJECTS -> switch (name) {
                case "project", "project name", "name", "du an", "ten du an" -> "project_name";
                case "role", "vai tro", "vi tri" -> "role";
                default -> null;
            };
            case CERTIFICATIONS -> switch (name) {
                case "name", "certificate", "certification", "ten", "ten chung chi", "chung chi" -> "name";
                case "issuer", "issued by", "organization", "don vi cap", "to chuc cap" -> "issuer";
                default -> null;
            };
            default -> null;
        };
    }

    private static Heading heading(
        String line) {
        String content = stripBullet(line).replaceFirst("^\\d+[.)]\\s*", "");
        int colon = colon(content);
        String title = colon >= 0 ? content.substring(0, colon) : content;
        String key = fold(title).replaceAll("[\\s:：–—-]+$", "");
        Section section = HEADINGS.get(key);
        return section == null ? null : new Heading(section, colon >= 0 ? content.substring(colon + 1).strip() : "");
    }

    private static boolean unknownHeading(
        String line) {
        String folded = fold(line).replaceAll("[:：]+$", "");
        return line.length() <= 80 && folded.matches(
            "(?:other|additional|extra|personal) (?:information|details|sections?)"
                + "|(?:thong tin|noi dung|muc) (?:khac|bo sung)"
        );
    }

    private static Map<String, Section> headings() {
        Map<String, Section> result = new LinkedHashMap<>();
        addHeadings(
            result,
            Section.HEADER,
            "contact",
            "contact information",
            "contact details",
            "personal information",
            "personal details",
            "thong tin ca nhan",
            "thong tin lien he",
            "lien he"
        );
        addHeadings(
            result,
            Section.SUMMARY,
            "summary",
            "professional summary",
            "career summary",
            "profile",
            "about me",
            "about",
            "objective",
            "career objective",
            "career objectives",
            "gioi thieu",
            "gioi thieu ban than",
            "tom tat",
            "tom tat chuyen mon",
            "muc tieu",
            "muc tieu nghe nghiep"
        );
        addHeadings(
            result,
            Section.EXPERIENCE,
            "experience",
            "work experience",
            "professional experience",
            "employment",
            "employment history",
            "work history",
            "relevant experience",
            "kinh nghiem",
            "kinh nghiem lam viec",
            "qua trinh lam viec",
            "qua trinh cong tac"
        );
        addHeadings(
            result,
            Section.EDUCATION,
            "education",
            "academic background",
            "educational background",
            "academic qualifications",
            "hoc van",
            "trinh do hoc van",
            "dao tao",
            "qua trinh hoc tap"
        );
        addHeadings(
            result,
            Section.PROJECTS,
            "projects",
            "project",
            "personal projects",
            "selected projects",
            "academic projects",
            "relevant projects",
            "du an",
            "du an ca nhan",
            "du an tieu bieu"
        );
        addHeadings(
            result,
            Section.CERTIFICATIONS,
            "certifications",
            "certificates",
            "certification",
            "licenses",
            "licenses and certifications",
            "chung chi",
            "chung nhan",
            "bang cap va chung chi"
        );
        addHeadings(
            result,
            Section.SKILLS,
            "skills",
            "technical skills",
            "professional skills",
            "core skills",
            "core competencies",
            "technologies",
            "skill set",
            "ky nang",
            "ky nang chuyen mon",
            "ky nang ky thuat",
            "nang luc",
            "cong nghe"
        );
        addHeadings(
            result,
            Section.IGNORE,
            "languages",
            "language",
            "foreign languages",
            "interests",
            "hobbies",
            "awards",
            "achievements",
            "publications",
            "research",
            "activities",
            "volunteering",
            "extracurricular activities",
            "strengths",
            "soft skills",
            "so thich",
            "ngoai ngu",
            "ngon ngu",
            "giai thuong",
            "thanh tich",
            "an pham",
            "nghien cuu",
            "hoat dong",
            "hoat dong ngoai khoa",
            "tinh nguyen",
            "ky nang mem"
        );
        addHeadings(result, Section.REFERENCES, "references", "reference", "nguoi tham chieu", "tham chieu");
        return Map.copyOf(result);
    }

    private static void addHeadings(
        Map<String, Section> target,
        Section section,
        String... names) {
        for (String name : names) {
            target.put(name, section);
        }
    }

    private static Label label(
        String line) {
        String text = stripBullet(line);
        int colon = colon(text);
        if (colon < 1 || colon > 50 || text.substring(0, colon).contains("/")) {
            return null;
        }
        return new Label(fold(text.substring(0, colon)), text.substring(colon + 1).strip());
    }

    private static int colon(
        String text) {
        int normal = text.indexOf(':');
        int full = text.indexOf('：');
        return normal < 0 ? full : full < 0 ? normal : Math.min(normal, full);
    }

    private static boolean isBullet(
        String line) {
        return line.matches("^\\s*[-•●▪◦*+]\\s+.*");
    }

    private static String stripBullet(
        String line) {
        return line.replaceFirst("^\\s*[-•●▪◦*+]\\s+", "").strip();
    }

    private static String fold(
        String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").replace('đ', 'd')
            .replace('Đ', 'D').toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
    }

    private static String cleanUrl(
        String url) {
        return limit(url.replaceAll("[).,;]+$", ""), 500);
    }

    private static String withScheme(
        String url) {
        return url.matches("(?i)^https?://.*") ? url : "https://" + url;
    }

    private static void put(
        Map<String, Object> target,
        String key,
        String value,
        int max) {
        if (value != null && !value.isBlank()) {
            target.put(key, limit(value.strip(), max));
        }
    }

    private static String limit(
        String value,
        int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private enum Section {

        HEADER(""), SUMMARY("summary"), EXPERIENCE("experience"), EDUCATION("education"), PROJECTS(
            "projects"), CERTIFICATIONS("certifications"), SKILLS("skills"), IGNORE(""), REFERENCES("");

        private final String key;

        Section(
            String key) {
            this.key = key;
        }
    }

    private record Heading(
        Section section,
        String content
    ) {
    }

    private record Label(
        String name,
        String value
    ) {
    }
}
