package com.example.intelligent_cv_matching_ai_agent.service.admin.rag;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.rag.CourseLinkRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.rag.RagCourseRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.util.admin.rag.RagCourseSupport;
import com.example.intelligent_cv_matching_ai_agent.util.shared.learning.CourseLink;
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

    public List<Map<String, Object>> learningResources() {
        user.requireOperationsRole();
        return db.learningResources();
    }

    @Transactional
    public Map<String, Object> setResourceActive(
        UUID id,
        boolean active) {
        user.requireOperationsRole();
        Map<String, Object> result = db.setResourceActive(id, active);
        db.audit(user.id(), active ? "LEARNING_RESOURCE_ENABLE" : "LEARNING_RESOURCE_DISABLE", id);
        return result;
    }

    public List<Map<String, Object>> list() {
        user.requireOperationsRole();
        return db.list();
    }

    public Map<String, Object> get(
        UUID id) {
        user.requireOperationsRole();
        return db.get(id);
    }

    @Transactional
    public Map<String, Object> create(
        CourseLinkRequest request) {
        user.requireOperationsRole();
        CourseLink link = CourseLink.parse(request.url());
        String title = RagCourseSupport.text(request.title(), "title", 255);
        String description = RagCourseSupport.text(request.description(), "description", 12000);
        String language = RagCourseSupport.text(request.language(), "language", 10);
        if (!language.matches("[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,6})?") || request.isFree() == null)
            throw ApiException.badRequest("language and isFree are required and must be valid");
        List<String> skills = RagCourseSupport.skills(request.skills());
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
        user.requireOperationsRole();
        Map<String, Object> course = db.lock(id);
        if ("APPROVED".equals(course.get("status")))
            return db.get(id);
        if (!"PENDING_REVIEW".equals(course.get("status")))
            throw ApiException.conflict("Only pending course links can be approved");

        CourseLink link = CourseLink.parse((String) course.get("url"));
        List<String> skillNames = RagCourseSupport.skills(course.get("skills"));
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
                RagCourseSupport.content(course, skillNames),
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
        user.requireOperationsRole();
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
        user.requireOperationsRole();
        Map<String, Object> course = db.get(id);
        if (!"APPROVED".equals(course.get("status")) || !(course.get("rag_document_id") instanceof UUID))
            throw ApiException.conflict("Only approved course links can be indexed");

        Map<String, Object> result = new LinkedHashMap<>(course);
        result.put("rag_document", rag.index((UUID) course.get("rag_document_id")));
        return result;
    }
}
