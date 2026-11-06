package com.example.intelligent_cv_matching_ai_agent.service.shared.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.consultation.AssignConsultantRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.consultation.CreateConsultationRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.consultation.ConsultationMessageRequest;

import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation.ConsultationRepository;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

@Service
public class ConsultationService {

    private final ConsultationRepository db;

    private final CurrentUser user;

    private record Subscriber(
        UUID userId,
        SseEmitter emitter
    ) {
    }

    private final Map<UUID, CopyOnWriteArrayList<Subscriber>> subscribers = new ConcurrentHashMap<>();

    public ConsultationService(
        ConsultationRepository db,
        CurrentUser user) {
        this.db = db;
        this.user = user;
    }

    private Map<String, Object> access(
        UUID id) {
        var thread = db.getConsultationThreadsById(id);
        if (!user.hasRole("ADMIN") && !user.id().equals(thread.get("candidate_user_id"))
            && !user.id().equals(thread.get("consultant_user_id")))
            throw ApiException.notFound("Consultation not found");

        return thread;
    }

    @Transactional
    public Map<String, Object> create(
        CreateConsultationRequest input) {
        user.requireRole("CANDIDATE");
        throw ApiException
            .badRequest("Purchase a session pack, then select a consultant through /consultation-bookings");
    }

    public List<Map<String, Object>> list() {
        if (user.hasRole("ADMIN"))
            return db.listConsultationThreads();

        return db.listConsultationThreadsByCandidateUserId(user.id(), user.id());
    }

    public Map<String, Object> thread(
        UUID id) {
        return access(id);
    }

    public Map<String, Object> candidateProfile(
        UUID id,
        UUID resumeId) {
        var thread = access(id);
        UUID owner = (UUID) thread.get("candidate_user_id");
        var profile = db.findCandidateProfilesByUserId(owner).orElseGet(LinkedHashMap::new);
        profile.put("skills", db.listCandidateProfileSkillsByUserId(owner));
        var candidate = db.getUsersById(owner);
        Map<String, Object> cv =
            resumeId == null ? db.findResumesByUserId(owner).orElse(Map.of()) : db.getResumesById(resumeId, owner);

        return fields("candidate", candidate, "career_profile", profile, "resume", cv);
    }

    @Transactional
    public Map<String, Object> assign(
        UUID id,
        AssignConsultantRequest request) {
        user.requireRole("ADMIN");

        db.getConsultationThreadsByIdForUpdate(id);
        db.getUsersById2(request.consultantId());

        var before = db.getConsultationThreadsById(id);
        if ("SESSION_BOOKING".equals(before.get("request_type"))) {
            throw ApiException.conflict("The candidate's selected consultant cannot be reassigned");
        }
        db.updateConsultationThreadsById(request.consultantId(), id);
        if (!request.consultantId().equals(before.get("consultant_user_id"))) {
            inform(
                request.consultantId(),
                "CONSULTATION_ASSIGNED",
                "Bạn được phân công một yêu cầu tư vấn",
                text(before.get("subject")),
                id
            );
            inform(
                (UUID) before.get("candidate_user_id"),
                "CONSULTATION_ASSIGNED",
                "Yêu cầu tư vấn đã có cố vấn",
                text(before.get("subject")),
                id
            );
        }
        db.insertAuditLogs(
            fields(
                "actor_user_id",
                user.id(),
                "action",
                "CONSULTATION_ASSIGN",
                "entity_type",
                "consultation_threads",
                "entity_id",
                id,
                "new_value",
                db.json(fields("consultant_id", request.consultantId()))
            )
        );
        afterCommit(() -> disconnect(id));

        return access(id);
    }

    public List<Map<String, Object>> messages(
        UUID id,
        int limit,
        int offset) {
        access(id);

        return db.listConsultationMessagesByThreadId(id, pageSize(limit), offset(offset));
    }

    @Transactional
    public Map<String, Object> send(
        UUID id,
        ConsultationMessageRequest input) {
        db.getConsultationThreadsByIdForUpdate2(id);
        var thread = access(id);
        if (!"OPEN".equals(thread.get("status")))
            throw ApiException.conflict("Consultation is closed");

        if ("SESSION_BOOKING".equals(thread.get("request_type"))) {
            var booking = db.bookingForThread(id);
            java.time.Instant now = java.time.Instant.now();
            if (!"ACCEPTED".equals(booking.get("status")) || bookingTime(booking.get("starts_at")).isAfter(now)
                || !bookingTime(booking.get("ends_at")).isAfter(now)) {
                throw ApiException.conflict("Consultation chat is available only during the accepted appointment");
            }
        }

        UUID messageId = db.insertConsultationMessages(
            fields(
                "thread_id",
                id,
                "sender_user_id",
                user.id(),
                "content",
                input.content(),
                "sent_at",
                java.time.OffsetDateTime.now()
            )
        );

        var message = db.getConsultationMessagesById(messageId);

        for (Object recipient : Arrays.asList(thread.get("candidate_user_id"), thread.get("consultant_user_id")))
            if (recipient != null && !user.id().equals(recipient)
                && db.findUnreadNotificationsByUserIdAndReference(recipient, "CONSULTATION_MESSAGE", id).isEmpty())
                inform(
                    (UUID) recipient,
                    "CONSULTATION_MESSAGE",
                    "Tin nhắn tư vấn mới",
                    text(thread.get("subject")),
                    id
                );

        afterCommit(() -> publish(id, message));

        return message;
    }

    public Map<String, Object> read(
        UUID id,
        UUID messageId) {
        access(id);
        db.getConsultationMessagesById2(messageId, id);

        db.updateConsultationMessagesById(messageId, user.id());

        return Map.of("id", messageId, "read", true);
    }

    @Transactional
    public Map<String, Object> close(
        UUID id) {
        db.getConsultationThreadsByIdForUpdate2(id);
        var thread = access(id);
        if ("SESSION_BOOKING".equals(thread.get("request_type"))) {
            throw ApiException.conflict("Scheduled consultations close through their booking lifecycle");
        }

        db.updateConsultationThreadsById2(id);
        afterCommit(() -> disconnect(id));

        return access(id);
    }

    public SseEmitter events(
        UUID id) {
        access(id);
        SseEmitter emitter = new SseEmitter(300_000L);
        Subscriber subscriber = new Subscriber(user.id(), emitter);
        var list = subscribers.computeIfAbsent(id, key -> new CopyOnWriteArrayList<>());
        list.add(subscriber);
        Runnable cleanup = () -> {
            list.remove(subscriber);

            if (list.isEmpty())
                subscribers.remove(id, list);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());

        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("thread_id", id)));
        } catch (IOException ex) {
            emitter.complete();
        }

        return emitter;
    }

    private void publish(
        UUID id,
        Map<String, Object> message) {
        for (Subscriber subscriber : subscribers.getOrDefault(id, new CopyOnWriteArrayList<>())) {
            boolean allowed = db.findConsultationThreadsById(subscriber.userId(), id).isPresent();

            try {
                if (allowed)
                    subscriber.emitter()
                        .send(SseEmitter.event().id(message.get("id").toString()).name("message").data(message));
                else
                    subscriber.emitter().complete();
            } catch (IOException | IllegalStateException ex) {
                subscriber.emitter().complete();
            }
        }
    }

    private void inform(
        UUID recipient,
        String type,
        String title,
        String content,
        UUID threadId) {
        db.insertNotifications(
            fields(
                "user_id",
                recipient,
                "type",
                type,
                "title",
                title,
                "content",
                content,
                "reference_type",
                "CONSULTATION",
                "reference_id",
                threadId,
                "created_at",
                java.time.OffsetDateTime.now()
            )
        );
    }

    private void disconnect(
        UUID id) {
        var list = subscribers.remove(id);

        if (list != null)
            list.forEach(s -> s.emitter().complete());
    }

    private static void afterCommit(
        Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static java.time.Instant bookingTime(
        Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime dateTime) {
            return dateTime.toInstant();
        }
        if (value instanceof java.time.Instant instant) {
            return instant;
        }
        return java.time.OffsetDateTime.parse(value.toString()).toInstant();
    }
}
