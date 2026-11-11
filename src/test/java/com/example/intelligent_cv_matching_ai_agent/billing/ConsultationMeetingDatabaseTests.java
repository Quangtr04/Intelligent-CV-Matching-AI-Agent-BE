package com.example.intelligent_cv_matching_ai_agent.billing;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation.ConsultationBookingRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation.ConsultationMeetingRepository;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.GoogleConsultationMeetingClient;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL repository transitions with synthetic fixtures rolled back after every scenario. */
@EnabledIfSystemProperty(named = "consultation.testDatabaseUrl", matches = ".+")
class ConsultationMeetingDatabaseTests {

    private static final String MEETING_URL = "https://meet.google.com/abc-defg-hij";

    private static final String EVENT_URL = "https://calendar.google.com/calendar/event?eid=synthetic";

    private record Fixture(
        SqlStore sql,
        ConsultationMeetingRepository meetings,
        ConsultationBookingRepository bookings,
        UUID candidate,
        UUID consultant,
        UUID booking
    ) {
    }

    @Test
    void approvalPersistsPendingMeetingWorkBeforeAnyProviderCall() {
        scenario(fixture -> {
            assertEquals("NOT_REQUESTED", row(fixture).get("meeting_status"));
            fixture.bookings().accept(fixture.booking());
            Map<String, Object> accepted = row(fixture);
            assertEquals("ACCEPTED", accepted.get("status"));
            assertEquals("PENDING", accepted.get("meeting_status"));
            assertNotNull(accepted.get("accepted_at"));
            assertNotNull(accepted.get("meeting_next_attempt_at"));
            assertNull(accepted.get("meeting_space_name"));
            assertEquals("PROVISION", dueWork(fixture));
        });
    }

    @Test
    void liveLeaseRejectsSecondClaimAndWrongTokenWrites() {
        scenario(fixture -> {
            UUID lease = approveAndClaim(fixture);
            UUID other = UUID.randomUUID();
            assertFalse(fixture.meetings().claim(fixture.booking(), other));
            assertFalse(fixture.meetings().renew(fixture.booking(), other));
            assertFalse(
                fixture.meetings().saveSpace(fixture.booking(), other, space(fixture), MEETING_URL, eventId(fixture))
            );
            fixture.meetings().failed(fixture.booking(), other, "Must not overwrite another worker");
            fixture.meetings().release(fixture.booking(), other);
            Map<String, Object> stored = row(fixture);
            assertEquals(lease, stored.get("meeting_lease_token"));
            assertNull(stored.get("meeting_space_name"));
            assertNull(stored.get("meeting_error"));
            assertEquals(0, ((Number) stored.get("meeting_attempts")).intValue());
        });
    }

    @Test
    void expiredLeaseCanBeReclaimedAndOldTokenCannotPersistAProviderResult() {
        scenario(fixture -> {
            UUID oldLease = approveAndClaim(fixture);
            fixture.sql().update(
                ("UPDATE consultation_bookings SET meeting_lease_until=now()-interval " + "'1 second' WHERE id=?"),
                fixture.booking()
            );
            UUID newLease = UUID.randomUUID();
            assertTrue(fixture.meetings().claim(fixture.booking(), newLease));
            assertFalse(
                fixture.meetings().saveSpace(fixture.booking(), oldLease, space(fixture), MEETING_URL, eventId(fixture))
            );
            assertTrue(
                fixture.meetings().saveSpace(fixture.booking(), newLease, space(fixture), MEETING_URL, eventId(fixture))
            );
            fixture.meetings().release(fixture.booking(), oldLease);
            assertEquals(newLease, row(fixture).get("meeting_lease_token"));
        });
    }

    @Test
    void savedSpaceAndPlannedCalendarIdSurviveLeaseReleaseForRecovery() {
        scenario(fixture -> {
            UUID lease = approveAndClaim(fixture);
            saveSpace(fixture, lease);
            fixture.meetings().release(fixture.booking(), lease);
            Map<String, Object> stored = new ConsultationMeetingRepository(fixture.sql()).get(fixture.booking());
            assertEquals(space(fixture), stored.get("meeting_space_name"));
            assertEquals(MEETING_URL, stored.get("meeting_url"));
            assertEquals(eventId(fixture), stored.get("calendar_event_id"));
            assertEquals("PENDING", stored.get("meeting_status"));
            assertNull(stored.get("meeting_lease_token"));
            assertTrue(fixture.meetings().claim(fixture.booking(), UUID.randomUUID()));
        });
    }

    @Test
    void readyTransitionSendsExactlyOneNotificationToEachParticipant() {
        scenario(fixture -> {
            UUID lease = approveAndClaim(fixture);
            saveSpace(fixture, lease);
            assertTrue(fixture.meetings().ready(fixture.booking(), lease, eventId(fixture), EVENT_URL));
            assertFalse(fixture.meetings().ready(fixture.booking(), lease, eventId(fixture), EVENT_URL));
            List<Map<String, Object>> notices = fixture.sql().list(
                ("SELECT user_id FROM notifications WHERE reference_id=? AND "
                    + "type='CONSULTATION_BOOKING_MEETING_READY' ORDER BY user_id"),
                fixture.booking()
            );
            assertEquals(2, notices.size());
            assertEquals(
                Set.of(fixture.candidate(), fixture.consultant()),
                Set.copyOf(notices.stream().map(notice -> (UUID) notice.get("user_id")).toList())
            );
            assertEquals("READY", row(fixture).get("meeting_status"));
            assertEquals(EVENT_URL, row(fixture).get("calendar_event_url"));
            assertEquals(
                true,
                fixture.sql().scalar(
                    "SELECT meeting_next_attempt_at=starts_at FROM consultation_bookings WHERE id=?",
                    fixture.booking()
                )
            );
        });
    }

    @Test
    void publicBookingOmitsWorkerLeaseAndInvitationEmailFields() {
        scenario(fixture -> {
            UUID lease = approveAndClaim(fixture);
            saveSpace(fixture, lease);
            assertTrue(fixture.meetings().ready(fixture.booking(), lease, eventId(fixture), EVENT_URL));
            Map<String, Object> internal = row(fixture);
            assertNotNull(internal.get("meeting_lease_token"));
            assertNotNull(internal.get("candidate_email"));
            assertNotNull(internal.get("consultant_email"));
            Map<String, Object> publicBooking = fixture.bookings().booking(fixture.booking());
            assertEquals(MEETING_URL, publicBooking.get("meeting_url"));
            assertTrue(
                publicBooking.keySet().stream().noneMatch(key -> key.contains("lease") || key.endsWith("_email"))
            );
        });
    }

    @Test
    void futureReadyBookingCannotRecordAnEarlyOpening() {
        scenario(fixture -> {
            UUID lease = approveAndClaim(fixture);
            saveSpace(fixture, lease);
            assertTrue(fixture.meetings().ready(fixture.booking(), lease, eventId(fixture), EVENT_URL));
            assertFalse(fixture.meetings().opened(fixture.booking(), lease));
            assertNull(row(fixture).get("meeting_opened_at"));
            assertEquals("READY", row(fixture).get("meeting_status"));
        });
    }

    @Test
    void acceptedReadyBookingRecordsOpeningOnlyInsideItsWindow() {
        scenario(fixture -> {
            UUID lease = approveAndClaim(fixture);
            saveSpace(fixture, lease);
            assertTrue(fixture.meetings().ready(fixture.booking(), lease, eventId(fixture), EVENT_URL));
            fixture.sql().update(
                ("UPDATE consultation_bookings SET starts_at=now()-interval '10 "
                    + "minutes',ends_at=now()+interval '20 minutes' WHERE id=?"),
                fixture.booking()
            );
            assertTrue(fixture.meetings().opened(fixture.booking(), lease));
            Object openedAt = row(fixture).get("meeting_opened_at");
            assertNotNull(openedAt);
            assertTrue(fixture.meetings().opened(fixture.booking(), lease));
            assertEquals(openedAt, row(fixture).get("meeting_opened_at"));
            assertEquals(
                true,
                fixture.sql().scalar(
                    "SELECT meeting_next_attempt_at=ends_at FROM consultation_bookings WHERE id=?",
                    fixture.booking()
                )
            );
        });
    }

    @Test
    void lateProviderCompletionCanRequeueAnAlreadyEndedCancellationForCleanup() {
        scenario(fixture -> {
            UUID lease = approveAndClaim(fixture);
            saveSpace(fixture, lease);
            assertTrue(fixture.meetings().ready(fixture.booking(), lease, eventId(fixture), EVENT_URL));
            fixture.bookings().end(fixture.booking(), "CANCELLED", true);
            fixture.meetings().ended(fixture.booking(), lease, true);
            assertNotNull(row(fixture).get("meeting_ended_at"));
            assertFalse(fixture.meetings().renew(fixture.booking(), lease));
            fixture.meetings().release(fixture.booking(), lease);
            assertNull(dueWork(fixture));
            fixture.meetings().requeueCleanup(fixture.booking());
            assertNull(row(fixture).get("meeting_ended_at"));
            assertEquals("CLOSE", dueWork(fixture));
            assertEquals(space(fixture), row(fixture).get("meeting_space_name"));
            assertEquals(eventId(fixture), row(fixture).get("calendar_event_id"));
            assertTrue(fixture.meetings().claim(fixture.booking(), UUID.randomUUID()));
        });
    }

    @Test
    void provisioningFailureBackoffCannotSkipPastTheScheduledEnd() {
        scenario(fixture -> {
            UUID lease = approveAndClaim(fixture);
            fixture.sql().update(
                ("UPDATE consultation_bookings SET starts_at=now()-interval '29 minutes "
                    + "59 seconds',ends_at=now()+interval '1 second',meeting_attempts=6 " + "WHERE id=?"),
                fixture.booking()
            );
            fixture.meetings().failed(fixture.booking(), lease, "Synthetic provider failure");
            Map<String, Object> failed = row(fixture);
            assertEquals("FAILED", failed.get("meeting_status"));
            assertEquals(7, ((Number) failed.get("meeting_attempts")).intValue());
            assertEquals("Synthetic provider failure", failed.get("meeting_error"));
            assertEquals(
                true,
                fixture.sql().scalar(
                    "SELECT meeting_next_attempt_at=ends_at FROM consultation_bookings WHERE id=?",
                    fixture.booking()
                )
            );
        });
    }

    @Test
    void cancelledMeetingCleanupFailureRetriesWithinFiveSeconds() {
        scenario(fixture -> {
            UUID lease = approveAndClaim(fixture);
            saveSpace(fixture, lease);
            fixture.bookings().end(fixture.booking(), "CANCELLED", true);
            fixture.sql().update("UPDATE consultation_bookings SET meeting_attempts=6 WHERE id=?", fixture.booking());
            fixture.meetings().failed(fixture.booking(), lease, "Synthetic cleanup failure");
            assertEquals("CANCELLED", row(fixture).get("meeting_status"));
            assertNull(row(fixture).get("meeting_ended_at"));
            assertEquals(
                true,
                fixture.sql().scalar(
                    ("SELECT extract(epoch FROM (meeting_next_attempt_at-now())) BETWEEN 0 "
                        + "AND 5 FROM consultation_bookings WHERE id=?"),
                    fixture.booking()
                )
            );
            assertEquals(space(fixture), row(fixture).get("meeting_space_name"));
        });
    }

    @Test
    void cancellationWinningBeforeReadyCannotBeOverwrittenAndRetainsCleanupResources() {
        scenario(fixture -> {
            UUID lease = approveAndClaim(fixture);
            saveSpace(fixture, lease);
            fixture.bookings().end(fixture.booking(), "CANCELLED", true);
            assertFalse(fixture.meetings().ready(fixture.booking(), lease, eventId(fixture), EVENT_URL));
            Map<String, Object> cancelled = row(fixture);
            assertEquals("CANCELLED", cancelled.get("status"));
            assertEquals("CANCELLED", cancelled.get("meeting_status"));
            assertEquals(space(fixture), cancelled.get("meeting_space_name"));
            assertEquals(MEETING_URL, cancelled.get("meeting_url"));
            assertEquals(eventId(fixture), cancelled.get("calendar_event_id"));
            assertEquals(
                0L,
                ((Number) fixture.sql().scalar(
                    ("SELECT count(*) FROM notifications WHERE reference_id=? AND "
                        + "type='CONSULTATION_BOOKING_MEETING_READY'"),
                    fixture.booking()
                )).longValue()
            );
            fixture.meetings().release(fixture.booking(), lease);
            assertEquals("CLOSE", dueWork(fixture));
        });
    }

    private void scenario(
        Consumer<Fixture> action) {
        String url = System.getProperty("consultation.testDatabaseUrl");
        if (url == null || !url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[a-z_]+_test")) {
            throw new IllegalArgumentException("A disposable localhost _test database is required");
        }
        var source = new DriverManagerDataSource(url, "edunexus_test", "");
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            status.setRollbackOnly();
            SqlStore sql = new SqlStore(new JdbcTemplate(source));
            action.accept(fixture(sql));
        });
    }

    private Fixture fixture(
        SqlStore sql) {
        UUID candidate = person(sql, "CANDIDATE");
        UUID consultant = person(sql, "CAREER_CONSULTANT");
        UUID product = (UUID) sql.scalar("SELECT id FROM consultation_services WHERE service_code='CAREER_PACK_5_30'");
        UUID credit = sql.insert(
            "consultation_orders",
            fields(
                "candidate_user_id",
                candidate,
                "service_id",
                product,
                "service_code",
                "CAREER_PACK_5_30",
                "service_name",
                "Synthetic five-session pack",
                "delivery_mode",
                "LIVE",
                "duration_minutes",
                30,
                "amount",
                495000,
                "currency",
                "VND",
                "subject",
                "Synthetic session credits",
                "description",
                "Repository integration fixture",
                "sessions_total",
                5,
                "sessions_remaining",
                4,
                "is_session_pack",
                true,
                "status",
                "PAID"
            )
        );
        UUID thread = sql.insert(
            "consultation_threads",
            fields(
                "candidate_user_id",
                candidate,
                "consultant_user_id",
                consultant,
                "request_type",
                "SESSION_BOOKING",
                "subject",
                "Synthetic scheduled consultation",
                "status",
                "OPEN"
            )
        );
        OffsetDateTime start = instant(sql.scalar("SELECT now()")).atOffset(ZoneOffset.UTC).plusHours(1);
        UUID booking = sql.insert(
            "consultation_bookings",
            fields(
                "candidate_user_id",
                candidate,
                "consultant_user_id",
                consultant,
                "credit_order_id",
                credit,
                "target_role",
                "Backend Developer",
                "subject",
                "Synthetic scheduled consultation",
                "description",
                "No real Google requests are made",
                "starts_at",
                start,
                "ends_at",
                start.plusMinutes(30),
                "duration_minutes",
                30,
                "status",
                "PENDING",
                "thread_id",
                thread
            )
        );
        return new Fixture(
            sql,
            new ConsultationMeetingRepository(sql),
            new ConsultationBookingRepository(sql),
            candidate,
            consultant,
            booking
        );
    }

    private UUID person(
        SqlStore sql,
        String role) {
        return sql.insert(
            "users",
            fields(
                "email",
                UUID.randomUUID() + "@example.test",
                "auth_provider",
                "GOOGLE",
                "google_sub",
                UUID.randomUUID().toString(),
                "full_name",
                "Synthetic meeting " + role,
                "role",
                role,
                "status",
                "ACTIVE"
            )
        );
    }

    private UUID approveAndClaim(
        Fixture fixture) {
        fixture.bookings().accept(fixture.booking());
        UUID lease = UUID.randomUUID();
        assertTrue(fixture.meetings().claim(fixture.booking(), lease));
        return lease;
    }

    private void saveSpace(
        Fixture fixture,
        UUID lease) {
        assertTrue(
            fixture.meetings().saveSpace(fixture.booking(), lease, space(fixture), MEETING_URL, eventId(fixture))
        );
    }

    private Map<String, Object> row(
        Fixture fixture) {
        return fixture.meetings().get(fixture.booking());
    }

    private String dueWork(
        Fixture fixture) {
        return fixture.meetings().due().stream().filter(item -> fixture.booking().equals(item.get("id")))
            .map(item -> item.get("meeting_work").toString()).findFirst().orElse(null);
    }

    private String space(
        Fixture fixture) {
        return "spaces/test" + fixture.booking().toString().replace("-", "");
    }

    private String eventId(
        Fixture fixture) {
        return GoogleConsultationMeetingClient.eventId(fixture.booking());
    }

    private Instant instant(
        Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof OffsetDateTime date) {
            return date.toInstant();
        }
        return (Instant) value;
    }
}
