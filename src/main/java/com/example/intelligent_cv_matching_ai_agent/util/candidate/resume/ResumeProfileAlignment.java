package com.example.intelligent_cv_matching_ai_agent.util.candidate.resume;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.object;

/** Evidence-grounded CV alignment with the owner's saved aspirations; independent of CV writing quality. */
public final class ResumeProfileAlignment {

    public static final String RUBRIC_VERSION = "cv-profile-alignment-v1";

    public static final List<String> CV_PREFERENCE_CODES = List.of("LOCATION", "WORKPLACE", "EMPLOYMENT_TYPE");

    public static final List<String> CODES =
        List.of("TARGET_ROLE", "CAREER_GOAL", "SKILLS", "EXPERIENCE", "LOCATION", "WORKPLACE", "EMPLOYMENT_TYPE");

    private static final Map<String, List<String>> PROFILE_FIELDS = Map.of(
        "TARGET_ROLE",
        List.of("target_job_title"),
        "CAREER_GOAL",
        List.of("career_goal"),
        "SKILLS",
        List.of("skills"),
        "EXPERIENCE",
        List.of("years_experience", "current_job_title", "current_company"),
        "LOCATION",
        List.of("preferred_location"),
        "WORKPLACE",
        List.of("workplace_preference"),
        "EMPLOYMENT_TYPE",
        List.of("employment_type")
    );

    private ResumeProfileAlignment() {
    }

    public static Map<String, Object> snapshotProfile(
        Map<String, Object> profile,
        List<Map<String, Object>> skills) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String field : List.of(
            "target_job_title",
            "career_goal",
            "current_job_title",
            "current_company",
            "years_experience",
            "profile_summary",
            "preferred_location",
            "workplace_preference",
            "employment_type",
            "expected_salary",
            "salary_currency"
        )) {
            Object value = profile.get(field);
            if (value instanceof String text && !text.isBlank()) {
                snapshot.put(field, text.strip());
            } else if (value instanceof Number number) {
                snapshot.put(field, new BigDecimal(number.toString()).stripTrailingZeros());
            }
        }
        List<Map<String, Object>> safeSkills = new ArrayList<>();
        for (Map<String, Object> skill : skills) {
            if (!(skill.get("name") instanceof String name) || name.isBlank()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name.strip());
            for (String field : List.of("proficiency_level", "years_experience")) {
                Object value = skill.get(field);
                if (value instanceof String text && !text.isBlank()) {
                    row.put(field, text.strip());
                } else if (value instanceof Number number) {
                    row.put(field, new BigDecimal(number.toString()).stripTrailingZeros());
                }
            }
            safeSkills.add(row);
        }
        safeSkills.sort(Comparator.comparing(row -> row.get("name").toString()));
        snapshot.put("skills", safeSkills);
        return snapshot;
    }

    public static Map<String, Object> resumeSnapshot(
        Map<String, Object> resume) {
        Map<String, Object> data = object(resume.get("structured_data"));
        boolean reviewed = !Objects.toString(data.get("summary"), "").isBlank()
            || List.of("experience", "education", "projects", "certifications", "skills").stream()
                .anyMatch(key -> data.get(key) instanceof List<?> rows && !rows.isEmpty());
        Map<String, Object> safeData = new LinkedHashMap<>();
        if (reviewed) {
            for (String field : List
                .of("summary", "location", "experience", "education", "projects", "certifications", "skills")) {
                if (data.containsKey(field)) {
                    safeData.put(field, copy(data.get(field)));
                }
            }
        }
        return Map.of(
            "structured_data",
            safeData,
            "raw_content",
            reviewed ? "" : Objects.toString(resume.get("raw_content"), ""),
            "content_authority",
            reviewed ? "REVIEWED_STRUCTURED" : "RAW_TEXT",
            "language",
            Objects.toString(resume.get("language"), "vi")
        );
    }

    public static boolean hasComparableProfile(
        Map<String, Object> profile) {
        return CODES.stream().anyMatch(code -> applicable(code, profile));
    }

    public static List<String> cvEvidenceOptions(
        Map<String, Object> resume) {
        List<String> source = new ArrayList<>();
        collect(
            "REVIEWED_STRUCTURED".equals(resume.get("content_authority"))
                ? resume.get("structured_data")
                : resume.get("raw_content"),
            source
        );
        return snippets(source);
    }

    public static List<String> explicitCvRoles(
        Map<String, Object> resume) {
        Map<String, Object> data = object(resume.get("structured_data"));
        if (!(data.get("experience") instanceof List<?> rows)) {
            return List.of();
        }
        return rows.stream().map(ResumeProfileAlignment::rowRole).filter(value -> !value.isBlank()).distinct().toList();
    }

    private static String rowRole(
        Object value) {
        return value instanceof Map<?, ?> row && row.get("job_title") instanceof String title ? title.strip() : "";
    }

    public static List<String> profileEvidenceOptions(
        Map<String, Object> profile) {
        List<String> source = new ArrayList<>();
        PROFILE_FIELDS.values().stream().flatMap(List::stream).distinct()
            .forEach(field -> collect(profile.get(field), source));
        return snippets(source);
    }

    private static List<String> snippets(
        List<String> source) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String text : source) {
            for (int start = 0; start < text.length();) {
                int end = Math.min(text.length(), start + 240);
                if (end < text.length()) {
                    int boundary = text.lastIndexOf(' ', end);
                    if (boundary > start + 80) {
                        end = boundary;
                    }
                    if (Character.isHighSurrogate(text.charAt(end - 1))) {
                        end--;
                    }
                }
                String quote = text.substring(start, end).strip();
                if (!quote.isBlank()) {
                    result.add(quote);
                }
                start = end;
            }
        }
        return List.copyOf(result);
    }

    public static void validateModelResponse(
        Map<String, Object> generated) {
        if (!RUBRIC_VERSION.equals(generated.get("rubric_version"))) {
            throw invalid("Unsupported rubric version");
        }
        if (!(generated.get("criteria") instanceof List<?> rows) || rows.size() != CODES.size()) {
            throw invalid("Exactly seven comparison criteria are required");
        }
        List<String> seen = new ArrayList<>();
        for (Object value : rows) {
            if (!(value instanceof Map<?, ?> row) || !(row.get("code") instanceof String code) || !CODES.contains(code)
                || seen.contains(code)) {
                throw invalid("Unknown or duplicated criterion");
            }
            seen.add(code);
            String status = Objects.toString(row.get("status"), "");
            if (!List.of("ASSESSED", "MISSING_CV_EVIDENCE", "INSUFFICIENT_EVIDENCE", "NOT_APPLICABLE")
                .contains(status)) {
                throw invalid("Invalid criterion status");
            }
            if ("ASSESSED".equals(status)) {
                score(row.get("score"));
            } else if (!row.containsKey("score") || row.get("score") != null) {
                throw invalid("Unassessed criterion must have a null score");
            }
            text(row.get("feedback"), 1200);
            strings(row.get("profile_evidence"), 2, 500);
            strings(row.get("cv_evidence"), 2, 500);
            strings(row.get("suggestions"), 3, 600);
        }
    }

    public static Map<String, Object> normalize(
        Map<String, Object> generated,
        Map<String, Object> resume,
        Map<String, Object> profile) {
        validateModelResponse(generated);
        List<String> cvSource = new ArrayList<>();
        if ("REVIEWED_STRUCTURED".equals(resume.get("content_authority"))) {
            collect(resume.get("structured_data"), cvSource);
        } else {
            collect(resume.get("raw_content"), cvSource);
        }
        boolean vi = !"en".equals(resume.get("language"));
        List<Map<String, Object>> normalized = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        int assessed = 0;
        int applicable = 0;
        for (String code : CODES) {
            Map<String, Object> incoming = ((List<?>) generated.get("criteria")).stream().map(value -> object(value))
                .filter(row -> code.equals(row.get("code"))).findFirst().orElseThrow();
            if (!applicable(code, profile)) {
                normalized.add(
                    unassessed(
                        code,
                        "NOT_APPLICABLE",
                        vi
                            ? "Hồ sơ nghề nghiệp chưa có thông tin này để đối chiếu."
                            : "The career profile has no information for this comparison."
                    )
                );
                continue;
            }
            applicable++;
            // An address or past employer does not establish a desired work preference in the current CV schema.
            if (CV_PREFERENCE_CODES.contains(code)) {
                Map<String, Object> missing = unassessed(
                    code,
                    "MISSING_CV_EVIDENCE",
                    vi
                        ? "Schema CV hiện chưa có trường ưu tiên làm việc riêng để đối chiếu tin cậy. Không suy đoán từ địa chỉ hoặc công ty; hãy đối chiếu ưu tiên này với job/JD."
                        : "The current CV schema has no dedicated desired-work field for a reliable comparison. An address or employer does not prove this preference; compare it with a job/JD."
                );
                missing.put("profile_evidence", List.of(profile.get(PROFILE_FIELDS.get(code).getFirst()).toString()));
                normalized.add(missing);
                continue;
            }
            List<String> profileSource = new ArrayList<>();
            PROFILE_FIELDS.get(code).forEach(field -> collect(profile.get(field), profileSource));
            List<String> profileQuotes = strings(incoming.get("profile_evidence"), 2, 500);
            List<String> cvQuotes = strings(incoming.get("cv_evidence"), 2, 500);
            verifyQuotes(profileQuotes, profileSource, "career profile", code);
            verifyQuotes(cvQuotes, cvSource, "reviewed CV", code);
            String status = incoming.get("status").toString();
            if ("NOT_APPLICABLE".equals(status)) {
                status = "INSUFFICIENT_EVIDENCE";
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code);
            row.put("status", status);
            row.put("score", null);
            row.put("feedback", incoming.get("feedback"));
            row.put("profile_evidence", profileQuotes);
            row.put("cv_evidence", cvQuotes);
            row.put("suggestions", strings(incoming.get("suggestions"), 3, 600));
            if ("ASSESSED".equals(status)) {
                if (profileQuotes.isEmpty() || cvQuotes.isEmpty()) {
                    throw invalid("An assessed criterion needs evidence from both sources: " + code);
                }
                BigDecimal value = score(incoming.get("score"));
                row.put("score", value);
                sum = sum.add(value);
                assessed++;
            }
            if ("MISSING_CV_EVIDENCE".equals(status) && !cvQuotes.isEmpty()) {
                throw invalid("Missing CV evidence must not contain CV quotes: " + code);
            }
            normalized.add(row);
        }
        Object salary = profile.get("expected_salary");
        boolean hasSalary = salary instanceof Number number && new BigDecimal(number.toString()).signum() > 0;
        String salaryStatus = hasSalary ? "REQUIRES_JOB_DATA" : "NOT_APPLICABLE";
        normalized.add(
            unassessed(
                "SALARY_EXPECTATION",
                salaryStatus,
                vi
                    ? "Mức lương kỳ vọng là ưu tiên của bạn. Cần mức trả của job/JD để đánh giá phù hợp lương; không suy ra khả năng nhận lương từ CV."
                    : "Expected salary is your preference. An offered job salary is needed to assess salary fit; earnings cannot be inferred from a CV."
            )
        );
        Map<String, Object> salaryContext = new LinkedHashMap<>();
        salaryContext.put("expected_salary", hasSalary ? salary : null);
        salaryContext.put("currency", Objects.toString(profile.get("salary_currency"), "VND"));
        salaryContext.put("period", "MONTHLY");
        salaryContext.put("status", salaryStatus);
        salaryContext.put("score", null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rubric_version", RUBRIC_VERSION);
        result.put("score", assessed == 0 ? null : sum.divide(BigDecimal.valueOf(assessed), 2, RoundingMode.HALF_UP));
        result.put("assessed_criteria", assessed);
        result.put("applicable_criteria", applicable);
        result.put(
            "coverage_percent",
            applicable == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(assessed * 100L).divide(BigDecimal.valueOf(applicable), 2, RoundingMode.HALF_UP)
        );
        result.put("criteria", normalized);
        result.put("salary_expectation", salaryContext);
        return result;
    }

    private static boolean applicable(
        String code,
        Map<String, Object> profile) {
        return PROFILE_FIELDS.get(code).stream().map(profile::get).anyMatch(
            value -> value instanceof Number || value instanceof String text && !text.isBlank()
                || value instanceof List<?> rows && !rows.isEmpty()
        );
    }

    private static Map<String, Object> unassessed(
        String code,
        String status,
        String feedback) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", code);
        row.put("status", status);
        row.put("score", null);
        row.put("feedback", feedback);
        row.put("profile_evidence", List.of());
        row.put("cv_evidence", List.of());
        row.put("suggestions", List.of());
        return row;
    }

    private static void verifyQuotes(
        List<String> quotes,
        List<String> source,
        String name,
        String code) {
        for (String quote : quotes) {
            if (source.stream().map(ResumeProfileAlignment::search).noneMatch(value -> value.contains(search(quote)))) {
                throw invalid("Evidence is not a quote from " + name + ": " + code);
            }
        }
    }

    private static String search(
        String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).replaceAll("[\\s\\p{Z}]+", " ").strip();
    }

    private static void collect(
        Object value,
        List<String> target) {
        if (value instanceof String text && !text.isBlank()) {
            target.add(text);
        } else if (value instanceof Number number) {
            target.add(number.toString());
            String plain = new BigDecimal(number.toString()).toPlainString();
            if (!plain.equals(number.toString())) {
                target.add(plain);
            }
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> collect(item, target));
        } else if (value instanceof List<?> list) {
            list.forEach(item -> collect(item, target));
        }
    }

    private static Object copy(
        Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach(
                (
                    key,
                    item) -> {
                    if (!List.of("id", "resume_id", "skill_id", "user_id", "created_at", "updated_at")
                        .contains(key.toString())) {
                        result.put(key.toString(), copy(item));
                    }
                }
            );
            return result;
        }
        if (value instanceof List<?> rows) {
            return rows.stream().map(ResumeProfileAlignment::copy).toList();
        }
        return value;
    }

    private static BigDecimal score(
        Object value) {
        if (!(value instanceof Number number)) {
            throw invalid("Assessed score must be numeric");
        }
        try {
            BigDecimal score = new BigDecimal(number.toString());
            if (score.signum() < 0 || score.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw invalid("Score must be between 0 and 100");
            }
            return score;
        } catch (NumberFormatException ex) {
            throw invalid("Score must be finite");
        }
    }

    private static String text(
        Object value,
        int maximum) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > maximum) {
            throw invalid("Invalid comparison text");
        }
        return text;
    }

    private static List<String> strings(
        Object value,
        int maximum,
        int length) {
        if (!(value instanceof List<?> values) || values.size() > maximum) {
            throw invalid("Invalid comparison text list");
        }
        return values.stream().map(item -> text(item, length)).toList();
    }

    private static ApiException invalid(
        String detail) {
        return new ApiException(502, "AI CV profile alignment is invalid: " + detail);
    }
}
