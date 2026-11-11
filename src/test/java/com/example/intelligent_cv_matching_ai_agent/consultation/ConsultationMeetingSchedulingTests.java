package com.example.intelligent_cv_matching_ai_agent.consultation;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation.ConsultationMeetingRepository;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.ConsultationMeetingService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.GoogleConsultationMeetingClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsultationMeetingSchedulingTests {

    private static final Instant NOW = Instant.parse("2026-11-06T05:00:00Z");

    private static final String CLOSING_SPACE = "spaces/expiredConsultation";

    @Test
    void saturatedProvisioningCannotDelayAnExpiredRoomClosure() throws InterruptedException {
        ConsultationMeetingRepository db = mock(ConsultationMeetingRepository.class);
        GoogleConsultationMeetingClient google = mock(GoogleConsultationMeetingClient.class);
        ConsultationMeetingService service =
            new ConsultationMeetingService(db, google, Clock.fixed(NOW, ZoneOffset.UTC));
        CountDownLatch provisioningEntered = new CountDownLatch(4);
        CountDownLatch releaseProvisioning = new CountDownLatch(1);
        CountDownLatch provisioningFinished = new CountDownLatch(4);
        CountDownLatch conferenceEnded = new CountDownLatch(1);
        CountDownLatch closurePersisted = new CountDownLatch(1);
        AtomicBoolean endedBeforeProvisioningRelease = new AtomicBoolean();
        Map<UUID, Map<String, Object>> rows = new ConcurrentHashMap<>();
        List<Map<String, Object>> provisioningJobs = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            UUID id = UUID.randomUUID();
            rows.put(id, row(id, false));
            provisioningJobs.add(Map.of("id", id, "meeting_work", "PROVISION"));
        }
        UUID closingId = UUID.randomUUID();
        rows.put(closingId, row(closingId, true));
        when(db.due()).thenReturn(provisioningJobs, List.of(Map.of("id", closingId, "meeting_work", "CLOSE")));
        when(db.claim(any(UUID.class), any(UUID.class))).thenAnswer(invocation -> {
            Map<String, Object> row = rows.get(invocation.getArgument(0, UUID.class));
            row.put("meeting_lease_token", invocation.getArgument(1, UUID.class));
            row.put("meeting_lease_until", NOW.plusSeconds(600));
            return true;
        });
        when(db.get(any(UUID.class)))
            .thenAnswer(invocation -> new LinkedHashMap<>(rows.get(invocation.getArgument(0, UUID.class))));
        when(db.renew(any(UUID.class), any(UUID.class))).thenAnswer(invocation -> {
            Map<String, Object> row = rows.get(invocation.getArgument(0, UUID.class));
            return invocation.getArgument(1, UUID.class).equals(row.get("meeting_lease_token"));
        });
        when(google.createSpace()).thenAnswer(invocation -> {
            provisioningEntered.countDown();
            if (!releaseProvisioning.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Synthetic blocked provider was not released by the test");
            }
            return new GoogleConsultationMeetingClient.MeetingSpace(
                "spaces/unadvertisedTestRoom",
                "https://meet.google.com/abc-defg-hij"
            );
        });
        doAnswer(invocation -> {
            endedBeforeProvisioningRelease
                .set(releaseProvisioning.getCount() == 1 && provisioningFinished.getCount() == 4);
            conferenceEnded.countDown();
            return null;
        }).when(google).endConference(CLOSING_SPACE);
        doAnswer(invocation -> {
            closurePersisted.countDown();
            return null;
        }).when(db).ended(eq(closingId), any(UUID.class), eq(false));
        doAnswer(invocation -> {
            if (!closingId.equals(invocation.getArgument(0, UUID.class))) {
                provisioningFinished.countDown();
            }
            return null;
        }).when(db).release(any(UUID.class), any(UUID.class));

        try {
            service.synchronize();
            assertTrue(provisioningEntered.await(3, TimeUnit.SECONDS), "All four provisioning workers must be blocked");
            service.synchronize();
            assertTrue(
                conferenceEnded.await(2, TimeUnit.SECONDS),
                "Expired room must close while provisioning is blocked"
            );
            assertTrue(closurePersisted.await(2, TimeUnit.SECONDS), "Closing work must finish without provisioning");
            assertTrue(endedBeforeProvisioningRelease.get());
            assertEquals(1L, releaseProvisioning.getCount());
            verify(google, times(4)).createSpace();
            verify(google).restrictSpace(CLOSING_SPACE);
            verify(google).endConference(CLOSING_SPACE);
            verify(google, never()).cancelEvent(closingId);
        } finally {
            releaseProvisioning.countDown();
            try {
                provisioningFinished.await(5, TimeUnit.SECONDS);
            } finally {
                service.stop();
            }
        }
    }

    private static Map<String, Object> row(
        UUID id,
        boolean expired) {
        Map<String, Object> row = new ConcurrentHashMap<>();
        row.put("id", id);
        row.put("status", expired ? "COMPLETED" : "ACCEPTED");
        row.put("starts_at", expired ? NOW.minusSeconds(3601) : NOW.plusSeconds(3600));
        row.put("ends_at", expired ? NOW.minusSeconds(1) : NOW.plusSeconds(7200));
        row.put("meeting_status", expired ? "READY" : "PENDING");
        if (expired) {
            row.put("meeting_space_name", CLOSING_SPACE);
            row.put("meeting_opened_at", NOW.minusSeconds(3600));
        }
        return row;
    }
}
