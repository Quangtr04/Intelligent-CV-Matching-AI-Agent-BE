package com.example.intelligent_cv_matching_ai_agent.billing;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.consultation.ConsultationBookingRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation.ConsultationBookingRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.ConsultationBookingService;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
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

/** Real concurrent transactions; committed synthetic fixtures are removed by exact UUID in finally. */
@EnabledIfSystemProperty(named = "consultation.testDatabaseUrl", matches = ".+")
class ConsultationBookingConcurrencyDatabaseTests {

    @Test
    void overlappingRequestsForSameConsultantOnlyReserveOneSession() throws Exception {
        try (Fixture fixture = new Fixture()) {
            UUID firstCandidate = fixture.person("CANDIDATE");
            UUID secondCandidate = fixture.person("CANDIDATE");
            UUID consultant = fixture.consultant();
            UUID firstCredit = fixture.pack(firstCandidate);
            UUID secondCredit = fixture.pack(secondCandidate);
            OffsetDateTime start = OffsetDateTime.now().plusDays(2);
            List<Integer> outcomes = race(
                () -> fixture.book(firstCandidate, consultant, firstCredit, start),
                () -> fixture.book(secondCandidate, consultant, secondCredit, start)
            );
            assertEquals(List.of(200, 409), outcomes.stream().sorted().toList());
            assertEquals(
                1,
                ((Number) fixture.sql
                    .scalar("SELECT count(*) FROM consultation_bookings WHERE consultant_user_id=?", consultant))
                    .intValue()
            );
            assertEquals(1, fixture.remaining(firstCredit) + fixture.remaining(secondCredit));
        }
    }

    @Test
    void simultaneousRequestsCannotSpendSameFinalCreditOnDifferentConsultants() throws Exception {
        try (Fixture fixture = new Fixture()) {
            UUID candidate = fixture.person("CANDIDATE");
            UUID firstConsultant = fixture.consultant();
            UUID secondConsultant = fixture.consultant();
            UUID credit = fixture.pack(candidate);
            OffsetDateTime firstTime = OffsetDateTime.now().plusDays(2);
            // Separate times isolate credit exhaustion from the candidate-overlap guard.
            OffsetDateTime secondTime = firstTime.plusDays(1);
            List<Integer> outcomes = race(
                () -> fixture.book(candidate, firstConsultant, credit, firstTime),
                () -> fixture.book(candidate, secondConsultant, credit, secondTime)
            );
            assertEquals(List.of(200, 409), outcomes.stream().sorted().toList());
            assertEquals(0, fixture.remaining(credit));
            assertEquals(
                1,
                ((Number) fixture.sql
                    .scalar("SELECT count(*) FROM consultation_bookings WHERE candidate_user_id=?", candidate))
                    .intValue()
            );
        }
    }

    @Test
    void cancellationFailsPromptlyDuringEvaluationAndRefundsExactlyOnceAfterRetry() throws Exception {
        evaluationLockDoesNotBlockBookingClose(false);
    }

    @Test
    void completionFailsPromptlyDuringEvaluationAndSendsExactlyOneRatingPromptAfterRetry() throws Exception {
        evaluationLockDoesNotBlockBookingClose(true);
    }

    private void evaluationLockDoesNotBlockBookingClose(
        boolean complete)
        throws Exception {
        try (Fixture fixture = new Fixture()) {
            UUID candidate = fixture.person("CANDIDATE");
            UUID consultant = fixture.consultant();
            UUID credit = fixture.pack(candidate);
            assertEquals(200, fixture.book(candidate, consultant, credit, OffsetDateTime.now().plusDays(2)));
            Map<String, Object> row =
                fixture.sql.one("SELECT id,thread_id FROM consultation_bookings WHERE candidate_user_id=?", candidate);
            UUID booking = (UUID) row.get("id");
            UUID thread = (UUID) row.get("thread_id");
            String initialStatus = complete ? "ACCEPTED" : "PENDING";
            if (complete) {
                OffsetDateTime start = OffsetDateTime.now().minusHours(2);
                fixture.sql.update(
                    "UPDATE consultation_bookings SET status='ACCEPTED',accepted_at=?,starts_at=?,ends_at=? WHERE id=?",
                    start.minusHours(1),
                    start,
                    start.plusMinutes(30),
                    booking
                );
            }
            UUID actor = complete ? consultant : candidate;
            String role = complete ? "CAREER_CONSULTANT" : "CANDIDATE";
            CountDownLatch shareHeld = new CountDownLatch(1);
            CountDownLatch releaseShare = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            List<Future<?>> workers = new ArrayList<>();
            try {
                Future<?> evaluation = executor.submit(() -> fixture.transaction.executeWithoutResult(status -> {
                    fixture.sql.one("SELECT id FROM consultation_threads WHERE id=? FOR SHARE", thread);
                    shareHeld.countDown();
                    try {
                        assertTrue(releaseShare.await(10, TimeUnit.SECONDS), "The evaluation lock must be released");
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Evaluation lock worker interrupted", ex);
                    }
                }));
                workers.add(evaluation);
                assertTrue(shareHeld.await(5, TimeUnit.SECONDS), "Evaluation must acquire the thread share lock");
                Future<Integer> attempt = executor.submit(() -> fixture.transition(actor, role, booking, complete));
                workers.add(attempt);
                // The share lock remains held until this assertion completes. A blocking close would time out.
                assertEquals(409, attempt.get(3, TimeUnit.SECONDS));
                Map<String, Object> unchanged = fixture.sql
                    .one("SELECT status,credit_refunded,completed_at FROM consultation_bookings WHERE id=?", booking);
                assertEquals(initialStatus, unchanged.get("status"));
                assertEquals(false, unchanged.get("credit_refunded"));
                assertNull(unchanged.get("completed_at"));
                assertEquals(0, fixture.remaining(credit));
                assertEquals("OPEN", fixture.sql.scalar("SELECT status FROM consultation_threads WHERE id=?", thread));
                assertEquals(0, fixture.ratingPrompts(candidate, thread));
                releaseShare.countDown();
                evaluation.get(5, TimeUnit.SECONDS);

                assertEquals(200, fixture.transition(actor, role, booking, complete));
                assertEquals(
                    complete ? "COMPLETED" : "CANCELLED",
                    fixture.sql.scalar("SELECT status FROM consultation_bookings WHERE id=?", booking)
                );
                assertEquals(
                    "CLOSED",
                    fixture.sql.scalar("SELECT status FROM consultation_threads WHERE id=?", thread)
                );
                assertEquals(complete ? 0 : 1, fixture.remaining(credit));
                assertEquals(complete ? 1 : 0, fixture.ratingPrompts(candidate, thread));
                // Completion is idempotent; replaying cancellation is rejected. Neither may refund/notify twice.
                assertEquals(complete ? 200 : 409, fixture.transition(actor, role, booking, complete));
                assertEquals(complete ? 0 : 1, fixture.remaining(credit));
                assertEquals(complete ? 1 : 0, fixture.ratingPrompts(candidate, thread));
            } finally {
                releaseShare.countDown();
                workers.forEach(worker -> worker.cancel(true));
                executor.shutdownNow();
                assertTrue(
                    executor.awaitTermination(20, TimeUnit.SECONDS),
                    "Evaluation workers must stop before cleanup"
                );
            }
        }
    }

    private List<Integer> race(
        Callable<Integer> first,
        Callable<Integer> second)
        throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (Callable<Integer> action : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent booking start timed out");
                    }
                    return action.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Both booking workers must reach the start barrier");
            start.countDown();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertTrue(
                executor.awaitTermination(20, TimeUnit.SECONDS),
                "Booking workers must terminate before cleanup"
            );
        }
    }

    private static class Fixture implements AutoCloseable {

        private final SqlStore sql;

        private final TransactionTemplate transaction;

        private final List<UUID> people = new ArrayList<>();

        private final List<UUID> candidates = new ArrayList<>();

        private Fixture() {
            String url = System.getProperty("consultation.testDatabaseUrl");
            if (!url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[a-z_]+_test")) {
                throw new IllegalArgumentException("A disposable localhost _test database is required");
            }
            var source = new DriverManagerDataSource(url, "edunexus_test", "");
            sql = new SqlStore(new JdbcTemplate(source));
            transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
            transaction.setTimeout(15);
        }

        private UUID person(
            String role) {
            UUID id = sql.insert(
                "users",
                fields(
                    "email",
                    UUID.randomUUID() + "@example.test",
                    "auth_provider",
                    "GOOGLE",
                    "google_sub",
                    UUID.randomUUID().toString(),
                    "full_name",
                    "Concurrent booking test",
                    "role",
                    role,
                    "status",
                    "ACTIVE"
                )
            );
            people.add(id);
            if ("CANDIDATE".equals(role)) {
                candidates.add(id);
            }
            return id;
        }

        private UUID consultant() {
            UUID id = person("CAREER_CONSULTANT");
            sql.update(
                "INSERT INTO consultant_profiles(user_id,target_roles,bio) VALUES (?,?,'Concurrency fixture')",
                id,
                sql.json(List.of("Backend"))
            );
            return id;
        }

        private UUID pack(
            UUID candidate) {
            UUID product =
                (UUID) sql.scalar("SELECT id FROM consultation_services WHERE service_code='CAREER_CALL_30'");
            return sql.insert(
                "consultation_orders",
                fields(
                    "candidate_user_id",
                    candidate,
                    "service_id",
                    product,
                    "service_code",
                    "CAREER_CALL_30",
                    "service_name",
                    "Concurrency fixture pack",
                    "delivery_mode",
                    "LIVE",
                    "duration_minutes",
                    30,
                    "amount",
                    99000,
                    "currency",
                    "VND",
                    "subject",
                    "Session pack",
                    "description",
                    "",
                    "sessions_total",
                    1,
                    "sessions_remaining",
                    1,
                    "is_session_pack",
                    true,
                    "status",
                    "PAID"
                )
            );
        }

        private int book(
            UUID candidate,
            UUID consultant,
            UUID credit,
            OffsetDateTime start) {
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                    candidate.toString(),
                    "",
                    List.of(new SimpleGrantedAuthority("ROLE_CANDIDATE"))
                )
            );
            try {
                transaction.executeWithoutResult(status -> {
                    sql.update("SET LOCAL statement_timeout='15s'");
                    new ConsultationBookingService(new ConsultationBookingRepository(sql), new CurrentUser()).create(
                        new ConsultationBookingRequest(
                            consultant,
                            credit,
                            "BE",
                            "Concurrent booking",
                            "Prepare for Backend interview",
                            start,
                            30
                        )
                    );
                });
                return 200;
            } catch (ApiException ex) {
                return ex.status();
            } finally {
                SecurityContextHolder.clearContext();
            }
        }

        private int remaining(
            UUID credit) {
            return ((Number) sql.scalar("SELECT sessions_remaining FROM consultation_orders WHERE id=?", credit))
                .intValue();
        }

        private int ratingPrompts(
            UUID candidate,
            UUID thread) {
            return ((Number) sql.scalar(
                "SELECT count(*) FROM notifications WHERE user_id=? AND reference_id=? "
                    + "AND type='CONSULTATION_RATING_REQUEST'",
                candidate,
                thread
            )).intValue();
        }

        private int transition(
            UUID actor,
            String role,
            UUID booking,
            boolean complete) {
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                    actor.toString(),
                    "",
                    List.of(new SimpleGrantedAuthority("ROLE_" + role))
                )
            );
            try {
                transaction.executeWithoutResult(status -> {
                    sql.update("SET LOCAL statement_timeout='15s'");
                    var service =
                        new ConsultationBookingService(new ConsultationBookingRepository(sql), new CurrentUser());
                    if (complete) {
                        service.complete(booking);
                    } else {
                        service.cancel(booking);
                    }
                });
                return 200;
            } catch (ApiException ex) {
                return ex.status();
            } finally {
                SecurityContextHolder.clearContext();
            }
        }

        @Override
        public void close() {
            transaction.executeWithoutResult(status -> {
                for (UUID candidate : candidates) {
                    List<Map<String, Object>> threads =
                        sql.list("SELECT id FROM consultation_threads WHERE candidate_user_id=?", candidate);
                    sql.update("DELETE FROM consultation_ratings WHERE candidate_user_id=?", candidate);
                    sql.update("DELETE FROM consultation_bookings WHERE candidate_user_id=?", candidate);
                    for (Map<String, Object> thread : threads) {
                        sql.update("DELETE FROM consultation_messages WHERE thread_id=?", thread.get("id"));
                        sql.update("DELETE FROM notifications WHERE reference_id=?", thread.get("id"));
                        sql.update("DELETE FROM consultation_threads WHERE id=?", thread.get("id"));
                    }
                    sql.update("DELETE FROM consultation_orders WHERE candidate_user_id=?", candidate);
                }
                for (UUID person : people) {
                    sql.update("DELETE FROM notifications WHERE user_id=?", person);
                    sql.update("DELETE FROM consultant_profiles WHERE user_id=?", person);
                    sql.update("DELETE FROM users WHERE id=?", person);
                }
            });
        }
    }
}
