package com.example.intelligent_cv_matching_ai_agent.service.candidate.learning;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.AssessmentRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.learning.LearningReferenceService;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.learning.SkillNames;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.text;

/** Gives candidates skill-specific material before generating a selected job's assessment. */
@Service
public class AssessmentPreparationService {

    private final AssessmentRepository db;

    private final CurrentUser user;

    private final LearningReferenceService references;

    public AssessmentPreparationService(
        AssessmentRepository db,
        CurrentUser user,
        LearningReferenceService references) {
        this.db = db;
        this.user = user;
        this.references = references;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> prepare(
        UUID jobId) {
        user.requireRole("CANDIDATE");
        if (jobId == null) {
            throw ApiException.badRequest("Select a job description before preparing an assessment");
        }
        Map<String, Object> job = db.getMarketJobPostsById(jobId);
        String title = text(job.get("job_title")).strip();
        if (title.isBlank()) {
            throw ApiException.conflict("The selected job has no title to assess");
        }
        boolean hasDescription =
            List.of("raw_content", "parsed_requirements", "parsed_responsibilities", "parsed_description").stream()
                .anyMatch(key -> !text(job.get(key)).isBlank());
        if (!hasDescription) {
            throw ApiException.conflict("The selected job has no job description to assess");
        }

        Map<String, Map<String, Object>> skills = new LinkedHashMap<>();
        for (Map<String, Object> row : db.listMarketJobSkills(jobId)) {
            String name = text(row.get("skill_name")).strip();
            if (name.isBlank()) {
                continue;
            }
            String key = SkillNames.normalize(name);
            boolean required = Boolean.TRUE.equals(row.get("is_required"));
            if (skills.containsKey(key)) {
                if (required) {
                    skills.get(key).put("is_required", true);
                }
                continue;
            }
            Map<String, Object> skill = new LinkedHashMap<>();
            skill.put("skill_name", name);
            skill.put("is_required", required);
            skill.put("resources", references.resourcesForSkill(name));
            skills.put(key, skill);
        }
        if (skills.isEmpty()) {
            throw ApiException.conflict("The selected job needs extracted skills before generating an assessment");
        }
        return Map.of("job_id", jobId, "job_title", title, "skills", new ArrayList<>(skills.values()));
    }
}
