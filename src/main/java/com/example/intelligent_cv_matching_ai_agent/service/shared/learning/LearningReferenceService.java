package com.example.intelligent_cv_matching_ai_agent.service.shared.learning;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.LearningRepository;
import com.example.intelligent_cv_matching_ai_agent.util.shared.learning.CourseLink;
import com.example.intelligent_cv_matching_ai_agent.util.shared.learning.LearningReferenceData;
import com.example.intelligent_cv_matching_ai_agent.util.shared.learning.RoadmapReferenceCatalog;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

/** Selects verified source links without remote requests or additional AI usage. */
@Service
public class LearningReferenceService {

    private final LearningRepository db;

    private final RoadmapReferenceCatalog catalog;

    public LearningReferenceService(
        LearningRepository db,
        RoadmapReferenceCatalog catalog) {
        this.db = db;
        this.catalog = catalog;
    }

    public Map<String, Object> recommend(
        String target,
        Collection<String> skills) {
        List<String> focus = LearningReferenceData.focusSkills(skills);
        List<Map<String, Object>> roadmaps = catalog.select(target, focus);
        Collection<String> slugs = new LinkedHashSet<>(skillSlugs(target));
        focus.forEach(skill -> slugs.addAll(skillSlugs(skill)));
        List<Map<String, Object>> resources =
            new ArrayList<>(db.listApprovedCourseResources(slugs, CourseLink.skillSlug(target)));
        resources.addAll(db.listLearningResourcesByUrls(roadmaps.stream().map(r -> text(r.get("url"))).toList()));
        for (String skill : focus) {
            resources.addAll(resourcesForSkill(skill));
        }
        return Map.of(
            "provider",
            "roadmap.sh",
            "source_catalog_url",
            catalog.catalogUrl(),
            "focus_skills",
            focus,
            "roadmaps",
            roadmaps,
            "resources",
            LearningReferenceData.trustedResources(resources, 15)
        );
    }

    public Map<String, Object> forAssessment(
        Map<String, Object> session) {
        if (!"completed".equals(session.get("status"))) {
            return Map.of();
        }
        List<String> focus = LearningReferenceData.assessmentFocus(session);
        return recommendSkills(focus);
    }

    public Map<String, Object> recommendSkills(
        Collection<String> skills) {
        List<String> focus = LearningReferenceData.focusSkills(skills);
        List<Map<String, Object>> resources = new ArrayList<>();
        for (String skill : focus) {
            resources.addAll(resourcesForSkill(skill));
        }
        return Map.of(
            "learning_scope",
            "MISSING_SKILLS_ONLY",
            "focus_skills",
            focus,
            "roadmaps",
            List.of(),
            "resources",
            LearningReferenceData.trustedResources(resources, 100)
        );
    }

    public List<Map<String, Object>> resourcesForSkill(
        String skill) {
        if (skill == null || skill.isBlank()) {
            return List.of();
        }
        String pattern = LearningReferenceData.skillPattern(skill);
        List<Map<String, Object>> resources = new ArrayList<>();
        for (String slug : skillSlugs(skill)) {
            resources.addAll(db.listLearningResourcesForSkill(slug, pattern));
        }
        resources.addAll(
            db.listLearningResourcesByUrls(
                catalog.select("", List.of(skill)).stream().map(reference -> text(reference.get("url"))).toList()
            )
        );
        return LearningReferenceData.trustedResources(resources, 8);
    }

    private Collection<String> skillSlugs(
        String skill) {
        Collection<String> slugs = new LinkedHashSet<>(catalog.skillSlugs(skill));
        String fallback = CourseLink.skillSlug(skill);
        if (!fallback.isBlank()) {
            slugs.add(fallback);
        }
        return slugs;
    }
}
