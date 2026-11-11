package com.example.intelligent_cv_matching_ai_agent.service.consultant.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultationAdviceRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultationPrivateNotesRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.consultant.consultation.ConsultationAdviceRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.consultant.consultation.ConsultationEvaluationRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.util.consultant.consultation.ConsultationAdviceContent;
import com.example.intelligent_cv_matching_ai_agent.util.consultant.consultation.ConsultationAttachments;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;

@Service
public class ConsultationAdviceService {

    private final ConsultationAdviceRepository db;

    private final ConsultationEvaluationRepository evaluations;

    private final CurrentUser user;

    public ConsultationAdviceService(
        ConsultationAdviceRepository db,
        ConsultationEvaluationRepository evaluations,
        CurrentUser user) {
        this.db = db;
        this.evaluations = evaluations;
        this.user = user;
    }

    @Transactional
    public Map<String, Object> advice(
        UUID consultationId) {
        requireReader();
        Map<String, Object> context = db.context(consultationId, user.id(), false);
        Optional<Map<String, Object>> existing = db.advice(consultationId);
        if (existing.isEmpty()
            || !"PUBLISHED".equals(existing.get().get("status")) && !user.hasRole("CAREER_CONSULTANT")) {
            return fields(
                "consultationId",
                consultationId,
                "candidateId",
                context.get("candidate_user_id"),
                "status",
                "EMPTY",
                "version",
                0L,
                "advice",
                null
            );
        }
        return adviceResponse(existing.get());
    }

    @Transactional
    public Map<String, Object> saveAdvice(
        UUID consultationId,
        ConsultationAdviceRequest request) {
        user.requireRole("CAREER_CONSULTANT");
        Map<String, Object> context = writableContext(consultationId);
        if (request == null || !Set.of("DRAFT", "PUBLISHED").contains(request.status())) {
            throw ApiException.badRequest("Advice status must be DRAFT or PUBLISHED");
        }
        Optional<Map<String, Object>> existing = db.advice(consultationId);
        checkVersion(request.expectedVersion(), existing);
        if (existing.isPresent() && "PUBLISHED".equals(existing.get().get("status"))
            && "DRAFT".equals(request.status())) {
            throw ApiException.conflict("A published report cannot be hidden as a draft; publish an updated version");
        }
        UUID candidateId = (UUID) context.get("candidate_user_id");
        UUID resumeId = ConsultationAttachments.resume(context, request.resumeId());
        UUID jobId = ConsultationAttachments.job(context, request.jobId());
        if (resumeId != null) {
            evaluations.readyResume(resumeId, candidateId);
        }
        if (jobId != null && !ConsultationAttachments.bound(context)) {
            evaluations.activeJob(jobId);
        }
        Map<String, Object> content =
            ConsultationAdviceContent.normalize(request.advice(), "PUBLISHED".equals(request.status()));
        Map<String, Object> saved = db.saveAdvice(
            consultationId,
            candidateId,
            user.id(),
            resumeId,
            jobId,
            request.status(),
            content,
            request.expectedVersion()
        );
        db.audit(consultationId, user.id(), "CONSULTATION_ADVICE_" + request.status(), version(saved));
        if ("PUBLISHED".equals(request.status())) {
            db.publishedNotification(consultationId, candidateId);
        }
        return adviceResponse(db.advice(consultationId).orElse(saved));
    }

    @Transactional
    public Map<String, Object> privateNotes(
        UUID consultationId) {
        user.requireRole("CAREER_CONSULTANT");
        db.context(consultationId, user.id(), false);
        Optional<Map<String, Object>> existing = db.privateNotes(consultationId, user.id());
        return existing.map(this::notesResponse).orElseGet(
            () -> fields(
                "consultationId",
                consultationId,
                "consultantId",
                user.id(),
                "notes",
                "",
                "rubric",
                Map.of(),
                "version",
                0L,
                "updatedAt",
                null
            )
        );
    }

    @Transactional
    public Map<String, Object> savePrivateNotes(
        UUID consultationId,
        ConsultationPrivateNotesRequest request) {
        user.requireRole("CAREER_CONSULTANT");
        writableContext(consultationId);
        if (request == null || request.notes() == null || request.notes().length() > 10000) {
            throw ApiException.badRequest("Private notes must contain at most 10000 characters");
        }
        Optional<Map<String, Object>> existing = db.privateNotes(consultationId, user.id());
        checkVersion(request.expectedVersion(), existing);
        Map<String, Object> rubric = rubric(request.rubric());
        Map<String, Object> saved =
            db.savePrivateNotes(consultationId, user.id(), request.notes().strip(), rubric, request.expectedVersion());
        // The audit records only metadata; private notes and rubric are never copied into shared audit content.
        db.audit(consultationId, user.id(), "CONSULTATION_PRIVATE_NOTES_SAVE", version(saved));
        return notesResponse(saved);
    }

    private Map<String, Object> writableContext(
        UUID id) {
        Map<String, Object> context = db.context(id, user.id(), true);
        if ("SESSION_BOOKING".equals(context.get("request_type"))) {
            if (!Set.of("ACCEPTED", "COMPLETED").contains(context.get("booking_status"))
                || Boolean.TRUE.equals(context.get("credit_refunded"))) {
                throw ApiException.conflict("Advice requires an accepted or completed consultation booking");
            }
        } else if (!"OPEN".equals(context.get("status"))) {
            throw ApiException.conflict("Consultation is closed");
        }
        return context;
    }

    private void requireReader() {
        user.id();
        if (!user.hasRole("CANDIDATE") && !user.hasRole("CAREER_CONSULTANT")) {
            throw ApiException.forbidden("Candidate or assigned career consultant access is required");
        }
    }

    private static void checkVersion(
        Long expected,
        Optional<Map<String, Object>> existing) {
        if (expected == null || expected < 0 || expected == Long.MAX_VALUE) {
            throw ApiException.badRequest("Provide a nonnegative expectedVersion");
        }
        long current = existing.map(ConsultationAdviceService::version).orElse(0L);
        if (expected != current) {
            throw ApiException.conflict("This report changed; reload it before saving");
        }
    }

    private static long version(
        Map<String, Object> row) {
        return ((Number) row.get("version")).longValue();
    }

    private Map<String, Object> adviceResponse(
        Map<String, Object> row) {
        Map<String, Object> content = new LinkedHashMap<>();
        if (row.get("advice_json") instanceof Map<?, ?> map) {
            map.forEach(
                (
                    key,
                    value) -> content.put(key.toString(), value)
            );
        }
        content.put("lastUpdated", timestamp(row.get("updated_at")));
        content.put("consultantName", row.get("consultant_name"));
        return fields(
            "consultationId",
            row.get("thread_id"),
            "candidateId",
            row.get("candidate_user_id"),
            "consultantId",
            row.get("consultant_user_id"),
            "resumeId",
            row.get("resume_id"),
            "jobId",
            row.get("market_job_id"),
            "status",
            row.get("status"),
            "version",
            version(row),
            "advice",
            content,
            "createdAt",
            timestamp(row.get("created_at")),
            "updatedAt",
            timestamp(row.get("updated_at")),
            "publishedAt",
            timestamp(row.get("published_at"))
        );
    }

    private Map<String, Object> notesResponse(
        Map<String, Object> row) {
        return fields(
            "consultationId",
            row.get("thread_id"),
            "consultantId",
            row.get("consultant_user_id"),
            "notes",
            row.get("notes"),
            "rubric",
            row.get("rubric_json"),
            "version",
            version(row),
            "updatedAt",
            timestamp(row.get("updated_at"))
        );
    }

    private static Map<String, Object> rubric(
        ConsultationPrivateNotesRequest.Rubric input) {
        if (input == null) {
            return Map.of();
        }
        Map<String, Object> result = fields(
            "cvQuality",
            input.cvQuality(),
            "technicalDepth",
            input.technicalDepth(),
            "communication",
            input.communication(),
            "marketReadiness",
            input.marketReadiness()
        );
        for (Object value : result.values()) {
            if (!(value instanceof Integer score) || score < 0 || score > 10) {
                throw ApiException.badRequest("Rubric scores must be integers from 0 to 10");
            }
        }
        return result;
    }

    private static String timestamp(
        Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Timestamp time) {
            return time.toInstant().toString();
        }
        if (value instanceof OffsetDateTime time) {
            return time.toInstant().toString();
        }
        if (value instanceof Instant time) {
            return time.toString();
        }
        return value.toString();
    }
}
