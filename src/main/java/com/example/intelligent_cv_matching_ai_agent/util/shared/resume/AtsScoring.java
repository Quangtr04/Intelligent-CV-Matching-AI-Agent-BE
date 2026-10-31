package com.example.intelligent_cv_matching_ai_agent.util.shared.resume;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic ATS rubric. The same CV and job always receive the same score, so the score never depends on a
 * language model. Criteria follow public ATS checkers (Jobscan, Resume Worded, Enhancv, Rezi): keyword match, job
 * title, sections and contact details, impact, brevity and parseability.
 */
public final class AtsScoring {

    public static final String RUBRIC_VERSION = "ats-v1";

    private static final Pattern NUMBER = Pattern.compile("\\d");

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");

    private static final Pattern PHONE =
        Pattern.compile("(?<!\\d)(\\+\\d{1,3}[\\s.-]?|0)\\d{2,3}[\\s.-]?\\d{3}[\\s.-]?\\d{3,4}(?!\\d)");

    private static final Pattern LINK = Pattern
        .compile("(linkedin\\.com/|github\\.com/|gitlab\\.com/|behance\\.net/|https?://)", Pattern.CASE_INSENSITIVE);

    private static final Pattern YEARS_REQUIRED = Pattern.compile("(\\d{1,2})\\s*\\+?\\s*(nam|years?|yrs?)\\b");

    private static final Pattern BULLET_SPLIT = Pattern.compile("\\r?\\n|[•●▪■◦►✓✔]|(?<=[.;])\\s+(?=[A-ZÀ-Ỹ])");

    private static final List<String> ACTION_VERBS = List.of(
        "achieved",
        "analyzed",
        "architected",
        "automated",
        "built",
        "coordinated",
        "created",
        "delivered",
        "deployed",
        "designed",
        "developed",
        "drove",
        "engineered",
        "established",
        "improved",
        "implemented",
        "increased",
        "integrated",
        "launched",
        "led",
        "managed",
        "mentored",
        "migrated",
        "optimized",
        "owned",
        "reduced",
        "refactored",
        "resolved",
        "scaled",
        "streamlined",
        "tested",
        "trained",
        "phat trien",
        "xay dung",
        "thiet ke",
        "trien khai",
        "toi uu",
        "cai thien",
        "quan ly",
        "dan dat",
        "lanh dao",
        "tu dong hoa",
        "tich hop",
        "giam",
        "tang",
        "dat",
        "hoan thanh",
        "nghien cuu",
        "phan tich",
        "huong dan",
        "dao tao",
        "van hanh",
        "kiem thu",
        "chuyen doi",
        "nang cap",
        "khoi tao",
        "dieu phoi",
        "to chuc",
        "viet",
        "lap trinh"
    );

    private static final List<String> WEAK_PHRASES = List.of(
        "responsible for",
        "duties included",
        "helped",
        "worked on",
        "assisted",
        "involved in",
        "chiu trach nhiem",
        "tham gia",
        "ho tro",
        "phu trach"
    );

    private static final Set<String> PRONOUNS = Set.of("i", "me", "my", "tôi", "mình", "em");

    /** Canonical skill → accepted spellings. Matching is case- and accent-insensitive. */
    private static final Map<String, List<String>> HARD_SKILLS = hardSkills();

    private static final Map<String, List<String>> SOFT_SKILLS = softSkills();

    private static final Set<String> TITLE_STOPWORDS = Set.of(
        "senior",
        "junior",
        "middle",
        "mid",
        "fresher",
        "intern",
        "internship",
        "lead",
        "leader",
        "principal",
        "staff",
        "chief",
        "head",
        "sr",
        "jr",
        "thuc",
        "tap",
        "sinh",
        "vien",
        "nhan",
        "and",
        "or",
        "of",
        "for",
        "the",
        "a",
        "an",
        "in",
        "at",
        "tai",
        "va",
        "cho",
        "hoac",
        "luong",
        "upto",
        "up",
        "to",
        "usd",
        "vnd",
        "trieu",
        "remote",
        "hybrid",
        "onsite",
        "fulltime",
        "full",
        "time",
        "part",
        "ha",
        "noi",
        "ho",
        "chi",
        "minh",
        "hcm",
        "hn"
    );

    private AtsScoring() {
    }

    /** Scores a CV against one job. {@code jobSkills} rows use skill_name/normalized_skill and is_required. */
    public static Map<String, Object> score(
        Map<String, Object> resume,
        Map<String, Object> job,
        List<Map<String, Object>> jobSkills) {
        Cv cv = Cv.of(resume);
        boolean vi = !"en".equals(cv.language);
        List<Map<String, Object>> categories = new ArrayList<>();
        Messages notes = new Messages(vi);
        Map<String, Object> keywords = new LinkedHashMap<>();
        if (job != null && !job.isEmpty()) {
            categories.add(hardSkills(cv, job, jobSkills, notes, keywords));
            categories.add(roleFit(cv, job, notes));
            categories.add(softSkillsCategory(cv, job, notes, keywords));
        }
        categories.add(sections(cv, notes, job != null && !job.isEmpty() ? 20 : 30));
        categories.add(impact(cv, notes, job != null && !job.isEmpty() ? 15 : 30));
        categories.add(brevity(cv, notes, job != null && !job.isEmpty() ? 10 : 20));
        categories.add(formatting(cv, notes, job != null && !job.isEmpty() ? 5 : 20));

        double weighted = 0, weights = 0;
        for (Map<String, Object> category : categories) {
            if (!"ASSESSED".equals(category.get("status")))
                continue;
            double weight = ((Number) category.get("weight")).doubleValue();
            weighted += weight * ((Number) category.get("score")).doubleValue();
            weights += weight;
        }
        double overall = weights == 0 ? 0 : weighted / weights;

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("rubric_version", RUBRIC_VERSION);
        report.put("mode", job != null && !job.isEmpty() ? "JOB_MATCH" : "GENERAL");
        report.put("score", round(overall));
        report.put("band", band(overall));
        report.put("target_score", 75);
        report.put("categories", categories);
        report.putAll(keywords);
        report.put("strengths", notes.strengths);
        report.put("issues", notes.issues);
        report.put("suggestions", notes.suggestions);
        report.put(
            "limitations",
            List.of(
                vi
                    ? "Điểm được tính bằng luật cố định trên nội dung chữ của CV; không kiểm tra font, màu hay bố cục hiển thị."
                    : "The score uses fixed rules on the CV text; fonts, colours and visual layout are not checked."
            )
        );
        return report;
    }

    /** Scores a CV without a target job (general ATS readiness). */
    public static Map<String, Object> score(
        Map<String, Object> resume) {
        return score(resume, null, List.of());
    }

    private static Map<String, Object> hardSkills(
        Cv cv,
        Map<String, Object> job,
        List<Map<String, Object>> jobSkills,
        Messages notes,
        Map<String, Object> keywords) {
        Map<String, Boolean> wanted = new LinkedHashMap<>();
        for (Map<String, Object> row : jobSkills == null ? List.<Map<String, Object>>of() : jobSkills) {
            String name = text(row.get("skill_name"));
            if (name.isBlank())
                name = text(row.get("normalized_skill"));
            if (name.isBlank())
                continue;
            boolean required = !Boolean.FALSE.equals(row.get("is_required"));
            wanted.merge(name.strip(), required, Boolean::logicalOr);
        }
        String requirements = normalize(
            String.join(
                " ",
                text(job.get("job_title")),
                text(job.get("parsed_requirements")),
                text(job.get("parsed_responsibilities")),
                text(job.get("parsed_description")),
                wanted.isEmpty() ? text(job.get("raw_content")) : ""
            )
        );
        String requiredPart = normalize(text(job.get("parsed_requirements")) + " " + text(job.get("job_title")));
        for (Map.Entry<String, List<String>> skill : HARD_SKILLS.entrySet()) {
            if (wanted.keySet().stream().anyMatch(name -> sameSkill(name, skill.getKey())))
                continue;
            if (containsAny(requirements, skill.getValue()))
                wanted.put(skill.getKey(), requiredPart.isBlank() || containsAny(requiredPart, skill.getValue()));
        }

        List<Map<String, Object>> matched = new ArrayList<>(), missing = new ArrayList<>();
        double earned = 0, possible = 0;
        for (Map.Entry<String, Boolean> skill : wanted.entrySet()) {
            List<String> spellings = spellings(skill.getKey());
            double weight = skill.getValue() ? 1.0 : 0.5;
            possible += weight;
            boolean inContext = containsAny(cv.contextText, spellings);
            boolean inSkills = containsAny(cv.skillsText, spellings);
            if (inContext || inSkills) {
                earned += weight * (inContext ? 1.0 : 0.75);
                matched.add(
                    row(
                        "name",
                        skill.getKey(),
                        "required",
                        skill.getValue(),
                        "evidence",
                        inContext ? "EXPERIENCE" : "SKILLS_ONLY"
                    )
                );
            } else
                missing.add(row("name", skill.getKey(), "required", skill.getValue()));
        }
        missing.sort(
            (
                a,
                b) -> Boolean.compare((Boolean) b.get("required"), (Boolean) a.get("required"))
        );
        keywords.put("matched_skills", matched);
        keywords.put("missing_skills", missing);

        if (wanted.isEmpty())
            return category(
                "HARD_SKILLS",
                notes.t("Kỹ năng chuyên môn theo JD", "Hard skills from the JD"),
                35,
                null,
                List.of()
            );

        double score = 100 * earned / possible;
        List<Map<String, Object>> checks = new ArrayList<>();
        long requiredMissing = missing.stream().filter(item -> (Boolean) item.get("required")).count();
        checks.add(
            check(
                "REQUIRED_SKILLS",
                notes.t("Có kỹ năng bắt buộc", "Required skills present"),
                requiredMissing == 0 ? 1 : 0,
                notes.t(
                    requiredMissing + " kỹ năng bắt buộc chưa thấy trong CV",
                    requiredMissing + " required skills not found"
                )
            )
        );
        long skillsOnly = matched.stream().filter(item -> "SKILLS_ONLY".equals(item.get("evidence"))).count();
        checks.add(
            check(
                "SKILL_EVIDENCE",
                notes.t("Kỹ năng có bằng chứng trong kinh nghiệm/dự án", "Skills backed by experience or projects"),
                matched.isEmpty() ? 0 : 1 - (double) skillsOnly / matched.size(),
                notes.t(
                    skillsOnly + " kỹ năng chỉ nằm trong mục Kỹ năng",
                    skillsOnly + " skills only appear in the skills list"
                )
            )
        );
        if (score >= 75)
            notes.strength(
                "Khớp " + matched.size() + "/" + wanted.size() + " kỹ năng của JD",
                "Matches " + matched.size() + "/" + wanted.size() + " JD skills"
            );
        if (!missing.isEmpty()) {
            String names = String.join(", ", missing.stream().limit(6).map(item -> text(item.get("name"))).toList());
            notes.issue("Thiếu từ khoá JD: " + names, "Missing JD keywords: " + names);
            notes.suggest(
                "KEYWORDS",
                "HIGH",
                "Nếu bạn thật sự có các kỹ năng " + names
                    + ", hãy ghi đúng tên như trong JD vào mục Kỹ năng và vào một gạch đầu dòng kinh nghiệm/dự án.",
                "If you really have " + names
                    + ", write them exactly as the JD does in your skills list and in an experience or project bullet."
            );
        }
        if (skillsOnly > 0)
            notes.suggest(
                "KEYWORDS",
                "MEDIUM",
                "Một số kỹ năng chỉ được liệt kê; hãy cho thấy bạn đã dùng chúng ở đâu trong kinh nghiệm hoặc dự án.",
                "Some skills are only listed; show where you used them in experience or projects."
            );
        return category(
            "HARD_SKILLS",
            notes.t("Kỹ năng chuyên môn theo JD", "Hard skills from the JD"),
            35,
            score,
            checks
        );
    }

    private static Map<String, Object> roleFit(
        Cv cv,
        Map<String, Object> job,
        Messages notes) {
        List<Map<String, Object>> checks = new ArrayList<>();
        List<String> titleTokens = new ArrayList<>();
        for (String token : normalize(text(job.get("job_title"))).split(" "))
            if (token.length() > 1 && !TITLE_STOPWORDS.contains(token) && !token.chars().allMatch(Character::isDigit))
                titleTokens.add(token);
        String cvTitles = cv.titleText;
        double titleScore;
        if (titleTokens.isEmpty())
            titleScore = 1;
        else if (contains(cvTitles, String.join(" ", titleTokens)))
            titleScore = 1;
        else
            titleScore =
                (double) titleTokens.stream().filter(token -> contains(cvTitles, token)).count() / titleTokens.size();
        checks.add(
            check(
                "JOB_TITLE",
                notes.t("Chức danh khớp với JD", "Job title matches the JD"),
                titleScore,
                notes.t("Chức danh JD: " + text(job.get("job_title")), "JD title: " + text(job.get("job_title")))
            )
        );
        if (titleScore < 0.6)
            notes.suggest(
                "JOB_TITLE",
                "MEDIUM",
                "Đưa chức danh \"" + text(job.get("job_title")).strip()
                    + "\" (hoặc gần nhất với kinh nghiệm thật) vào tiêu đề hoặc phần tóm tắt CV.",
                "Put the title \"" + text(job.get("job_title")).strip()
                    + "\" (or the closest true title) in your CV headline or summary."
            );
        else
            notes.strength("Chức danh CV khớp với vị trí tuyển dụng", "CV title matches the job title");

        Matcher years = YEARS_REQUIRED.matcher(
            normalize(
                text(job.get("parsed_requirements")) + " " + text(job.get("parsed_seniority")) + " "
                    + text(job.get("raw_content"))
            )
        );
        if (years.find()) {
            int required = Integer.parseInt(years.group(1));
            double have = cv.yearsOfExperience;
            if (required > 0 && required <= 20) {
                double ratio = Math.min(1, have / required);
                checks.add(
                    check(
                        "EXPERIENCE_YEARS",
                        notes.t("Số năm kinh nghiệm", "Years of experience"),
                        ratio,
                        notes.t(
                            "JD yêu cầu " + required + " năm, CV thể hiện khoảng " + round(have) + " năm",
                            "JD asks for " + required + " years, CV shows about " + round(have) + " years"
                        )
                    )
                );
                if (ratio < 1)
                    notes.issue(
                        "Kinh nghiệm thể hiện (" + round(have) + " năm) thấp hơn yêu cầu " + required + " năm",
                        "Shown experience (" + round(have) + " years) is below the required " + required + " years"
                    );
            }
        }
        return category("ROLE_FIT", notes.t("Chức danh và cấp độ", "Job title and level"), 10, average(checks), checks);
    }

    private static Map<String, Object> softSkillsCategory(
        Cv cv,
        Map<String, Object> job,
        Messages notes,
        Map<String, Object> keywords) {
        String jd = normalize(
            text(job.get("parsed_requirements")) + " " + text(job.get("parsed_description")) + " "
                + text(job.get("raw_content"))
        );
        List<String> matched = new ArrayList<>(), missing = new ArrayList<>();
        for (Map.Entry<String, List<String>> skill : SOFT_SKILLS.entrySet())
            if (containsAny(jd, skill.getValue()))
                (containsAny(cv.allText, skill.getValue()) ? matched : missing).add(skill.getKey());
        keywords.put("matched_soft_skills", matched);
        keywords.put("missing_soft_skills", missing);
        String label = notes.t("Kỹ năng mềm theo JD", "Soft skills from the JD");
        if (matched.isEmpty() && missing.isEmpty())
            return category("SOFT_SKILLS", label, 5, null, List.of());
        if (!missing.isEmpty())
            notes.suggest(
                "SOFT_SKILLS",
                "LOW",
                "JD nhắc tới: " + String.join(", ", missing) + ". Hãy thể hiện bằng một ví dụ cụ thể nếu bạn có.",
                "The JD mentions: " + String.join(", ", missing) + ". Show them with a concrete example if true."
            );
        double score = (double) matched.size() / (matched.size() + missing.size());
        return category(
            "SOFT_SKILLS",
            label,
            5,
            100 * score,
            List.of(check("SOFT_SKILLS", label, score, matched.size() + "/" + (matched.size() + missing.size())))
        );
    }

    private static Map<String, Object> sections(
        Cv cv,
        Messages notes,
        double weight) {
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(presence("FULL_NAME", notes.t("Họ tên", "Full name"), !cv.fullName.isBlank(), notes));
        if (cv.hasRawText) {
            checks.add(presence("EMAIL", notes.t("Email liên hệ", "Contact email"), cv.email != null, notes));
            checks.add(presence("PHONE", notes.t("Số điện thoại", "Phone number"), cv.hasPhone, notes));
            checks.add(
                check(
                    "LINKS",
                    notes.t("LinkedIn/GitHub/portfolio", "LinkedIn/GitHub/portfolio"),
                    cv.hasLink ? 1 : 0.5,
                    ""
                )
            );
        }
        checks.add(presence("SUMMARY", notes.t("Tóm tắt bản thân", "Summary"), cv.summaryWords >= 15, notes));
        checks.add(
            presence(
                "EXPERIENCE",
                notes.t("Kinh nghiệm hoặc dự án", "Experience or projects"),
                cv.experienceCount + cv.projectCount > 0,
                notes
            )
        );
        checks.add(presence("EDUCATION", notes.t("Học vấn", "Education"), cv.educationCount > 0, notes));
        checks.add(
            check(
                "SKILLS_LIST",
                notes.t("Mục Kỹ năng (≥ 5 kỹ năng)", "Skills section (≥ 5 skills)"),
                Math.min(1, cv.skillCount / 5.0),
                cv.skillCount + ""
            )
        );
        if (cv.experienceCount > 0)
            checks.add(
                check(
                    "DATES",
                    notes.t("Kinh nghiệm có ngày bắt đầu/kết thúc", "Experience has start/end dates"),
                    (double) cv.datedExperience / cv.experienceCount,
                    cv.datedExperience + "/" + cv.experienceCount
                )
            );
        if (average(checks) >= 90)
            notes.strength("CV có đủ các mục chính mà ATS tìm kiếm", "CV has all the main sections an ATS looks for");
        if (cv.skillCount < 5)
            notes.suggest(
                "SKILLS",
                "MEDIUM",
                "Mục Kỹ năng nên có ít nhất 5 kỹ năng, mỗi kỹ năng một mục riêng (không gộp thành câu).",
                "List at least 5 skills, one per item (not merged into a sentence)."
            );
        return category(
            "SECTIONS",
            notes.t("Mục CV và thông tin liên hệ", "Sections and contact details"),
            weight,
            average(checks),
            checks
        );
    }

    private static Map<String, Object> impact(
        Cv cv,
        Messages notes,
        double weight) {
        List<String> bullets = cv.bullets;
        String label = notes.t("Tác động và thành tích", "Impact and achievements");
        if (bullets.isEmpty()) {
            notes.issue("Chưa có gạch đầu dòng mô tả công việc/dự án", "No experience or project bullets");
            notes.suggest(
                "IMPACT",
                "HIGH",
                "Mỗi kinh nghiệm/dự án nên có 2–5 gạch đầu dòng: động từ hành động + việc đã làm + kết quả đo được.",
                "Give each role or project 2–5 bullets: action verb + what you did + measurable result."
            );
            return category("IMPACT", label, weight, 0.0, List.of());
        }
        int quantified = 0, action = 0, weak = 0, pronoun = 0;
        Map<String, Integer> firstWords = new LinkedHashMap<>();
        for (String bullet : bullets) {
            String normalized = normalize(bullet).strip();
            if (NUMBER.matcher(bullet).find())
                quantified++;
            if (ACTION_VERBS.stream().anyMatch(verb -> normalized.startsWith(verb + " ") || normalized.equals(verb)))
                action++;
            if (WEAK_PHRASES.stream().anyMatch(phrase -> contains(normalized, phrase)))
                weak++;
            String[] words = normalized.split(" ");
            String lower = Normalizer.normalize(bullet, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
            for (String word : lower.split("[^\\p{L}]+"))
                if (PRONOUNS.contains(word)) {
                    pronoun++;
                    break;
                }
            if (words.length > 0 && !words[0].isBlank())
                firstWords.merge(words[0], 1, Integer::sum);
        }
        int n = bullets.size();
        List<Map<String, Object>> checks = new ArrayList<>();
        double quantifiedRatio = (double) quantified / n;
        checks.add(
            check(
                "QUANTIFIED",
                notes.t("Gạch đầu dòng có số liệu", "Bullets with numbers"),
                Math.min(1, quantifiedRatio / 0.4),
                quantified + "/" + n
            )
        );
        checks.add(
            check(
                "ACTION_VERBS",
                notes.t("Bắt đầu bằng động từ hành động", "Start with an action verb"),
                Math.min(1, ((double) action / n) / 0.7),
                action + "/" + n
            )
        );
        checks.add(
            check(
                "WEAK_PHRASES",
                notes.t("Tránh cụm từ yếu", "Avoid weak phrases"),
                1 - (double) weak / n,
                weak + "/" + n
            )
        );
        checks.add(
            check(
                "PRONOUNS",
                notes.t("Không dùng đại từ nhân xưng", "No personal pronouns"),
                1 - (double) pronoun / n,
                pronoun + "/" + n
            )
        );
        int repeated = firstWords.values().stream().mapToInt(count -> Math.max(0, count - 2)).sum();
        checks.add(
            check(
                "REPETITION",
                notes.t("Không lặp từ mở đầu", "Varied opening words"),
                1 - Math.min(1, (double) repeated / n),
                repeated + ""
            )
        );

        if (quantifiedRatio >= 0.4)
            notes.strength(
                quantified + "/" + n + " gạch đầu dòng có số liệu cụ thể",
                quantified + "/" + n + " bullets are quantified"
            );
        else
            notes.suggest(
                "IMPACT",
                "HIGH",
                "Chỉ " + quantified + "/" + n
                    + " gạch đầu dòng có số liệu. Thêm con số thật (%, thời gian, số người dùng, số tính năng) vào ít nhất 40%.",
                "Only " + quantified + "/" + n
                    + " bullets have numbers. Add real figures (%, time, users, features) to at least 40% of them."
            );
        if (weak > 0)
            notes.suggest(
                "IMPACT",
                "MEDIUM",
                "Thay \"chịu trách nhiệm/tham gia/hỗ trợ\" bằng động từ mạnh như \"xây dựng\", \"tối ưu\", \"triển khai\".",
                "Replace \"responsible for/helped/worked on\" with strong verbs such as \"built\", \"optimized\", \"deployed\"."
            );
        if (pronoun > 0)
            notes.suggest(
                "IMPACT",
                "LOW",
                "Bỏ các đại từ \"tôi/em/mình\" trong gạch đầu dòng.",
                "Remove \"I/my/me\" from bullets."
            );
        return category("IMPACT", label, weight, average(checks), checks);
    }

    private static Map<String, Object> brevity(
        Cv cv,
        Messages notes,
        double weight) {
        List<Map<String, Object>> checks = new ArrayList<>();
        int words = cv.wordCount;
        double lengthScore = words < 150
            ? words / 150.0 * 0.5
            : words < 300
                ? 0.5 + (words - 150) / 300.0
                : words <= 1000 ? 1 : Math.max(0.3, 1 - (words - 1000) / 1000.0);
        checks.add(
            check(
                "WORD_COUNT",
                notes.t("Độ dài CV (300–1000 từ)", "CV length (300–1000 words)"),
                lengthScore,
                words + ""
            )
        );
        if (!cv.bullets.isEmpty()) {
            long good = cv.bullets.stream().map(bullet -> words(normalize(bullet)))
                .filter(count -> count >= 6 && count <= 35).count();
            checks.add(
                check(
                    "BULLET_LENGTH",
                    notes.t("Gạch đầu dòng dài 6–35 từ", "Bullets of 6–35 words"),
                    (double) good / cv.bullets.size(),
                    good + "/" + cv.bullets.size()
                )
            );
        }
        if (cv.summaryWords > 0) {
            double summary = cv.summaryWords <= 90 ? 1 : Math.max(0, 1 - (cv.summaryWords - 90) / 90.0);
            checks.add(
                check(
                    "SUMMARY_LENGTH",
                    notes.t("Tóm tắt ngắn gọn (≤ 90 từ)", "Concise summary (≤ 90 words)"),
                    summary,
                    cv.summaryWords + ""
                )
            );
        }
        if (words < 300)
            notes.suggest(
                "LENGTH",
                "MEDIUM",
                "CV mới có khoảng " + words + " từ; nên có 300–1000 từ để ATS có đủ nội dung đối chiếu.",
                "The CV has about " + words + " words; 300–1000 gives an ATS enough content to match."
            );
        else if (words > 1000)
            notes.suggest(
                "LENGTH",
                "MEDIUM",
                "CV khá dài (" + words + " từ); hãy rút gọn còn những gì liên quan nhất tới vị trí.",
                "The CV is long (" + words + " words); keep what matters most for the role."
            );
        return category(
            "BREVITY",
            notes.t("Độ dài và ngắn gọn", "Length and brevity"),
            weight,
            average(checks),
            checks
        );
    }

    private static Map<String, Object> formatting(
        Cv cv,
        Messages notes,
        double weight) {
        List<Map<String, Object>> checks = new ArrayList<>();
        if ("UPLOAD".equals(cv.source)) {
            String type = (cv.mimeType + " " + cv.fileName).toLowerCase(Locale.ROOT);
            boolean friendly = type.contains("pdf") || type.contains("word") || type.contains(".doc");
            checks.add(
                check("FILE_TYPE", notes.t("Định dạng tệp PDF/DOCX", "PDF/DOCX file"), friendly ? 1 : 0.3, cv.mimeType)
            );
            double parsed = Math.min(1, cv.rawWords / 200.0);
            checks.add(
                check(
                    "PARSE_RATE",
                    notes.t("ATS đọc được chữ trong tệp", "ATS can read the file text"),
                    parsed,
                    cv.rawWords + notes.t(" từ đọc được", " words extracted")
                )
            );
            if (parsed < 0.5) {
                notes.issue(
                    "Tệp CV gần như không đọc được chữ (có thể là ảnh scan)",
                    "Little text could be read (possibly a scanned image)"
                );
                notes.suggest(
                    "FORMAT",
                    "HIGH",
                    "Xuất CV từ Word/Google Docs ra PDF dạng chữ, tránh CV dạng ảnh hoặc chữ nằm trong hình.",
                    "Export the CV from Word/Google Docs as a text PDF; avoid image CVs or text inside graphics."
                );
            }
        } else
            checks.add(
                check("TEMPLATE", notes.t("Tạo bằng mẫu CV của hệ thống", "Built with a system template"), 1, cv.source)
            );
        if (cv.email != null) {
            String local = cv.email.substring(0, cv.email.indexOf('@'));
            long digits = local.chars().filter(Character::isDigit).count();
            checks.add(
                check(
                    "PROFESSIONAL_EMAIL",
                    notes.t("Email chuyên nghiệp", "Professional email"),
                    digits > 4 ? 0.5 : 1,
                    cv.email
                )
            );
        }
        if (!cv.dates.isEmpty()) {
            long iso = cv.dates.stream().filter(date -> date.matches("\\d{4}-\\d{2}(-\\d{2})?")).count();
            checks.add(
                check(
                    "DATE_FORMAT",
                    notes.t("Định dạng ngày thống nhất", "Consistent date format"),
                    iso == 0 || iso == cv.dates.size() ? 1 : 0.5,
                    iso + "/" + cv.dates.size()
                )
            );
        }
        return category(
            "FORMATTING",
            notes.t("Định dạng và khả năng đọc", "Format and parseability"),
            weight,
            average(checks),
            checks
        );
    }

    private static final class Cv {

        String language = "vi", source = "", mimeType = "", fileName = "", fullName = "", email;

        boolean hasRawText, hasPhone, hasLink;

        int summaryWords, experienceCount, projectCount, educationCount, skillCount, datedExperience, wordCount,
            rawWords;

        double yearsOfExperience;

        String contextText = "", skillsText = "", titleText = "", allText = "";

        List<String> bullets = new ArrayList<>(), dates = new ArrayList<>();

        static Cv of(
            Map<String, Object> resume) {
            Cv cv = new Cv();
            cv.language = "en".equalsIgnoreCase(text(resume.get("language"))) ? "en" : "vi";
            cv.source = text(resume.get("source"));
            cv.mimeType = text(resume.get("mime_type"));
            cv.fileName = text(resume.get("file_name"));
            String raw = text(resume.get("raw_content"));
            Map<String, Object> data = map(resume.get("structured_data"));
            cv.fullName = text(data.get("full_name"));
            String summary = text(data.get("summary"));
            cv.summaryWords = words(summary);

            List<String> context = new ArrayList<>(List.of(summary));
            List<String> titles = new ArrayList<>(List.of(text(resume.get("title")), summary));
            LocalDate today = LocalDate.now();
            long months = 0;
            for (Object item : list(data.get("experience"))) {
                Map<String, Object> row = map(item);
                cv.experienceCount++;
                titles.add(text(row.get("job_title")));
                context.add(text(row.get("job_title")));
                String description = text(row.get("description"));
                context.add(description);
                cv.bullets.addAll(bullets(description));
                LocalDate start = date(row.get("start_date"));
                LocalDate end = Boolean.TRUE.equals(row.get("is_current")) ? today : date(row.get("end_date"));
                if (start != null && (end != null || Boolean.TRUE.equals(row.get("is_current"))))
                    cv.datedExperience++;
                if (start != null && end != null && !end.isBefore(start))
                    months += ChronoUnit.MONTHS.between(start, end);
                addDate(cv, row.get("start_date"));
                addDate(cv, row.get("end_date"));
            }
            cv.yearsOfExperience = months / 12.0;
            for (Object item : list(data.get("projects"))) {
                Map<String, Object> row = map(item);
                cv.projectCount++;
                String description = text(row.get("description"));
                context.add(text(row.get("project_name")) + " " + text(row.get("role")) + " " + description);
                context.add(String.join(" ", list(row.get("technologies")).stream().map(AtsScoring::text).toList()));
                cv.bullets.addAll(bullets(description));
            }
            for (Object item : list(data.get("education"))) {
                cv.educationCount++;
                context.add(text(map(item).get("major")) + " " + text(map(item).get("description")));
            }
            for (Object item : list(data.get("certifications")))
                context.add(text(map(item).get("name")));
            List<String> skills = new ArrayList<>();
            for (Object item : list(data.get("skills"))) {
                String name = item instanceof Map<?, ?> ? text(map(item).get("name")) : text(item);
                if (!name.isBlank())
                    skills.add(name);
            }
            cv.skillCount = skills.size();

            boolean structured =
                !summary.isBlank() || cv.experienceCount + cv.projectCount + cv.educationCount + cv.skillCount > 0;
            if (!structured) {
                context.add(raw);
                cv.bullets.addAll(bullets(raw).stream().filter(line -> words(line) >= 4).toList());
            }
            cv.contextText = normalize(String.join(" \n ", context));
            cv.skillsText = normalize(String.join(" , ", skills));
            cv.titleText = normalize(String.join(" \n ", titles));
            String visible = String.join(" ", context) + " " + String.join(" ", skills);
            cv.wordCount = structured ? words(visible) : words(raw);
            cv.rawWords = words(raw);
            cv.hasRawText = !raw.isBlank();
            String contact = raw + " " + visible;
            Matcher email = EMAIL.matcher(contact);
            cv.email = email.find() ? email.group() : null;
            cv.hasPhone = PHONE.matcher(raw).find();
            cv.hasLink = LINK.matcher(contact).find();
            cv.allText = normalize(contact);
            return cv;
        }

        private static void addDate(
            Cv cv,
            Object value) {
            String date = text(value).strip();
            if (!date.isBlank())
                cv.dates.add(date);
        }
    }

    private static final class Messages {

        final boolean vi;

        final List<String> strengths = new ArrayList<>(), issues = new ArrayList<>();

        final List<Map<String, Object>> suggestions = new ArrayList<>();

        Messages(
            boolean vi) {
            this.vi = vi;
        }

        String t(
            String viText,
            String enText) {
            return vi ? viText : enText;
        }

        void strength(
            String viText,
            String enText) {
            strengths.add(t(viText, enText));
        }

        void issue(
            String viText,
            String enText) {
            issues.add(t(viText, enText));
        }

        void suggest(
            String category,
            String severity,
            String viText,
            String enText) {
            suggestions.add(row("category", category, "severity", severity, "explanation", t(viText, enText)));
        }
    }

    private static Map<String, Object> presence(
        String code,
        String label,
        boolean present,
        Messages notes) {
        if (!present) {
            notes.issue("Thiếu: " + label, "Missing: " + label);
            notes.suggest(
                "SECTIONS",
                "HIGH",
                "Bổ sung " + label.toLowerCase(Locale.ROOT) + " vào CV.",
                "Add your " + label.toLowerCase(Locale.ROOT) + "."
            );
        }
        return check(code, label, present ? 1 : 0, present ? "OK" : "MISSING");
    }

    private static Map<String, Object> check(
        String code,
        String label,
        double score,
        String detail) {
        double bounded = Math.max(0, Math.min(1, score));
        return row(
            "code",
            code,
            "label",
            label,
            "score",
            round(100 * bounded),
            "passed",
            bounded >= 0.7,
            "detail",
            detail
        );
    }

    private static Map<String, Object> category(
        String code,
        String label,
        double weight,
        Double score,
        List<Map<String, Object>> checks) {
        return row(
            "code",
            code,
            "label",
            label,
            "weight",
            weight,
            "status",
            score == null ? "NOT_APPLICABLE" : "ASSESSED",
            "score",
            score == null ? null : round(score),
            "checks",
            checks
        );
    }

    private static double average(
        List<Map<String, Object>> checks) {
        return checks.isEmpty()
            ? 0
            : checks.stream().mapToDouble(item -> ((Number) item.get("score")).doubleValue()).average().orElse(0);
    }

    private static String band(
        double score) {
        return score >= 80 ? "EXCELLENT" : score >= 65 ? "GOOD" : score >= 50 ? "FAIR" : "POOR";
    }

    private static BigDecimal round(
        double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static Map<String, Object> row(
        Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
            row.put((String) pairs[i], pairs[i + 1]);
        return row;
    }

    private static List<String> bullets(
        String text) {
        List<String> result = new ArrayList<>();
        for (String part : BULLET_SPLIT.split(text)) {
            String line = part.replaceFirst("^[\\s\\-–—*+·>]+", "").strip();
            if (words(line) >= 2)
                result.add(line);
        }
        return result;
    }

    /** Lower-case, accent-free text with single spaces; keeps symbols used in skill names (c#, c++, node.js). */
    static String normalize(
        String value) {
        String text = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
            .replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
        text = text.replaceAll("[^a-z0-9+#./ ]", " ").replaceAll("(?<![a-z0-9])\\.|\\.(?![a-z0-9])", " ");
        return " " + text.replaceAll("\\s+", " ").strip() + " ";
    }

    private static boolean contains(
        String normalizedText,
        String phrase) {
        String needle = normalize(phrase).strip();
        if (needle.isEmpty())
            return false;
        return Pattern.compile("(?<![a-z0-9+#])" + Pattern.quote(needle) + "(?![a-z0-9+#])").matcher(normalizedText)
            .find();
    }

    private static boolean containsAny(
        String normalizedText,
        List<String> phrases) {
        return phrases.stream().anyMatch(phrase -> contains(normalizedText, phrase));
    }

    private static boolean sameSkill(
        String name,
        String canonical) {
        String key = normalize(name).strip();
        return HARD_SKILLS.getOrDefault(canonical, List.of()).stream()
            .anyMatch(alias -> normalize(alias).strip().equals(key));
    }

    private static List<String> spellings(
        String skill) {
        Set<String> result = new LinkedHashSet<>(List.of(skill));
        for (Map.Entry<String, List<String>> entry : HARD_SKILLS.entrySet())
            if (sameSkill(skill, entry.getKey()))
                result.addAll(entry.getValue());
        return new ArrayList<>(result);
    }

    private static int words(
        String text) {
        String stripped = text == null ? "" : text.strip();
        return stripped.isEmpty() ? 0 : stripped.split("\\s+").length;
    }

    private static LocalDate date(
        Object value) {
        String text = text(value).strip();
        try {
            if (text.matches("\\d{4}-\\d{2}-\\d{2}.*"))
                return LocalDate.parse(text.substring(0, 10));
            if (text.matches("\\d{4}-\\d{2}"))
                return LocalDate.parse(text + "-01");
            if (text.matches("\\d{4}"))
                return LocalDate.of(Integer.parseInt(text), 1, 1);
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static String text(
        Object value) {
        return value == null ? "" : Objects.toString(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(
        Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<?> list(
        Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static Map<String, List<String>> hardSkills() {
        Map<String, List<String>> skills = new LinkedHashMap<>();
        String[][] rows = {
            {"Java", "java", "java core", "core java"},
            {"Spring Boot", "spring boot", "springboot", "spring"},
            {"Hibernate", "hibernate", "jpa"},
            {"Kotlin", "kotlin"},
            {"Python", "python"},
            {"Django", "django"},
            {"FastAPI", "fastapi"},
            {"Flask", "flask"},
            {"JavaScript", "javascript", "js", "es6"},
            {"TypeScript", "typescript"},
            {"Node.js", "node.js", "nodejs", "node js"},
            {"NestJS", "nestjs", "nest.js"},
            {"Express", "express.js", "expressjs"},
            {"React", "react", "reactjs", "react.js"},
            {"Next.js", "next.js", "nextjs"},
            {"Vue.js", "vue", "vuejs", "vue.js"},
            {"Angular", "angular", "angularjs"},
            {"HTML", "html", "html5"},
            {"CSS", "css", "css3"},
            {"Tailwind CSS", "tailwind", "tailwindcss"},
            {"Redux", "redux"},
            {"C#", "c#", "csharp"},
            {".NET", ".net", "dotnet", "asp.net", ".net core"},
            {"C++", "c++", "cpp"},
            {"Go", "golang"},
            {"PHP", "php"},
            {"Laravel", "laravel"},
            {"Ruby", "ruby", "ruby on rails", "rails"},
            {"Swift", "swift"},
            {"Flutter", "flutter"},
            {"Dart", "dart"},
            {"React Native", "react native"},
            {"Android", "android"},
            {"iOS", "ios"},
            {"SQL", "sql"},
            {"MySQL", "mysql"},
            {"PostgreSQL", "postgresql", "postgres"},
            {"SQL Server", "sql server", "mssql"},
            {"Oracle", "oracle"},
            {"MongoDB", "mongodb", "mongo"},
            {"Redis", "redis"},
            {"Elasticsearch", "elasticsearch", "elastic search"},
            {"Kafka", "kafka"},
            {"RabbitMQ", "rabbitmq"},
            {"Docker", "docker"},
            {"Kubernetes", "kubernetes", "k8s"},
            {"AWS", "aws", "amazon web services"},
            {"Azure", "azure"},
            {"GCP", "gcp", "google cloud"},
            {"Linux", "linux"},
            {"Git", "git", "github", "gitlab"},
            {"CI/CD", "ci/cd", "cicd", "jenkins", "github actions", "gitlab ci"},
            {"Terraform", "terraform"},
            {"Microservices", "microservices", "microservice"},
            {"REST API", "rest api", "restful", "restful api"},
            {"GraphQL", "graphql"},
            {"gRPC", "grpc"},
            {"Unit Testing", "unit test", "unit testing", "junit", "jest"},
            {"Selenium", "selenium"},
            {"Automation Testing", "automation test", "automation testing", "test automation"},
            {"Manual Testing", "manual test", "manual testing"},
            {"Machine Learning", "machine learning", "ml"},
            {"Deep Learning", "deep learning"},
            {"NLP", "nlp", "natural language processing"},
            {"LLM", "llm", "large language model"},
            {"TensorFlow", "tensorflow"},
            {"PyTorch", "pytorch"},
            {"Pandas", "pandas"},
            {"Power BI", "power bi", "powerbi"},
            {"Tableau", "tableau"},
            {"Excel", "excel"},
            {"Figma", "figma"},
            {"UI/UX", "ui/ux", "ux", "ui design"},
            {"Agile", "agile"},
            {"Scrum", "scrum"},
            {"Jira", "jira"},
            {"OOP", "oop", "object oriented"},
            {"Design Patterns", "design pattern", "design patterns"},
            {"Data Structures", "data structures", "algorithms", "cau truc du lieu"}};
        for (String[] row : rows) {
            List<String> aliases = new ArrayList<>(List.of(row).subList(1, row.length));
            skills.put(row[0], aliases);
        }
        return skills;
    }

    private static Map<String, List<String>> softSkills() {
        Map<String, List<String>> skills = new LinkedHashMap<>();
        skills.put("Communication", List.of("communication", "giao tiep"));
        skills.put("Teamwork", List.of("teamwork", "team work", "team player", "lam viec nhom"));
        skills.put("Problem solving", List.of("problem solving", "problem-solving", "giai quyet van de"));
        skills.put("Leadership", List.of("leadership", "lanh dao", "dan dat"));
        skills.put("Time management", List.of("time management", "quan ly thoi gian"));
        skills.put("English", List.of("english", "tieng anh", "toeic", "ielts"));
        skills.put("Self-learning", List.of("self-learning", "self learning", "tu hoc", "ham hoc hoi"));
        skills.put("Critical thinking", List.of("critical thinking", "tu duy phan bien"));
        skills.put("Presentation", List.of("presentation", "thuyet trinh"));
        skills.put("Adaptability", List.of("adaptability", "thich nghi"));
        return skills;
    }
}
