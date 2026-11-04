package com.example.intelligent_cv_matching_ai_agent.repository.candidate.ai;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by CareerAssistantService. */
@Repository
public class CareerAssistantRepository {

    private final SqlStore db;

    public CareerAssistantRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public List<Map<String, Object>> listCareerAssistantConversationsByUserId(
        Object... args) {
        return db
            .list(("SELECT * FROM career_assistant_conversations WHERE user_id=? ORDER BY " + "updated_at DESC"), args);
    }

    public UUID insertCareerAssistantConversations(
        Map<String, Object> fields) {
        return db.insert("career_assistant_conversations", fields);
    }

    public Map<String, Object> getCareerAssistantConversationsById(
        Object... args) {
        return db.one("SELECT * FROM career_assistant_conversations WHERE id=?", args);
    }

    public List<Map<String, Object>> listCareerAssistantMessagesByConversationId(
        Object... args) {
        return db.list(
            ("SELECT m.*,(SELECT CASE WHEN f.is_helpful=true THEN 'like' "
                + "WHEN f.is_helpful=false THEN 'dislike' ELSE NULL END "
                + "FROM career_assistant_feedback f WHERE f.message_id=m.id "
                + "ORDER BY f.created_at DESC,f.id DESC LIMIT 1) AS feedback "
                + "FROM career_assistant_messages m WHERE m.conversation_id=? ORDER BY m.created_at,m.id"),
            args
        );
    }

    public int updateConversationDetails(
        Object... args) {
        return db.update(
            "UPDATE career_assistant_conversations SET title=?,context_type=?,related_resume_id=?,"
                + "related_job_id=?,updated_at=? WHERE id=?",
            args
        );
    }

    public void clearConversationMessages(
        UUID conversation) {
        db.update(
            "DELETE FROM career_assistant_feedback WHERE message_id IN "
                + "(SELECT id FROM career_assistant_messages WHERE conversation_id=?)",
            conversation
        );
        db.update(
            "DELETE FROM career_assistant_message_sources WHERE message_id IN "
                + "(SELECT id FROM career_assistant_messages WHERE conversation_id=?)",
            conversation
        );
        db.update("DELETE FROM career_assistant_messages WHERE conversation_id=?", conversation);
        db.update(
            "UPDATE career_assistant_conversations SET last_message_at=NULL,updated_at=? WHERE id=?",
            java.time.Instant.now(),
            conversation
        );
    }

    public List<Map<String, Object>> listCareerAssistantMessageSourcesByConversationId(
        Object... args) {
        return db.list(
            ("SELECT s.* FROM career_assistant_message_sources s JOIN "
                + "career_assistant_messages m ON m.id=s.message_id WHERE "
                + "m.conversation_id=? ORDER BY s.created_at"),
            args
        );
    }

    public List<Map<String, Object>> listCareerAssistantMessagesByConversationId2(
        Object... args) {
        return db.list(
            ("SELECT sender_type,message_text FROM (SELECT id,sender_type,"
                + "message_text,created_at FROM career_assistant_messages WHERE "
                + "conversation_id=? ORDER BY created_at DESC,id DESC LIMIT 20) recent " + "ORDER BY created_at,id"),
            args
        );
    }

    public List<Map<String, Object>> listRagChunksByProvider(
        Object... args) {
        return db.list(
            ("SELECT c.id AS chunk_id,d.id AS document_id,d.title,c.content,"
                + "course.url AS source_url,course.provider AS source_provider,"
                + "1-(e.embedding <=> ?::vector) AS similarity FROM rag_chunks c JOIN "
                + "rag_documents d ON d.id=c.document_id JOIN rag_chunk_embeddings e ON "
                + "e.chunk_id=c.id LEFT JOIN users uploader ON uploader.id=d.uploaded_by "
                + "LEFT JOIN rag_course_links course ON course.rag_document_id=d.id "
                + "LEFT JOIN learning_resources resource ON resource.id=course.learning_resource_id "
                + "WHERE e.provider=? AND e.model=? AND e.embedding IS NOT NULL AND d.embedding_status='COMPLETED' "
                + "AND COALESCE(d.metadata->>'disabled','false')<>'true' AND "
                + "(course.id IS NULL OR (course.status='APPROVED' AND resource.is_active=true)) AND "
                + "(d.uploaded_by=? OR uploader.role IN ('ADMIN','MANAGEMENT')) AND 1-(e.embedding <=> "
                + "?::vector)>=0.55 ORDER BY e.embedding <=> ?::vector LIMIT 5"),
            args
        );
    }

    public Map<String, Object> getCareerAssistantConversationsByIdForUpdate(
        Object... args) {
        return db.one("SELECT * FROM career_assistant_conversations WHERE id=? FOR UPDATE", args);
    }

    public UUID insertCareerAssistantMessages(
        Map<String, Object> fields) {
        return db.insert("career_assistant_messages", fields);
    }

    public UUID insertCareerAssistantMessageSources(
        Map<String, Object> fields) {
        return db.insert("career_assistant_message_sources", fields);
    }

    public int updateCareerAssistantConversationsById(
        Object... args) {
        return db.update("UPDATE career_assistant_conversations SET last_message_at=?,updated_at=? WHERE id=?", args);
    }

    public Map<String, Object> getCareerAssistantMessagesById(
        Object... args) {
        return db.one("SELECT * FROM career_assistant_messages WHERE id=?", args);
    }

    public List<Map<String, Object>> listCareerAssistantMessageSourcesByMessageId(
        Object... args) {
        return db.list("SELECT * FROM career_assistant_message_sources WHERE message_id=?", args);
    }

    public int updateCareerAssistantConversationsById2(
        Object... args) {
        return db.update(
            ("UPDATE career_assistant_conversations SET status='ARCHIVED'," + "updated_at=now() WHERE id=?"),
            args
        );
    }

    public Map<String, Object> getCareerAssistantMessagesById2(
        Object... args) {
        return db.one(
            ("SELECT m.*,c.user_id FROM career_assistant_messages m JOIN "
                + "career_assistant_conversations c ON c.id=m.conversation_id WHERE "
                + "m.id=? AND m.sender_type='ASSISTANT'"),
            args
        );
    }

    public Map<String, Object> getCareerAssistantFeedbackById(
        Object... args) {
        return db.one("SELECT * FROM career_assistant_feedback WHERE id=?", args);
    }

    public UUID insertCareerAssistantFeedback(
        Map<String, Object> fields) {
        return db.insert("career_assistant_feedback", fields);
    }

    public int deleteMessageFeedback(
        Object... args) {
        return db.update("DELETE FROM career_assistant_feedback WHERE message_id=? AND user_id=?", args);
    }

    public Map<String, Object> getMarketJobPostsById(
        Object... args) {
        return db.one("SELECT id FROM market_job_posts WHERE id=? AND status='ACTIVE'", args);
    }

    public Map<String, Object> getGapAnalysesById(
        Object... args) {
        return db.one("SELECT * FROM gap_analyses WHERE id=?", args);
    }

    public Map<String, Object> getRoadmapsById(
        Object... args) {
        return db.one("SELECT * FROM roadmaps WHERE id=?", args);
    }

    public Optional<Map<String, Object>> findCandidateProfilesByUserId(
        Object... args) {
        return db.optional("SELECT * FROM candidate_profiles WHERE user_id=?", args);
    }

    public Map<String, Object> getMarketJobPostsById2(
        Object... args) {
        return db.one("SELECT job_title,company_name,location,raw_content FROM market_job_posts WHERE id=?", args);
    }

    public List<Map<String, Object>> listRoadmapNodesByRoadmapId(
        Object... args) {
        return db.list("SELECT * FROM roadmap_nodes WHERE roadmap_id=? ORDER BY sequence_order", args);
    }

}
