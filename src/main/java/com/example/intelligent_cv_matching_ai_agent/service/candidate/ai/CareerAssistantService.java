package com.example.intelligent_cv_matching_ai_agent.service.candidate.ai;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.ai.CareerAssistantRepository;
import com.example.intelligent_cv_matching_ai_agent.service.guest.identity.GuestAccess;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeData;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class CareerAssistantService {

    private final CareerAssistantRepository db;

    private final CurrentUser current;

    private final GuestAccess guests;

    private final AiService ai;

    private final ResumeService resumes;

    public CareerAssistantService(
        CareerAssistantRepository db,
        CurrentUser current,
        GuestAccess guests,
        AiService ai,
        ResumeService resumes) {
        this.db = db;
        this.current = current;
        this.guests = guests;
        this.ai = ai;
        this.resumes = resumes;
    }

    public List<Map<String, Object>> list() {
        return db.listCareerAssistantConversationsByUserId(current.id());
    }

    public Map<String, Object> create(
        Map<String, Object> body) {
        return create(body, null);
    }

    public Map<String, Object> createGuest(
        String key) {
        UUID guest = guests.id(key);

        return create(Map.of("title", "Dùng thử trợ lý nghề nghiệp"), guest);
    }

    private Map<String, Object> create(
        Map<String, Object> body,
        UUID guest) {
        if (guest == null)
            current.id();

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(guest == null ? "user_id" : "guest_session_id", guest == null ? current.id() : guest);
        fields.put("title", ResumeData.text(body.getOrDefault("title", "Tư vấn nghề nghiệp"), "title", 255));
        fields.put("status", "ACTIVE");
        fields.put("context_type", ResumeData.text(body.getOrDefault("context_type", "general"), "context_type", 50));

        if (guest == null) {
            for (String field : List
                .of("related_job_id", "related_resume_id", "related_gap_analysis_id", "related_roadmap_id")) {
                UUID id = uuid(body.get(field));

                if (id == null)
                    continue;

                verifyContext(field, id);
                fields.put(field, id);
            }
        }

        UUID id = db.insertCareerAssistantConversations(fields);

        return db.getCareerAssistantConversationsById(id);
    }

    public Map<String, Object> get(
        UUID id) {
        Map<String, Object> thread = owned(id, null);

        return detail(thread);
    }

    public Map<String, Object> getGuest(
        UUID id,
        String key) {
        return detail(owned(id, guests.id(key)));
    }

    private Map<String, Object> detail(
        Map<String, Object> thread) {
        Map<String, Object> detail = new LinkedHashMap<>(thread);
        detail.put("messages", db.listCareerAssistantMessagesByConversationId(thread.get("id")));
        detail.put("sources", db.listCareerAssistantMessageSourcesByConversationId(thread.get("id")));

        return detail;
    }

    @Transactional
    public Map<String, Object> send(
        UUID id,
        Map<String, Object> body) {
        return send(id, body, null);
    }

    @Transactional
    public Map<String, Object> sendGuest(
        UUID id,
        Map<String, Object> body,
        String key) {
        return send(id, body, guests.id(key));
    }

    private Map<String, Object> send(
        UUID id,
        Map<String, Object> body,
        UUID guestId) {
        Map<String, Object> thread = owned(id, guestId);
        if (!"ACTIVE".equals(thread.get("status")))
            throw ApiException.conflict("Conversation is not active");

        String message = ResumeData.text(body.get("message"), "message", 8000);
        if (message.isBlank())
            throw ApiException.badRequest("message is required");
        if (body.containsKey("retrieve") && !(body.get("retrieve") instanceof Boolean))
            throw ApiException.badRequest("retrieve must be boolean");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", message);
        input.put("history", db.listCareerAssistantMessagesByConversationId2(id));
        input.put("context", context(thread, guestId));
        List<Map<String, Object>> sources = List.of();

        if (guestId == null && Boolean.TRUE.equals(body.get("retrieve"))) {
            float[] vector = ai.embed(message);
            sources = db.listRagChunksByProvider(
                Arrays.toString(vector),
                ai.provider(),
                ai.embeddingModel(),
                current.id(),
                Arrays.toString(vector),
                Arrays.toString(vector)
            );
        }

        input.put("sources", sources);
        input.put(
            "citation_rule",
            ("Only refer to the supplied sources; no other citations have been " + "retrieved or verified.")
        );

        Map<String, Object> result =
            guestId == null ? ai.generate("CAREER_CHAT", input) : ai.generateForGuest("CAREER_CHAT", input, guestId);

        // Check again after the network call so concurrent archiving cannot append to a
        // closed thread.
        Map<String, Object> locked = db.getCareerAssistantConversationsByIdForUpdate(id);
        if (!"ACTIVE".equals(locked.get("status")))
            throw ApiException.conflict("Conversation was closed while the response was being generated");

        Instant sentAt = Instant.now(), answeredAt = sentAt.plusMillis(1);

        db.insertCareerAssistantMessages(
            Map.of(
                "conversation_id",
                id,
                "sender_type",
                "USER",
                "message_text",
                message,
                "retrieval_used",
                false,
                "created_at",
                sentAt
            )
        );

        Map<?, ?> usage = (Map<?, ?>) result.getOrDefault("_usage", Map.of());

        UUID reply = db.insertCareerAssistantMessages(
            Map.of(
                "conversation_id",
                id,
                "sender_type",
                "ASSISTANT",
                "message_text",
                result.get("answer"),
                "model_used",
                result.get("_model"),
                "input_tokens",
                usage.containsKey("input_tokens") ? usage.get("input_tokens") : 0,
                "output_tokens",
                usage.containsKey("output_tokens") ? usage.get("output_tokens") : 0,
                "retrieval_used",
                !sources.isEmpty(),
                "created_at",
                answeredAt
            )
        );

        for (Map<String, Object> source : sources)
            db.insertCareerAssistantMessageSources(
                Map.of(
                    "message_id",
                    reply,
                    "rag_chunk_id",
                    source.get("chunk_id"),
                    "rag_document_id",
                    source.get("document_id"),
                    "source_label",
                    source.get("title"),
                    "similarity_score",
                    source.get("similarity")
                )
            );

        db.updateCareerAssistantConversationsById(answeredAt, answeredAt, id);

        Map<String, Object> output = new LinkedHashMap<>(db.getCareerAssistantMessagesById(reply));
        output.put("sources", db.listCareerAssistantMessageSourcesByMessageId(reply));

        return output;
    }

    public void archive(
        UUID id) {
        owned(id, null);

        db.updateCareerAssistantConversationsById2(id);
    }

    public Map<String, Object> feedback(
        UUID message,
        Map<String, Object> body) {
        Map<String, Object> row = db.getCareerAssistantMessagesById2(message);
        current.owns(row, "user_id");
        Object rating = body.get("rating");
        if (rating != null && (!(rating instanceof Number n) || !Double.isFinite(n.doubleValue())
            || n.doubleValue() != n.intValue() || n.intValue() < 1 || n.intValue() > 5))
            throw ApiException.badRequest("rating must be an integer from 1 to 5");

        Object helpful = body.get("is_helpful");
        if (helpful != null && !(helpful instanceof Boolean))
            throw ApiException.badRequest("is_helpful must be boolean");

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("message_id", message);
        fields.put("user_id", current.id());
        fields.put("rating", rating);
        fields.put("is_helpful", helpful);
        fields.put(
            "feedback_text",
            body.get("feedback_text") == null ? null : ResumeData.text(body.get("feedback_text"), "feedback_text", 4000)
        );

        return db.getCareerAssistantFeedbackById(db.insertCareerAssistantFeedback(fields));
    }

    private Map<String, Object> owned(
        UUID id,
        UUID guest) {
        Map<String, Object> thread = db.getCareerAssistantConversationsById(id);

        if (guest == null)
            current.owns(thread, "user_id");
        else if (!guest.equals(thread.get("guest_session_id")))
            throw ApiException.notFound("Conversation not found");

        return thread;
    }

    private void verifyContext(
        String field,
        UUID id) {
        switch (field) {
            case "related_resume_id" -> resumes.owned(id);
            case "related_job_id" -> db.getMarketJobPostsById(id);
            case "related_gap_analysis_id" -> current.owns(db.getGapAnalysesById(id), "user_id");
            case "related_roadmap_id" -> current.owns(db.getRoadmapsById(id), "user_id");
            default -> throw ApiException.badRequest("Invalid assistant context");
        }
    }

    private Map<String, Object> context(
        Map<String, Object> thread,
        UUID guest) {
        if (guest != null)
            return Map.of("trial", true);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("career_profile", db.findCandidateProfilesByUserId(current.id()).orElse(Map.of()));

        if (thread.get("related_resume_id") != null)
            context.put("resume_text", resumes.canonicalText(resumes.owned((UUID) thread.get("related_resume_id"))));

        if (thread.get("related_job_id") != null)
            context.put("job", db.getMarketJobPostsById2(thread.get("related_job_id")));

        if (thread.get("related_gap_analysis_id") != null) {
            Map<String, Object> gap = db.getGapAnalysesById(thread.get("related_gap_analysis_id"));
            current.owns(gap, "user_id");
            context.put("skill_gaps", gap);
        }

        if (thread.get("related_roadmap_id") != null) {
            Map<String, Object> roadmap = db.getRoadmapsById(thread.get("related_roadmap_id"));
            current.owns(roadmap, "user_id");
            context.put("roadmap", roadmap);
            context.put("roadmap_nodes", db.listRoadmapNodesByRoadmapId(thread.get("related_roadmap_id")));
        }

        return context;
    }

    private UUID uuid(
        Object raw) {
        if (raw == null)
            return null;

        try {
            return UUID.fromString(raw.toString());
        } catch (Exception ex) {
            throw ApiException.badRequest("Invalid context UUID");
        }
    }
}
