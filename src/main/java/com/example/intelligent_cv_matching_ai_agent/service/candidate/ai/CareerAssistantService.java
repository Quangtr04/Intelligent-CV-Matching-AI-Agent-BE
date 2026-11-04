package com.example.intelligent_cv_matching_ai_agent.service.candidate.ai;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.ai.CareerAssistantRepository;
import com.example.intelligent_cv_matching_ai_agent.service.guest.identity.GuestAccess;
import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeData;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.ai.CareerAssistantData;
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
                UUID id = CareerAssistantData.contextUuid(body.get(field));

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

    @Transactional
    public Map<String, Object> update(
        UUID id,
        Map<String, Object> body) {
        Map<String, Object> thread = activeOwnedForUpdate(id);
        if (body.isEmpty())
            return detail(thread);

        Map<String, Object> updated = new LinkedHashMap<>(thread);
        for (String field : List.of("title", "context_type")) {
            if (!body.containsKey(field))
                continue;
            String value = ResumeData.text(body.get(field), field, field.equals("title") ? 255 : 30).trim();
            if (value.isBlank())
                throw ApiException.badRequest(field + " must not be blank");
            updated.put(field, value);
        }
        for (String field : List.of("related_resume_id", "related_job_id")) {
            if (!body.containsKey(field))
                continue;
            UUID contextId = CareerAssistantData.contextUuid(body.get(field));
            if (contextId != null)
                verifyContext(field, contextId);
            updated.put(field, contextId);
        }
        if (!body.containsKey("context_type")
            && (body.containsKey("related_resume_id") || body.containsKey("related_job_id"))) {
            updated.put(
                "context_type",
                updated.get("related_job_id") != null
                    ? "job"
                    : updated.get("related_resume_id") != null ? "resume" : "general"
            );
        }
        db.updateConversationDetails(
            updated.get("title"),
            updated.get("context_type"),
            updated.get("related_resume_id"),
            updated.get("related_job_id"),
            Instant.now(),
            id
        );
        return detail(db.getCareerAssistantConversationsById(id));
    }

    @Transactional
    public void clearMessages(
        UUID id) {
        activeOwnedForUpdate(id);
        db.clearConversationMessages(id);
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

        String message = CareerAssistantData.message(body);
        String language = CareerAssistantData.language(body);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("message", message);
        if (language != null)
            input.put("language", language);
        input.put("history", db.listCareerAssistantMessagesByConversationId2(id));
        input.put("context", context(thread, guestId));
        if ("application".equals(thread.get("context_type"))) {
            input.put(
                "writing_rule",
                "Write or revise the requested finished application document using only the supplied candidate facts. "
                    + "Place the complete document in the CAREER_CHAT answer string. Do not replace the answer field "
                    + "with content, subject or reviewScore fields. Respect the requested language and do not invent "
                    + "experience, achievements or contact details."
            );
        }
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
        if (!Objects.equals(thread.get("updated_at"), locked.get("updated_at")))
            throw ApiException.conflict("Conversation changed while the response was being generated; please retry");

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

        for (Map<String, Object> source : sources) {
            Map<String, Object> citation = CareerAssistantData.citation(reply, source);
            db.insertCareerAssistantMessageSources(citation);
        }

        db.updateCareerAssistantConversationsById(answeredAt, answeredAt, id);

        Map<String, Object> output = new LinkedHashMap<>(db.getCareerAssistantMessagesById(reply));
        output.put("sources", db.listCareerAssistantMessageSourcesByMessageId(reply));

        return output;
    }

    @Transactional
    public void archive(
        UUID id) {
        activeOwnedForUpdate(id);

        db.updateCareerAssistantConversationsById2(id);
    }

    @Transactional
    public Map<String, Object> feedback(
        UUID message,
        Map<String, Object> body) {
        Map<String, Object> row = db.getCareerAssistantMessagesById2(message);
        current.owns(row, "user_id");
        Object rating = CareerAssistantData.feedbackRating(body.get("rating"));

        Object helpful = CareerAssistantData.feedbackHelpful(body.get("is_helpful"));
        db.getCareerAssistantConversationsByIdForUpdate(row.get("conversation_id"));
        db.getCareerAssistantMessagesById2(message);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("message_id", message);
        fields.put("user_id", current.id());
        fields.put("rating", rating);
        fields.put("is_helpful", helpful);
        fields.put(
            "feedback_text",
            body.get("feedback_text") == null ? null : ResumeData.text(body.get("feedback_text"), "feedback_text", 4000)
        );

        db.deleteMessageFeedback(message, current.id());
        return db.getCareerAssistantFeedbackById(db.insertCareerAssistantFeedback(fields));
    }

    @Transactional
    public void clearFeedback(
        UUID message) {
        Map<String, Object> row = db.getCareerAssistantMessagesById2(message);
        current.owns(row, "user_id");
        db.getCareerAssistantConversationsByIdForUpdate(row.get("conversation_id"));
        db.getCareerAssistantMessagesById2(message);
        db.deleteMessageFeedback(message, current.id());
    }

    private Map<String, Object> activeOwnedForUpdate(
        UUID id) {
        Map<String, Object> thread = db.getCareerAssistantConversationsByIdForUpdate(id);
        current.owns(thread, "user_id");
        if (!"ACTIVE".equals(thread.get("status")))
            throw ApiException.conflict("Conversation is not active");
        return thread;
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
}
