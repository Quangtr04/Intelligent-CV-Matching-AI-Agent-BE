package com.example.intelligent_cv_matching_ai_agent.util.candidate.jobs;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;
import com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillCatalog;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

/** Stable search intent and factual evidence. Each distinct CV remains an independent search document. */
public final class CandidateSearchProfile {

    public static final String VERSION = "candidate-search-v2";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Document(
        List<UUID> resumeIds,
        Map<String, Object> profile,
        String searchText,
        String contentHash,
        QuickJobMatcher.Evidence evidence,
        Map<String, Object> sourceData
    ) {
    }

    private CandidateSearchProfile() {
    }

    public static List<Document> normalize(
        String basis,
        List<Map<String, Object>> resumes,
        Map<String, Object> careerProfile,
        List<Map<String, Object>> profileSkills) {
        if (basis.equals("CAREER_PROFILE")) {
            var evidence = QuickJobMatcher.fromProfile(careerProfile, profileSkills);
            Set<String> declared = new TreeSet<>();
            profileSkills.forEach(row -> addSkill(declared, text(row.get("name"))));
            var profile = fields(
                "desired_job_title",
                careerProfile.get("target_job_title"),
                "professional_summary",
                careerProfile.get("profile_summary"),
                "current_job_title",
                careerProfile.get("current_job_title"),
                "skills",
                declared,
                "years_experience",
                evidence.yearsExperience(),
                "preferences",
                preferences(careerProfile)
            );
            return List.of(
                document(
                    List.of(),
                    profile,
                    new QuickJobMatcher.Evidence(
                        declared,
                        evidence.factualText(),
                        evidence.yearsExperience(),
                        evidence.entryLevel()
                    ),
                    fields("skills", profileSkills)
                )
            );
        }
        Map<String, Document> distinct = new LinkedHashMap<>();
        for (var resume : resumes) {
            if (!"READY".equals(resume.get("status")))
                throw ApiException.conflict("Confirm CV data before matching");
            var data = object(resume.get("structured_data"));
            var rawEvidence = QuickJobMatcher.fromResume(resume);
            Set<String> skills = new TreeSet<>();
            for (Object raw : array(data.get("skills")))
                addSkill(skills, raw instanceof String value ? value : text(object(raw).get("name")));
            List<Map<String, Object>> facts = new ArrayList<>();
            for (var chunk : RequirementEvidence.resumeChunks(List.of(resume))) {
                String section = chunk.source().split("/")[1];
                if (section.equals("summary"))
                    continue;
                facts.add(fields("section", section, "text", chunk.text()));
                if (Set.of("experience", "projects").contains(section))
                    SkillCatalog.get().mentions(chunk.text())
                        .forEach(mention -> addSkill(skills, mention.skill().name()));
            }
            String role = text(data.get("target_job_title"));
            if (role.isBlank())
                role = text(data.get("position"));
            if (role.isBlank())
                role = array(data.get("experience")).stream().map(value -> text(object(value).get("job_title")))
                    .filter(value -> !value.isBlank()).findFirst().orElse("");
            if (role.isBlank())
                role = array(data.get("projects")).stream().map(value -> text(object(value).get("role")))
                    .filter(value -> !value.isBlank()).findFirst().orElse("");
            var profile = fields(
                "desired_job_title",
                role,
                "professional_summary",
                data.get("summary"),
                "skills",
                skills,
                "work_experience_projects_education",
                facts,
                "seniority",
                data.get("seniority"),
                "years_experience",
                rawEvidence.yearsExperience(),
                "preferences",
                preferences(careerProfile)
            );
            var evidence = new QuickJobMatcher.Evidence(
                skills,
                rawEvidence.factualText(),
                rawEvidence.yearsExperience(),
                rawEvidence.entryLevel()
            );
            Document next = document(List.of(uuid(resume.get("id"))), profile, evidence, data);
            Document prior = distinct.get(next.contentHash());
            if (prior != null) {
                var ids = new ArrayList<>(prior.resumeIds());
                ids.addAll(next.resumeIds());
                next = new Document(
                    List.copyOf(ids),
                    next.profile(),
                    next.searchText(),
                    next.contentHash(),
                    next.evidence(),
                    next.sourceData()
                );
            }
            distinct.put(next.contentHash(), next);
        }
        return List.copyOf(distinct.values());
    }

    private static Document document(
        List<UUID> ids,
        Map<String, Object> profile,
        QuickJobMatcher.Evidence evidence,
        Map<String, Object> sourceData) {
        String text = JSON.writeValueAsString(profile);
        if (text.length() > 100000)
            throw ApiException.badRequest("The confirmed CV/profile is too long for recommendation search");
        return new Document(
            ids,
            Collections.unmodifiableMap(profile),
            JSON.writeValueAsString(
                fields("job_title", profile.get("desired_job_title"), "skills", profile.get("skills"))
            ),
            FlowSupport.hash(VERSION + "\n" + text),
            evidence,
            sourceData
        );
    }

    public static String factualEvidence(
        Document document) {
        StringJoiner result = new StringJoiner("\n");
        result.add(text(document.profile().get("professional_summary")));
        result.add(text(document.profile().get("current_job_title")));
        if (document.evidence().yearsExperience() != null)
            result.add("Declared/dated employment years: " + document.evidence().yearsExperience());
        document.evidence().skills().forEach(result::add);
        Set<String> seen = new LinkedHashSet<>();
        for (Object row : array(document.profile().get("work_experience_projects_education")))
            if (seen.add(text(object(row).get("text"))))
                result.add(text(object(row).get("text")));
        return result.toString();
    }

    private static Map<String, Object> preferences(
        Map<String, Object> profile) {
        return fields(
            "location",
            profile.get("preferred_location"),
            "workplace",
            profile.get("workplace_preference"),
            "employment_type",
            profile.get("employment_type")
        );
    }

    private static void addSkill(
        Set<String> skills,
        String value) {
        String name = value.strip();
        if (!name.isBlank())
            skills.add(SkillCatalog.get().canonicalName(name));
    }
}
