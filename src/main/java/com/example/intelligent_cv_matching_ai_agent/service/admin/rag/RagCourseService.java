package com.example.intelligent_cv_matching_ai_agent.service.admin.rag;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.rag.CourseLinkRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.rag.RagCourseRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.util.shared.learning.CourseLink;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Only explicitly approved links become available to learning recommendations and RAG. */
@Service
public class RagCourseService {

    private final RagCourseRepository db;

    private final CurrentUser user;

    private final RagDocumentService rag;

    public RagCourseService(
        RagCourseRepository db,
        CurrentUser user,
        RagDocumentService rag) {
        this.db = db;
        this.user = user;
        this.rag = rag;
    }

    public List<Map<String, Object>> list() {
        user.requireRole("ADMIN");
        return db.list();
    }

    public Map<String, Object> get(
        UUID id) {
        user.requireRole("ADMIN");
        return db.get(id);
    }

    @Transactional
    public Map<String, Object> create(
        CourseLinkRequest request) {
        user.requireRole("ADMIN");
        CourseLink link = CourseLink.parse(request.url());
        String title = text(request.title(), "title", 255);
        String description = text(request.description(), "description", 12000);
        String language = text(request.language(), "language", 10);
        if (!language.matches("[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,6})?") || request.isFree() == null)
            throw ApiException.badRequest("language and isFree are required and must be valid");
        List<String> skills = skills(request.skills());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("url", link.url());
        fields.put("provider", link.provider());
        fields.put("title", title);
        fields.put("description", description);
        fields.put("skills", skills);
        fields.put("language", language);
        fields.put("is_free", request.isFree());
        fields.put("created_by_user_id", user.id());
        UUID id = db.create(fields).orElseThrow(() -> ApiException.conflict("This course link already exists"));
        db.audit(user.id(), "CREATE_RAG_COURSE", id);
        return db.get(id);
    }

    @Transactional
    public Map<String, Object> approve(
        UUID id) {
        user.requireRole("ADMIN");
        Map<String, Object> course = db.lock(id);
        if ("APPROVED".equals(course.get("status")))
            return db.get(id);
        if (!"PENDING_REVIEW".equals(course.get("status")))
            throw ApiException.conflict("Only pending course links can be approved");

        CourseLink link = CourseLink.parse((String) course.get("url"));
        List<String> skillNames = skills(course.get("skills"));
        List<String> skillSlugs = skillNames.stream().map(CourseLink::skillSlug).distinct().toList();
        Map<String, Object> resource = new LinkedHashMap<>(course);
        resource.put("resource_type", link.resourceType());
        resource.put(
            "metadata_json",
            Map.of("course_id", id.toString(), "skill_slugs", skillSlugs, "skill_names", skillNames, "reviewed", true)
        );
        UUID resourceId = db.createLearningResource(resource)
            .orElseThrow(() -> ApiException.conflict("This URL already exists in the learning resource catalog"));
        Map<String, Object> document = rag.create(
            Map.of(
                "title",
                course.get("title"),
                "content",
                content(course, skillNames),
                "metadata",
                Map.of(
                    "course_id",
                    id.toString(),
                    "source_url",
                    link.url(),
                    "provider",
                    link.provider(),
                    "skill_slugs",
                    skillSlugs,
                    "reviewed",
                    true
                )
            )
        );
        db.approve(id, resourceId, (UUID) document.get("id"), user.id());
        db.audit(user.id(), "APPROVE_RAG_COURSE", id);
        return db.get(id);
    }

    @Transactional
    public Map<String, Object> archive(
        UUID id) {
        user.requireRole("ADMIN");
        Map<String, Object> course = db.lock(id);
        if ("ARCHIVED".equals(course.get("status")))
            return db.get(id);
        if (!"PENDING_REVIEW".equals(course.get("status")) && !"APPROVED".equals(course.get("status")))
            throw ApiException.conflict("Course link cannot be archived from its current state");

        if (course.get("learning_resource_id") instanceof UUID resourceId)
            db.deactivateResource(resourceId);
        if (course.get("rag_document_id") instanceof UUID documentId)
            rag.archive(documentId);
        db.archive(id);
        db.audit(user.id(), "ARCHIVE_RAG_COURSE", id);
        return db.get(id);
    }

    /** Preserve the existing retryable index states instead of holding a transaction across network calls. */
    public Map<String, Object> index(
        UUID id) {
        user.requireRole("ADMIN");
        Map<String, Object> course = db.get(id);
        if (!"APPROVED".equals(course.get("status")) || !(course.get("rag_document_id") instanceof UUID))
            throw ApiException.conflict("Only approved course links can be indexed");

        Map<String, Object> result = new LinkedHashMap<>(course);
        result.put("rag_document", rag.index((UUID) course.get("rag_document_id")));
        return result;
    }

    private static String content(
        Map<String, Object> course,
        List<String> skills) {
        return "Admin-reviewed learning resource\nTitle: " + course.get("title") + "\nURL: " + course.get("url")
            + "\nProvider: " + course.get("provider") + "\nSkills: " + String.join(", ", skills) + "\nLanguage: "
            + course.get("language") + "\nFree: " + course.get("is_free") + "\nDescription: "
            + course.get("description");
    }

    private static List<String> skills(
        Object input) {
        if (!(input instanceof List<?> values) || values.isEmpty() || values.size() > 30)
            throw ApiException.badRequest("skills must contain between 1 and 30 skill names");

        Map<String, String> distinct = new LinkedHashMap<>();
        for (Object value : values) {
            if (!(value instanceof String name))
                throw ApiException.badRequest("Each skill must be a name");
            String clean = text(name, "skill", 150);
            String slug = CourseLink.skillSlug(clean);
            if (slug.isBlank())
                throw ApiException.badRequest("Each skill must contain letters or numbers");
            distinct.putIfAbsent(slug, clean);
        }
        return new ArrayList<>(distinct.values());
    }

    private static String text(
        String value,
        String name,
        int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength)
            throw ApiException.badRequest(name + " is required and must not exceed " + maxLength + " characters");
        return value.strip();
    }
}
