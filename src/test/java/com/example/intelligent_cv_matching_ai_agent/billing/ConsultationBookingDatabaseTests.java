package com.example.intelligent_cv_matching_ai_agent.billing;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.consultation.ConsultationBookingRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.consultation.ConsultationRatingRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultantBusyRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultantProfileRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation.ConsultationBookingRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.ConsultationBookingService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.ConsultationPreparationService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.ConsultationService;
import com.example.intelligent_cv_matching_ai_agent.service.consultant.consultation.ConsultationAnalysisService;
import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultationAnalysisRequest;
import com.example.intelligent_cv_matching_ai_agent.repository.consultant.consultation.ConsultationAnalysisRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation.ConsultationRepository;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "consultation.testDatabaseUrl", matches = ".+")
class ConsultationBookingDatabaseTests {

    private final Map<UUID, UUID> resumesByCredit = new HashMap<>();

    private void scenario(
        Consumer<SqlStore> action) {
        String url = System.getProperty("consultation.testDatabaseUrl");
        if (!url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[a-z_]+_test")) {
            throw new IllegalArgumentException("A disposable localhost _test database is required");
        }
        var source = new DriverManagerDataSource(url, "edunexus_test", "");
        try {
            new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
                status.setRollbackOnly();
                action.accept(new SqlStore(new JdbcTemplate(source)));
            });
        } finally {
            SecurityContextHolder.clearContext();
        }
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
                "Booking " + role,
                "role",
                role,
                "status",
                "ACTIVE"
            )
        );
    }

    private ConsultationBookingService as(
        SqlStore sql,
        UUID id,
        String role) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                id.toString(),
                "test",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
            )
        );
        return new ConsultationBookingService(new ConsultationBookingRepository(sql), new CurrentUser());
    }

    private UUID pack(
        SqlStore sql,
        UUID owner,
        int credits) {
        UUID product = (UUID) sql.scalar("SELECT id FROM consultation_services WHERE service_code='CAREER_PACK_5_30'");
        UUID credit = sql.insert(
            "consultation_orders",
            fields(
                "candidate_user_id",
                owner,
                "service_id",
                product,
                "service_code",
                "CAREER_PACK_5_30",
                "service_name",
                "5 sessions",
                "delivery_mode",
                "LIVE",
                "duration_minutes",
                30,
                "amount",
                495000,
                "currency",
                "VND",
                "subject",
                "Session pack",
                "description",
                "",
                "sessions_total",
                5,
                "sessions_remaining",
                credits,
                "is_session_pack",
                true,
                "status",
                "PAID"
            )
        );
        UUID resume = sql.insert(
            "resumes",
            fields(
                "user_id",
                owner,
                "title",
                "Booking CV",
                "source",
                "MANUAL",
                "status",
                "READY",
                "structured_data",
                sql.json(Map.of("summary", "Reviewed experience"))
            )
        );
        resumesByCredit.put(credit, resume);
        return credit;
    }

    private ConsultationBookingRequest request(
        UUID consultant,
        UUID credit,
        OffsetDateTime start) {
        return new ConsultationBookingRequest(
            consultant,
            credit,
            "BE",
            "Interview preparation",
            "Review Java API interview questions",
            start,
            30,
            resumesByCredit.get(credit),
            null
        );
    }

    private int remaining(
        SqlStore sql,
        UUID credit) {
        return ((Number) sql.scalar("SELECT sessions_remaining FROM consultation_orders WHERE id=?", credit))
            .intValue();
    }

    @Test
    void attachmentsAreOwnedAndReadyAndPreparationNeverLeaksOtherCvJobPairs() {
        scenario(sql -> {
            UUID candidate = person(sql, "CANDIDATE");
            UUID outsider = person(sql, "CANDIDATE");
            UUID consultant = person(sql, "CAREER_CONSULTANT");
            as(sql, consultant, "CAREER_CONSULTANT").saveProfile(new ConsultantProfileRequest(List.of("BE"), ""));
            UUID credit = pack(sql, candidate, 5);
            UUID selected = resumesByCredit.get(credit);
            UUID other = sql.insert(
                "resumes",
                fields(
                    "user_id",
                    candidate,
                    "title",
                    "Other CV",
                    "source",
                    "MANUAL",
                    "status",
                    "READY",
                    "is_primary",
                    true
                )
            );
            UUID foreign = sql.insert(
                "resumes",
                fields("user_id", outsider, "title", "Foreign CV", "source", "MANUAL", "status", "READY")
            );
            UUID selectedJob = job(sql);
            UUID otherJob = job(sql);
            OffsetDateTime start = OffsetDateTime.now().plusDays(2);
            var owner = as(sql, candidate, "CANDIDATE");
            assertThrows(
                ApiException.class,
                () -> owner.create(
                    new ConsultationBookingRequest(
                        consultant,
                        credit,
                        "BE",
                        "Review",
                        "Details",
                        start,
                        60,
                        foreign,
                        null
                    )
                )
            );
            sql.update("UPDATE resumes SET status='ARCHIVED',is_primary=false WHERE id=?", other);
            assertThrows(
                ApiException.class,
                () -> owner.create(
                    new ConsultationBookingRequest(
                        consultant,
                        credit,
                        "BE",
                        "Review",
                        "Details",
                        start,
                        60,
                        other,
                        null
                    )
                )
            );
            sql.update("UPDATE resumes SET status='READY',is_primary=true WHERE id=?", other);
            assertThrows(
                ApiException.class,
                () -> owner.create(
                    new ConsultationBookingRequest(
                        consultant,
                        credit,
                        "BE",
                        "Review",
                        "Details",
                        start,
                        60,
                        selected,
                        UUID.randomUUID()
                    )
                )
            );
            assertEquals(5, remaining(sql, credit));
            var booked = owner.create(
                new ConsultationBookingRequest(
                    consultant,
                    credit,
                    "BE",
                    "Review",
                    "Details",
                    start,
                    60,
                    selected,
                    selectedJob
                )
            );
            UUID id = (UUID) booked.get("id");
            UUID thread = (UUID) booked.get("thread_id");
            assertEquals(selected, booked.get("resume_id"));
            assertEquals(selectedJob, booked.get("job_id"));
            assertEquals(selected, sql.scalar("SELECT resume_id FROM consultation_threads WHERE id=?", thread));
            sql.insert(
                "resume_analyses",
                fields("resume_id", selected, "analysis_type", "RULE_BASED", "overall_score", 40)
            );
            sql.insert(
                "resume_analyses",
                fields("resume_id", other, "analysis_type", "RULE_BASED", "overall_score", 99)
            );
            for (Object[] pair : List.of(
                new Object[]{selected, selectedJob, 62},
                new Object[]{other, selectedJob, 99},
                new Object[]{selected, otherJob, 98}
            )) {
                sql.insert(
                    "job_resume_analyses",
                    fields("user_id", candidate, "resume_id", pair[0], "market_job_id", pair[1], "ats_score", pair[2])
                );
            }
            var preparation = new ConsultationPreparationService(
                new ConsultationBookingRepository(sql),
                new ConsultationAnalysisRepository(sql),
                new CurrentUser()
            );
            as(sql, consultant, "CAREER_CONSULTANT");
            var prepared = preparation.preparation(id);
            Map<?, ?> cv = (Map<?, ?>) prepared.get("resume");
            assertEquals(selected, cv.get("id"));
            assertFalse(cv.containsKey("file_url"));
            assertEquals(62, ((Number) ((Map<?, ?>) prepared.get("ats_analysis")).get("ats_score")).intValue());
            assertEquals(40, ((Number) ((Map<?, ?>) prepared.get("resume_analysis")).get("overall_score")).intValue());
            var profile = new ConsultationService(new ConsultationRepository(sql), new CurrentUser());
            assertEquals(selected, ((Map<?, ?>) profile.candidateProfile(thread, null).get("resume")).get("id"));
            assertThrows(ApiException.class, () -> profile.candidateProfile(thread, other));
            var analyses = new ConsultationAnalysisService(
                new ConsultationAnalysisRepository(sql),
                new ConsultationRepository(sql),
                new CurrentUser()
            );
            var onlySelected =
                analyses.candidateAnalyses(thread, new ConsultationAnalysisRequest(null, null, null, null));
            assertEquals(1, onlySelected.resumeAnalyses().size());
            assertEquals(1, onlySelected.atsAnalyses().size());
            assertTrue(onlySelected.matchingResults().isEmpty());
            as(sql, outsider, "CANDIDATE");
            assertThrows(ApiException.class, () -> preparation.preparation(id));
            as(sql, candidate, "CANDIDATE");
            UUID noJob = (UUID) owner.create(
                new ConsultationBookingRequest(
                    consultant,
                    credit,
                    "BE",
                    "No job",
                    "CV only",
                    start.plusHours(2),
                    30,
                    selected,
                    null
                )
            ).get("id");
            assertNull(preparation.preparation(noJob).get("ats_analysis"));
            sql.update("UPDATE consultation_bookings SET resume_id=NULL,job_id=NULL WHERE id=?", noJob);
            assertTrue(preparation.preparation(noJob).values().stream().allMatch(Objects::isNull));
            owner.cancel(id);
            as(sql, consultant, "CAREER_CONSULTANT");
            assertThrows(ApiException.class, () -> preparation.preparation(id));
            assertThrows(ApiException.class, () -> profile.candidateProfile(thread, null));
        });
    }

    private UUID job(
        SqlStore sql) {
        return sql.insert(
            "market_job_posts",
            fields(
                "source_site",
                "MANUAL",
                "raw_content_hash",
                UUID.randomUUID().toString(),
                "job_title",
                "Backend engineer",
                "company_name",
                "Test company",
                "role_category",
                "Backend",
                "raw_content",
                "Java backend work",
                "status",
                "ACTIVE"
            )
        );
    }

    @Test
    void acceptedSessionCompletesAtEndAndOnlyOwnerCanReviewOnce() {
        scenario(sql -> {
            UUID candidate = person(sql, "CANDIDATE");
            UUID consultant = person(sql, "CAREER_CONSULTANT");
            UUID outsider = person(sql, "CANDIDATE");
            var service = as(sql, consultant, "CAREER_CONSULTANT");
            service.saveProfile(new ConsultantProfileRequest(List.of("Backend Developer"), "Java backend expert"));
            UUID credit = pack(sql, candidate, 5);
            service = as(sql, candidate, "CANDIDATE");
            assertEquals(consultant, service.consultants("BE").getFirst().get("id"));
            assertTrue(service.consultants("Frontend").isEmpty());
            OffsetDateTime start = OffsetDateTime.now().plusDays(2);
            var booking = service.create(request(consultant, credit, start));
            UUID id = (UUID) booking.get("id");
            assertEquals("PENDING", booking.get("status"));
            assertEquals(4, remaining(sql, credit));
            var interval = service.availability(consultant, start.minusHours(1), start.plusHours(1)).getFirst();
            assertEquals(Set.of("id", "starts_at", "ends_at", "type"), interval.keySet());
            assertEquals("BOOKING", interval.get("type"));
            var stranger = as(sql, outsider, "CANDIDATE");
            assertTrue(stranger.bookings().isEmpty());
            assertThrows(ApiException.class, () -> stranger.cancel(id));
            assertThrows(ApiException.class, () -> stranger.rate(id, new ConsultationRatingRequest(5, "Great")));
            var adviser = as(sql, consultant, "CAREER_CONSULTANT");
            assertEquals("ACCEPTED", adviser.accept(id).get("status"));
            assertThrows(ApiException.class, () -> adviser.complete(id));
            var owner = as(sql, candidate, "CANDIDATE");
            assertThrows(ApiException.class, () -> owner.rate(id, new ConsultationRatingRequest(5, "Great")));
            sql.update(
                ("UPDATE consultation_bookings SET starts_at=now()-interval '1 hour',"
                    + "ends_at=now()-interval '30 minutes',meeting_status='ENDED',"
                    + "meeting_opened_at=now()-interval '1 hour' WHERE id=?"),
                id
            );
            owner.settleDue(id);
            owner.settleDue(id);
            assertEquals("COMPLETED", owner.bookings().getFirst().get("status"));
            assertEquals(4, remaining(sql, credit));
            assertEquals(
                1L,
                ((Number) sql.scalar(
                    ("SELECT count(*) FROM notifications WHERE user_id=? AND " + "type='CONSULTATION_RATING_REQUEST'"),
                    candidate
                )).longValue()
            );
            assertEquals(
                "CLOSED",
                sql.scalar("SELECT status FROM consultation_threads WHERE id=?", booking.get("thread_id"))
            );
            owner.rate(id, new ConsultationRatingRequest(5, "Helpful Java interview advice"));
            assertThrows(ApiException.class, () -> owner.rate(id, new ConsultationRatingRequest(1, "Second review")));
            var ranked = owner.consultants("Back-end").getFirst();
            assertEquals(5.0, ((Number) ranked.get("average_rating")).doubleValue());
            assertEquals(1, ((Number) ranked.get("rating_count")).intValue());
            assertEquals(1, ((Number) ranked.get("completed_count")).intValue());
        });
    }

    @Test
    void busyAndPendingIntervalsBlockOverlapButAdjacentSessionCanBeBooked() {
        scenario(sql -> {
            UUID candidate = person(sql, "CANDIDATE");
            UUID otherCandidate = person(sql, "CANDIDATE");
            UUID consultant = person(sql, "CAREER_CONSULTANT");
            var adviser = as(sql, consultant, "CAREER_CONSULTANT");
            adviser.saveProfile(new ConsultantProfileRequest(List.of("BE"), ""));
            OffsetDateTime start = OffsetDateTime.now().plusDays(3);
            adviser.addBusy(new ConsultantBusyRequest(start.minusHours(1), start, "Private appointment"));
            UUID credit = pack(sql, candidate, 5);
            UUID otherCredit = pack(sql, otherCandidate, 5);
            var owner = as(sql, candidate, "CANDIDATE");
            assertThrows(ApiException.class, () -> owner.create(request(consultant, credit, start.minusMinutes(15))));
            assertEquals(5, remaining(sql, credit));
            var booking = owner.create(request(consultant, credit, start));
            UUID id = (UUID) booking.get("id");
            var other = as(sql, otherCandidate, "CANDIDATE");
            assertThrows(
                ApiException.class,
                () -> other.create(request(consultant, otherCredit, start.plusMinutes(15)))
            );
            other.create(request(consultant, otherCredit, start.plusMinutes(30)));
            var adviserAgain = as(sql, consultant, "CAREER_CONSULTANT");
            assertThrows(
                ApiException.class,
                () -> adviserAgain
                    .addBusy(new ConsultantBusyRequest(start, start.plusMinutes(10), "Cannot overwrite booking"))
            );
            adviserAgain.decline(id);
            assertEquals(5, remaining(sql, credit));
            assertThrows(ApiException.class, () -> adviserAgain.decline(id));
            assertEquals(5, remaining(sql, credit));
        });
    }

    @Test
    void paidPackOwnerDurationAndBalanceAreRequiredAndCancellationRefundsOnce() {
        scenario(sql -> {
            UUID candidate = person(sql, "CANDIDATE");
            UUID outsider = person(sql, "CANDIDATE");
            UUID consultant = person(sql, "CAREER_CONSULTANT");
            as(sql, consultant, "CAREER_CONSULTANT").saveProfile(new ConsultantProfileRequest(List.of("BE"), ""));
            UUID credit = pack(sql, candidate, 1);
            UUID foreignCredit = pack(sql, outsider, 5);
            OffsetDateTime start = OffsetDateTime.now().plusDays(4);
            var owner = as(sql, candidate, "CANDIDATE");
            assertThrows(ApiException.class, () -> owner.create(request(consultant, foreignCredit, start)));
            assertThrows(
                ApiException.class,
                () -> owner.create(
                    new ConsultationBookingRequest(
                        consultant,
                        credit,
                        "BE",
                        "Interview",
                        "Details",
                        start,
                        61,
                        resumesByCredit.get(credit),
                        null
                    )
                )
            );
            sql.update("UPDATE consultation_orders SET status='PENDING_PAYMENT' WHERE id=?", credit);
            assertThrows(ApiException.class, () -> owner.create(request(consultant, credit, start)));
            sql.update("UPDATE consultation_orders SET status='PAID' WHERE id=?", credit);
            UUID id = (UUID) owner.create(request(consultant, credit, start)).get("id");
            assertEquals(0, remaining(sql, credit));
            assertThrows(ApiException.class, () -> owner.create(request(consultant, credit, start.plusHours(1))));
            owner.cancel(id);
            assertEquals(1, remaining(sql, credit));
            assertThrows(ApiException.class, () -> owner.cancel(id));
            assertEquals(1, remaining(sql, credit));
        });
    }

    @Test
    void unacceptedRequestExpiresAndRefundsEvenWhenConsultantBecomesInactive() {
        scenario(sql -> {
            UUID candidate = person(sql, "CANDIDATE");
            UUID consultant = person(sql, "CAREER_CONSULTANT");
            as(sql, consultant, "CAREER_CONSULTANT").saveProfile(new ConsultantProfileRequest(List.of("BE"), ""));
            UUID credit = pack(sql, candidate, 5);
            var owner = as(sql, candidate, "CANDIDATE");
            UUID id = (UUID) owner.create(request(consultant, credit, OffsetDateTime.now().plusDays(1))).get("id");
            sql.update(
                ("UPDATE consultation_bookings SET starts_at=now()-interval '5 minutes',"
                    + "ends_at=now()+interval '25 minutes' WHERE id=?"),
                id
            );
            sql.update("UPDATE users SET status='SUSPENDED' WHERE id=?", consultant);
            owner.settleDue(id);
            owner.settleDue(id);
            assertEquals("DECLINED", owner.bookings().getFirst().get("status"));
            assertEquals(5, remaining(sql, credit));
            assertEquals(true, owner.bookings().getFirst().get("credit_refunded"));
        });
    }
}
