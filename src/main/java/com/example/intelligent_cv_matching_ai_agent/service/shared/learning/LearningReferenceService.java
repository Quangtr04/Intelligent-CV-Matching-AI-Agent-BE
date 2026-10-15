package com.example.intelligent_cv_matching_ai_agent.service.shared.learning;

import com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.LearningRepository;
import com.example.intelligent_cv_matching_ai_agent.util.shared.learning.CourseLink;
import com.example.intelligent_cv_matching_ai_agent.util.shared.learning.RoadmapReferenceCatalog;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
        List<String> focus =
            skills.stream().filter(s -> s != null && !s.isBlank()).map(String::strip).distinct().limit(20).toList();
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
            trustedResources(resources, 15)
        );
    }

    public Map<String, Object> forAssessment(
        Map<String, Object> session) {
        if (!"completed".equals(session.get("status"))) {
            return Map.of();
        }
        List<String> focus = array(session.get("skill_scores")).stream().map(raw -> object(raw))
            .filter(
                score -> score.get("score") instanceof Number n && Double.isFinite(n.doubleValue())
                    && n.doubleValue() >= 0 && n.doubleValue() < 70
            ).sorted(Comparator.comparingDouble(score -> ((Number) score.get("score")).doubleValue()))
            .map(score -> text(score.get("skill")))
            .filter(skill -> !skill.isBlank() && !"General".equalsIgnoreCase(skill)).distinct().toList();
        return recommend(text(session.get("target_job_title")), focus);
    }

    public List<Map<String, Object>> resourcesForSkill(
        String skill) {
        if (skill == null || skill.isBlank()) {
            return List.of();
        }
        String pattern = "%" + skill.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<Map<String, Object>> resources = new ArrayList<>();
        for (String slug : skillSlugs(skill)) {
            resources.addAll(db.listLearningResourcesForSkill(slug, pattern));
        }
        resources.addAll(
            db.listLearningResourcesByUrls(
                catalog.select("", List.of(skill)).stream().map(reference -> text(reference.get("url"))).toList()
            )
        );
        return trustedResources(resources, 8);
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

    private static List<Map<String, Object>> trustedResources(
        List<Map<String, Object>> resources,
        int limit) {
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        List<Map<String, Object>> ordered = new ArrayList<>(resources);
        ordered.sort(Comparator.comparing(resource -> !object(resource.get("metadata_json")).containsKey("course_id")));
        for (Map<String, Object> resource : ordered) {
            String url = text(resource.get("url"));
            try {
                URI uri = URI.create(url);
                if (resource.get("id") != null && "https".equals(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null) {
                    unique.putIfAbsent(url, resource);
                }
            } catch (IllegalArgumentException ex) {
                // Ignore invalid legacy catalog URLs rather than linking untrusted schemes.
            }
            if (unique.size() >= limit) {
                break;
            }
        }
        return List.copyOf(unique.values());
    }
}
