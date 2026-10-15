package com.example.intelligent_cv_matching_ai_agent.repository.admin.rag;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class RagCourseRepository {

    private final SqlStore db;

    public RagCourseRepository(
        SqlStore db) {
        this.db = db;
    }

    public List<Map<String, Object>> list() {
        return db.list("SELECT * FROM rag_course_links ORDER BY created_at DESC, id DESC LIMIT 200");
    }

    public Map<String, Object> get(
        UUID id) {
        return db.one("SELECT * FROM rag_course_links WHERE id=?", id);
    }

    public Map<String, Object> lock(
        UUID id) {
        return db.one("SELECT * FROM rag_course_links WHERE id=? FOR UPDATE", id);
    }

    public Optional<UUID> create(
        Map<String, Object> fields) {
        return db.optional(
            "INSERT INTO rag_course_links (url,provider,title,description,skills,language,is_free,created_by_user_id) "
                + "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (url) DO NOTHING RETURNING id",
            fields.get("url"),
            fields.get("provider"),
            fields.get("title"),
            fields.get("description"),
            db.json(fields.get("skills")),
            fields.get("language"),
            fields.get("is_free"),
            fields.get("created_by_user_id")
        ).map(row -> (UUID) row.get("id"));
    }

    public Optional<UUID> createLearningResource(
        Map<String, Object> fields) {
        return db.optional(
            "INSERT INTO learning_resources (title,resource_type,provider,url,description,language,is_free,"
                + "source_type,metadata_json,is_active) VALUES (?,?,?,?,?,?,?,'ADMIN_CURATED',?,true) "
                + "ON CONFLICT (url) DO NOTHING RETURNING id",
            fields.get("title"),
            fields.get("resource_type"),
            fields.get("provider"),
            fields.get("url"),
            fields.get("description"),
            fields.get("language"),
            fields.get("is_free"),
            db.json(fields.get("metadata_json"))
        ).map(row -> (UUID) row.get("id"));
    }

    public void approve(
        UUID id,
        UUID resourceId,
        UUID documentId,
        UUID reviewerId) {
        db.update(
            "UPDATE rag_course_links SET status='APPROVED',learning_resource_id=?,rag_document_id=?,"
                + "reviewed_by_user_id=?,reviewed_at=now(),updated_at=now() WHERE id=?",
            resourceId,
            documentId,
            reviewerId,
            id
        );
    }

    public void archive(
        UUID id) {
        db.update("UPDATE rag_course_links SET status='ARCHIVED',updated_at=now() WHERE id=?", id);
    }

    public void deactivateResource(
        UUID id) {
        db.update("UPDATE learning_resources SET is_active=false,updated_at=now() WHERE id=?", id);
    }

    public void audit(
        UUID actorId,
        String action,
        UUID id) {
        db.insert(
            "audit_logs",
            Map.of("actor_user_id", actorId, "action", action, "entity_type", "RAG_COURSE", "entity_id", id)
        );
    }
}
