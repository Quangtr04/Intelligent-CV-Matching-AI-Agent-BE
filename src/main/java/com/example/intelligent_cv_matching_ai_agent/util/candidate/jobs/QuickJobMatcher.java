package com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobPostingParser;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeSkillMatcher;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

/** Compares factual skills and explicit experience requirements without calling a model. */
public final class QuickJobMatcher {

    public static final String VERSION = "quick-skill-evidence-v2";

    private static final Pattern ENTRY = Pattern.compile(
        "\\b(?:fresher|intern(?:ship)?|entry[ -]level|final[ -]year|new graduate|recent graduate|"
            + "sinh vien|moi tot nghiep|thuc tap)\\b"
    );

    private static final Pattern NO_EXPERIENCE = Pattern.compile(
        "\\b(?:no (?:prior |professional |full[ -]time )?(?:work |working )?experience|"
            + "chua co kinh nghiem (?:lam viec|di lam)|chua co kinh nghiem chinh thuc)\\b"
    );

    private static final Pattern ADVANCED_ROLE = Pattern
        .compile("\\b(?:senior|lead|expert|principal|staff|manager|director|architect|truong nhom|chuyen gia)\\b");

    private static final Pattern REQUIREMENTS = Pattern.compile(
        "\\b(?:your skills(?: and experience)?|requirements|qualifications|what you (?:bring|need)|"
            + "yeu cau(?: ung vien| cong viec)?|kinh nghiem va ky nang)\\b"
    );

    private static final Pattern BENEFITS =
        Pattern.compile("\\b(?:why you(?:'|’)?ll love|why join|benefits|what we offer|quyen loi|phuc loi|dai ngo)\\b");

    private static final Pattern YEARS = Pattern.compile(
        "(?<![\\p{L}\\d])(?:(minimum(?: of)?|at least|toi thieu|it nhat|tu|hon|over|more than|from)\\s+)?"
            + "(\\d{1,2}(?:\\.\\d+)?)\\s*(?:[-–]\\s*\\d{1,2}(?:\\.\\d+)?)?\\s*\\+?\\s*(?:years?|yrs?|nam)\\b"
    );

    public record Evidence(
        Set<String> skills,
        String factualText,
        Double yearsExperience,
        boolean entryLevel
    ) {

        public Evidence {
            skills = Collections.unmodifiableSet(new TreeSet<>(skills));
        }
    }

    private QuickJobMatcher() {
    }

    public static Evidence fromResume(
        Map<String, Object> resume) {
        Map<String, Object> data = object(resume.get("structured_data"));
        StringBuilder facts = new StringBuilder(text(data.get("summary")));
        Set<String> skills = explicitSkills(array(data.get("skills")));
        for (String section : List.of("experience", "projects")) {
            for (Object raw : array(data.get(section))) {
                Map<String, Object> row = object(raw);
                for (String key : List.of("job_title", "role", "description", "technologies", "highlights")) {
                    facts.append('\n').append(text(row.get(key)));
                }
            }
        }
        skills.addAll(canonicalSkills(facts.toString()));
        Double years = employmentYears(array(data.get("experience")));
        if (years == null && NO_EXPERIENCE.matcher(fold(facts.toString())).find()) {
            years = 0.0;
        }
        return new Evidence(
            skills,
            facts.toString(),
            years,
            years != null && years == 0 || years == null && ENTRY.matcher(fold(facts.toString())).find()
        );
    }

    public static Evidence fromProfile(
        Map<String, Object> profile,
        List<Map<String, Object>> declaredSkills) {
        String facts = text(profile.get("profile_summary")) + "\n" + text(profile.get("current_job_title"));
        Set<String> skills = explicitSkills(new ArrayList<>(declaredSkills));
        skills.addAll(canonicalSkills(facts));
        Double years = profile.get("years_experience") instanceof Number value ? value.doubleValue() : null;
        if (years != null && (!Double.isFinite(years) || years < 0)) {
            years = null;
        }
        if (years == null && NO_EXPERIENCE.matcher(fold(facts)).find()) {
            years = 0.0;
        }
        return new Evidence(
            skills,
            facts,
            years,
            years != null && years == 0 || years == null && ENTRY.matcher(fold(facts)).find()
        );
    }

    public static Evidence combine(
        List<Evidence> sources) {
        Set<String> skills = new TreeSet<>();
        StringJoiner facts = new StringJoiner("\n");
        Double years = null;
        for (Evidence source : sources) {
            skills.addAll(source.skills());
            facts.add(source.factualText());
            if (source.yearsExperience() != null) {
                years = years == null ? source.yearsExperience() : Math.max(years, source.yearsExperience());
            }
        }
        return new Evidence(
            skills,
            facts.toString(),
            years,
            !sources.isEmpty() && sources.stream().allMatch(Evidence::entryLevel)
        );
    }

    public static Map<String, Object> assess(
        Evidence candidate,
        Map<String, Object> job) {
        String requirements = requirements(job);
        Map<String, Boolean> requiredSkills = new TreeMap<>();
        for (Object raw : array(job.get("skills"))) {
            Map<String, Object> skill = object(raw);
            addRequirement(requiredSkills, text(skill.get("name")), !Boolean.FALSE.equals(skill.get("required")));
        }
        for (var skill : JobPostingParser.extractSkills("", requirements, "")) {
            addRequirement(requiredSkills, skill.name(), skill.required());
        }
        if (requiredSkills.containsKey("spring boot")) {
            requiredSkills.remove("spring");
        }
        List<String> strengths = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        int required = 0;
        int matchedRequired = 0;
        for (var skill : requiredSkills.entrySet()) {
            boolean found = candidate.skills().contains(skill.getKey())
                || skill.getKey().equals("spring") && candidate.skills().contains("spring boot");
            if (found) {
                strengths.add(skill.getKey());
            } else if (skill.getValue()) {
                gaps.add(skill.getKey());
            }
            if (skill.getValue()) {
                required++;
                if (found) {
                    matchedRequired++;
                }
            }
        }
        double coverage = required > 0
            ? (double) matchedRequired / required
            : requiredSkills.isEmpty() ? 0 : (double) strengths.size() / requiredSkills.size();
        Double requiredYears = requiredYears(requirements);
        String role = fold(text(job.get("job_title")) + " " + text(job.get("parsed_seniority")));
        boolean advanced = ADVANCED_ROLE.matcher(role).find() && !ENTRY.matcher(role).find();
        String experienceStatus = "NOT_SPECIFIED";
        if (requiredYears != null) {
            experienceStatus = candidate.yearsExperience() == null
                ? "UNVERIFIED"
                : candidate.yearsExperience() < requiredYears ? "INSUFFICIENT" : "MATCHED";
        } else if (advanced) {
            experienceStatus = "UNVERIFIED";
        }
        if (candidate.entryLevel() && advanced) {
            experienceStatus = "INSUFFICIENT";
        }
        String reason = "Khớp " + matchedRequired + "/" + required + " kỹ năng bắt buộc";
        if (required == 0) {
            reason = requiredSkills.isEmpty()
                ? "Chưa có yêu cầu kỹ năng để đối chiếu"
                : "Khớp " + strengths.size() + "/" + requiredSkills.size() + " kỹ năng trong tin tuyển dụng";
        }
        if (experienceStatus.equals("INSUFFICIENT")) {
            reason += ". Kinh nghiệm/cấp bậc hiện tại chưa đáp ứng yêu cầu tuyển dụng";
        } else if (experienceStatus.equals("UNVERIFIED")) {
            reason += ". Chưa đủ thông tin xác minh kinh nghiệm";
        }
        return fields(
            "score",
            coverage,
            "skill_coverage",
            coverage,
            "eligible",
            coverage > 0 && !experienceStatus.equals("INSUFFICIENT"),
            "experience_status",
            experienceStatus,
            "candidate_years",
            candidate.yearsExperience(),
            "required_years",
            requiredYears,
            "reason",
            reason,
            "strengths",
            strengths,
            "skill_gaps",
            gaps
        );
    }

    private static Set<String> explicitSkills(
        List<?> values) {
        Set<String> skills = new TreeSet<>();
        for (Object raw : values) {
            String name = raw instanceof String value ? value : text(object(raw).get("name"));
            if (!name.isBlank()) {
                Set<String> known = canonicalSkills(name);
                skills.addAll(known.isEmpty() ? Set.of(fold(name).trim()) : known);
            }
        }
        return skills;
    }

    private static Set<String> canonicalSkills(
        String facts) {
        Set<String> skills = new TreeSet<>();
        ResumeSkillMatcher.find(facts).forEach(skill -> skills.add(fold(skill).trim()));
        return skills;
    }

    private static void addRequirement(
        Map<String, Boolean> skills,
        String name,
        boolean required) {
        if (!name.isBlank()) {
            Set<String> names = canonicalSkills(name);
            for (String canonical : names.isEmpty() ? Set.of(fold(name).trim()) : names) {
                skills.merge(canonical, required, Boolean::logicalOr);
            }
        }
    }

    private static String requirements(
        Map<String, Object> job) {
        String text = fold(text(job.get("parsed_requirements")));
        if (text.isBlank()) {
            String raw = fold(text(job.get("raw_content")));
            var marker = REQUIREMENTS.matcher(raw);
            text = marker.find() ? raw.substring(marker.end()) : "";
            if (text.isBlank() && YEARS.matcher(raw).find()) {
                text = raw;
            }
        }
        var benefits = BENEFITS.matcher(text);
        if (benefits.find()) {
            text = text.substring(0, benefits.start());
        }
        return text.substring(0, Math.min(text.length(), 30_000));
    }

    private static Double requiredYears(
        String requirements) {
        var matcher = YEARS.matcher(requirements);
        Double minimum = null;
        while (matcher.find()) {
            String context = requirements
                .substring(Math.max(0, matcher.start() - 45), Math.min(requirements.length(), matcher.end() + 90));
            if (matcher.group(1) != null || context.matches("(?s).*(experience|kinh nghiem|hands.on|management).*")) {
                double value = Double.parseDouble(matcher.group(2));
                if (value <= 50) {
                    minimum = minimum == null ? value : Math.max(minimum, value);
                }
            }
        }
        return minimum;
    }

    private static Double employmentYears(
        List<?> experiences) {
        List<LocalDate[]> periods = new ArrayList<>();
        for (Object raw : experiences) {
            Map<String, Object> row = object(raw);
            try {
                LocalDate start = LocalDate.parse(text(row.get("start_date")));
                LocalDate end = Boolean.TRUE.equals(row.get("is_current"))
                    ? LocalDate.now()
                    : LocalDate.parse(text(row.get("end_date")));
                if (!end.isBefore(start) && !end.isAfter(LocalDate.now())) {
                    periods.add(new LocalDate[]{start.withDayOfMonth(1), end.withDayOfMonth(1)});
                }
            } catch (java.time.DateTimeException ignored) {
                // Undated roles do not establish a duration.
            }
        }
        if (periods.isEmpty()) {
            return null;
        }
        periods.sort(Comparator.comparing(period -> period[0]));
        long months = 0;
        LocalDate start = periods.getFirst()[0];
        LocalDate end = periods.getFirst()[1];
        for (int i = 1; i < periods.size(); i++) {
            LocalDate[] period = periods.get(i);
            if (!period[0].isAfter(end.plusMonths(1))) {
                end = period[1].isAfter(end) ? period[1] : end;
            } else {
                months += ChronoUnit.MONTHS.between(start, end) + 1;
                start = period[0];
                end = period[1];
            }
        }
        months += ChronoUnit.MONTHS.between(start, end) + 1;
        return Math.round(months / 12.0 * 10) / 10.0;
    }

    private static String fold(
        String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").replace('đ', 'd')
            .replace('Đ', 'D').toLowerCase(Locale.ROOT);
    }
}
