package com.example.intelligent_cv_matching_ai_agent.service.shared.consultation;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation.ConsultationMeetingRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.text;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.uuid;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;

/** Provider calls run outside database transactions; each durable step is lease-fenced. */
@Service
public class ConsultationMeetingService {

    private static final Logger LOG = LoggerFactory.getLogger(ConsultationMeetingService.class);

    private final ConsultationMeetingRepository db;

    private final GoogleConsultationMeetingClient google;

    private final Clock clock;

    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    private final ExecutorService provisioning = executor("consultation-provision-");

    private final ExecutorService opening = executor("consultation-open-");

    private final ExecutorService closing = executor("consultation-close-");

    @Autowired
    public ConsultationMeetingService(
        ConsultationMeetingRepository db,
        GoogleConsultationMeetingClient google) {
        this(db, google, Clock.systemUTC());
    }

    public ConsultationMeetingService(
        ConsultationMeetingRepository db,
        GoogleConsultationMeetingClient google,
        Clock clock) {
        this.db = db;
        this.google = google;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.consultation.meeting-worker-delay-ms:5000}", initialDelay = 5000)
    public void synchronize() {
        for (Map<String, Object> item : db.due()) {
            UUID id = uuid(item.get("id"));
            if (!inFlight.add(id)) {
                continue;
            }
            ExecutorService executor = switch (text(item.get("meeting_work"))) {
                case "CLOSE" -> closing;
                case "OPEN" -> opening;
                default -> provisioning;
            };
            try {
                executor.execute(() -> {
                    try {
                        process(id);
                    } catch (RuntimeException failure) {
                        LOG.warn("Consultation meeting worker will recover booking {} on its next scan", id);
                    } finally {
                        inFlight.remove(id);
                    }
                });
            } catch (RejectedExecutionException busy) {
                // No unbounded queue: leave the durable job due for the next scan.
                inFlight.remove(id);
            }
        }
    }

    @PreDestroy
    public void stop() {
        provisioning.shutdownNow();
        opening.shutdownNow();
        closing.shutdownNow();
    }

    private static ExecutorService executor(
        String name) {
        return new ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            Thread.ofVirtual().name(name, 0).factory(),
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public void process(
        UUID id) {
        UUID lease = UUID.randomUUID();
        if (!db.claim(id, lease)) {
            return;
        }
        try {
            Map<String, Object> row = db.get(id);
            if (!ownsLease(row, lease)) {
                return;
            }
            if (!active(row)) {
                close(row, lease);
                return;
            }
            validateWindow(row);
            if (!"READY".equals(row.get("meeting_status"))) {
                if (!provision(row, lease)) {
                    return;
                }
                row = db.get(id);
            }
            if (!ownsLease(row, lease)) {
                return;
            }
            if (!active(row)) {
                close(row, lease);
            } else if (!time(row.get("starts_at")).isAfter(clock.instant()) && row.get("meeting_opened_at") == null) {
                row = refresh(id, lease);
                if (!active(row)) {
                    close(row, lease);
                    return;
                }
                google.openSpace(text(row.get("meeting_space_name")));
                if (!db.opened(id, lease)) {
                    reconcileAfterSideEffect(db.get(id), lease);
                }
            }
        } catch (RuntimeException ex) {
            // Provider errors are deliberately sanitized; raw HTTP bodies/tokens never reach a booking or log.
            String message = ex instanceof ApiException
                ? ex.getMessage()
                : "Google meeting synchronization is temporarily unavailable; the system will retry.";
            db.failed(id, lease, message.substring(0, Math.min(message.length(), 500)));
            LOG.warn(
                "Google consultation synchronization failed: booking={}, errorType={}, reason={}",
                id,
                ex.getClass().getSimpleName(),
                ex instanceof ApiException ? ex.getMessage() : "Unexpected internal error"
            );
        } finally {
            db.release(id, lease);
        }
    }

    private boolean provision(
        Map<String, Object> row,
        UUID lease) {
        UUID id = uuid(row.get("id"));
        if (text(row.get("meeting_space_name")).isBlank()) {
            row = refresh(id, lease);
            if (!active(row)) {
                close(row, lease);
                return false;
            }
            LOG.info("Creating Google Meet space for booking {}", id);
            var space = google.createSpace();

            LOG.info("Google Meet space created for booking {}", id);
            if (!db
                .saveSpace(id, lease, space.name(), space.meetingUri(), GoogleConsultationMeetingClient.eventId(id))) {
                // A suspended worker may have lost its lease. This unadvertised room has no invitees.
                cleanupProvider(fields("id", id, "status", "CANCELLED", "meeting_space_name", space.name()), null);
                return false;
            }
        }
        row = refresh(id, lease);
        if (!active(row)) {
            close(row, lease);
            return false;
        }
        LOG.info("Creating Google Calendar event for booking {}", id);
        var event = google.createEvent(id, row, text(row.get("meeting_url")));

        LOG.info("Google Calendar event created for booking {}", id);
        if (!db.ready(id, lease, event.id(), event.htmlLink())) {
            reconcileAfterSideEffect(db.get(id), lease);
            return false;
        }
        return true;
    }

    private void close(
        Map<String, Object> row,
        UUID lease) {
        if (!ownsLease(row, lease)) {
            return;
        }
        cleanupProvider(row, lease);
        db.ended(uuid(row.get("id")), lease, cancelled(row));
    }

    private void reconcileAfterSideEffect(
        Map<String, Object> row,
        UUID lease) {
        if (active(row) && row.get("meeting_ended_at") == null) {
            return;
        }
        if (ownsLease(row, lease)) {
            close(row, lease);
            return;
        }
        // Lease fencing cannot undo an HTTP request already in flight. Rooms
        // belong to exactly one booking, so terminal cleanup is safe to repeat.
        db.requeueCleanup(uuid(row.get("id")));
        cleanupProvider(row, null);
    }

    private void cleanupProvider(
        Map<String, Object> row,
        UUID lease) {
        RuntimeException failure = null;
        UUID id = uuid(row.get("id"));
        String space = text(row.get("meeting_space_name"));
        if (!space.isBlank()) {
            try {
                heartbeat(id, lease);
                google.restrictSpace(space);
            } catch (RuntimeException ex) {
                failure = ex;
            }
            try {
                heartbeat(id, lease);
                google.endConference(space);
            } catch (RuntimeException ex) {
                failure = ex;
            }
        }
        if (cancelled(row) && !text(row.get("calendar_event_id")).isBlank()) {
            try {
                heartbeat(id, lease);
                google.cancelEvent(id);
            } catch (RuntimeException ex) {
                failure = ex;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static boolean cancelled(
        Map<String, Object> row) {
        return (!"ACCEPTED".equals(row.get("status")) && !"COMPLETED".equals(row.get("status")))
            || row.get("meeting_opened_at") == null;
    }

    private void heartbeat(
        UUID id,
        UUID lease) {
        if (lease != null && !db.renew(id, lease)) {
            throw ApiException.conflict("Meeting work was reassigned; another worker will recover it.");
        }
    }

    private Map<String, Object> refresh(
        UUID id,
        UUID lease) {
        heartbeat(id, lease);
        return db.get(id);
    }

    private boolean ownsLease(
        Map<String, Object> row,
        UUID lease) {
        return lease.equals(row.get("meeting_lease_token"))
            && time(row.get("meeting_lease_until")).isAfter(clock.instant());
    }

    private boolean active(
        Map<String, Object> row) {
        return "ACCEPTED".equals(row.get("status")) && time(row.get("ends_at")).isAfter(clock.instant());
    }

    private static void validateWindow(
        Map<String, Object> row) {
        long seconds = time(row.get("ends_at")).getEpochSecond() - time(row.get("starts_at")).getEpochSecond();
        if (seconds <= 0 || seconds > 3600) {
            throw ApiException.conflict("A consultation meeting must last no more than 60 minutes");
        }
    }

    private static Instant time(
        Object value) {
        if (value instanceof OffsetDateTime date) {
            return date.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return OffsetDateTime.parse(text(value)).toInstant();
    }
}
