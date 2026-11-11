package com.example.intelligent_cv_matching_ai_agent.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultationAdviceRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultationPrivateNotesRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.consultant.consultation.ConsultationAdviceRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.consultant.consultation.ConsultationEvaluationRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.consultant.consultation.ConsultationAdviceService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/** Real PostgreSQL isolation and optimistic writes; synthetic fixtures roll back after every scenario. */
@EnabledIfSystemProperty(named = "consultation.testDatabaseUrl", matches = ".+")
class ConsultationAdviceDatabaseTests {

    private record Fixture(
        SqlStore sql,
        ConsultationAdviceRepository repository,
        ConsultationAdviceService service,
        UUID candidate,
        UUID consultant,
        UUID thread
    ) {
    }

    @Test
    void assignedConsultantDraftIsHiddenFromCandidateAndOtherActors() {
        scenario(fixture -> {
            authenticate(fixture.consultant(), "CAREER_CONSULTANT");
            Map<String, Object> draft = fixture.service().saveAdvice(fixture.thread(), request(0L, "DRAFT"));
            assertEquals(1L, draft.get("version"));
            assertEquals("DRAFT", fixture.service().advice(fixture.thread()).get("status"));
            authenticate(fixture.candidate(), "CANDIDATE");
            Map<String, Object> hidden = fixture.service().advice(fixture.thread());
            assertEquals("EMPTY", hidden.get("status"));
            assertEquals(0L, hidden.get("version"));
            assertNull(hidden.get("advice"));
            UUID stranger = person(fixture.sql(), "CAREER_CONSULTANT");
            authenticate(stranger, "CAREER_CONSULTANT");
            assertEquals(
                404,
                assertThrows(ApiException.class, () -> fixture.service().advice(fixture.thread())).status()
            );
            UUID otherCandidate = person(fixture.sql(), "CANDIDATE");
            authenticate(otherCandidate, "CANDIDATE");
            assertEquals(
                404,
                assertThrows(ApiException.class, () -> fixture.service().advice(fixture.thread())).status()
            );
        });
    }

    @Test
    void publishedReportIsDurableAndSendsCandidateNoticeWithoutCreatingMessages() {
        scenario(fixture -> {
            authenticate(fixture.consultant(), "CAREER_CONSULTANT");
            Map<String, Object> saved = fixture.service().saveAdvice(fixture.thread(), request(0L, "PUBLISHED"));
            assertEquals("PUBLISHED", saved.get("status"));
            assertNotNull(saved.get("publishedAt"));
            authenticate(fixture.candidate(), "CANDIDATE");
            Map<String, Object> report = fixture.service().advice(fixture.thread());
            assertEquals(saved.get("advice"), report.get("advice"));
            assertEquals(1L, count(fixture, "notifications", "reference_id", fixture.thread()));
            assertEquals(0L, count(fixture, "consultation_messages", "thread_id", fixture.thread()));
            assertEquals(
                "CONSULTATION_ADVICE_PUBLISHED",
                fixture.sql().scalar("SELECT type FROM notifications WHERE reference_id=?", fixture.thread())
            );
            assertEquals(
                fixture.candidate(),
                fixture.sql().scalar("SELECT user_id FROM notifications WHERE reference_id=?", fixture.thread())
            );
        });
    }

    @Test
    void staleAdviceAndNotesCannotOverwriteNewerVersionsEvenAtRepositoryBoundary() {
        scenario(fixture -> {
            authenticate(fixture.consultant(), "CAREER_CONSULTANT");
            fixture.service().saveAdvice(fixture.thread(), request(0L, "DRAFT"));
            fixture.service().saveAdvice(fixture.thread(), request(1L, "PUBLISHED"));
            assertEquals(
                409,
                assertThrows(
                    ApiException.class,
                    () -> fixture.service().saveAdvice(fixture.thread(), request(1L, "PUBLISHED"))
                ).status()
            );
            assertEquals(
                409,
                assertThrows(
                    ApiException.class,
                    () -> fixture.repository().saveAdvice(
                        fixture.thread(),
                        fixture.candidate(),
                        fixture.consultant(),
                        null,
                        null,
                        "PUBLISHED",
                        content(),
                        1L
                    )
                ).status()
            );
            assertEquals(2L, fixture.service().advice(fixture.thread()).get("version"));
            fixture.service()
                .savePrivateNotes(fixture.thread(), new ConsultationPrivateNotesRequest(0L, "First", null));
            fixture.service()
                .savePrivateNotes(fixture.thread(), new ConsultationPrivateNotesRequest(1L, "Second", null));
            assertEquals(
                409,
                assertThrows(
                    ApiException.class,
                    () -> fixture.repository()
                        .savePrivateNotes(fixture.thread(), fixture.consultant(), "Stale", Map.of(), 1L)
                ).status()
            );
            assertEquals("Second", fixture.service().privateNotes(fixture.thread()).get("notes"));
        });
    }

    @Test
    void privateNotesAreInaccessibleToCandidateAndUnassignedConsultants() {
        scenario(fixture -> {
            authenticate(fixture.consultant(), "CAREER_CONSULTANT");
            fixture.service().savePrivateNotes(
                fixture.thread(),
                new ConsultationPrivateNotesRequest(
                    0L,
                    "Private consultant observation",
                    new ConsultationPrivateNotesRequest.Rubric(7, 8, 6, 7)
                )
            );
            fixture.service().saveAdvice(fixture.thread(), request(0L, "PUBLISHED"));
            authenticate(fixture.candidate(), "CANDIDATE");
            assertEquals(
                403,
                assertThrows(ApiException.class, () -> fixture.service().privateNotes(fixture.thread())).status()
            );
            assertFalse(
                fixture.service().advice(fixture.thread()).toString().contains("Private consultant observation")
            );
            authenticate(person(fixture.sql(), "CAREER_CONSULTANT"), "CAREER_CONSULTANT");
            assertEquals(
                404,
                assertThrows(ApiException.class, () -> fixture.service().privateNotes(fixture.thread())).status()
            );
            assertFalse(
                fixture.sql()
                    .scalar(
                        "SELECT new_value::text FROM audit_logs WHERE entity_id=? "
                            + "AND action='CONSULTATION_PRIVATE_NOTES_SAVE'",
                        fixture.thread()
                    ).toString().contains("Private")
            );
        });
    }

    @Test
    void consultantCannotAttachAnotherCandidatesResume() {
        scenario(fixture -> {
            UUID anotherCandidate = person(fixture.sql(), "CANDIDATE");
            UUID foreignResume = fixture.sql().insert(
                "resumes",
                fields("user_id", anotherCandidate, "title", "Foreign CV", "source", "MANUAL", "status", "READY")
            );
            authenticate(fixture.consultant(), "CAREER_CONSULTANT");
            assertEquals(
                404,
                assertThrows(
                    ApiException.class,
                    () -> fixture.service().saveAdvice(
                        fixture.thread(),
                        new ConsultationAdviceRequest(0L, "PUBLISHED", foreignResume, null, content())
                    )
                ).status()
            );
            assertEquals(0L, count(fixture, "consultation_advice_reports", "thread_id", fixture.thread()));
            UUID ownResume = fixture.sql().insert(
                "resumes",
                fields(
                    "user_id",
                    fixture.candidate(),
                    "title",
                    "Assigned Candidate CV",
                    "source",
                    "MANUAL",
                    "status",
                    "READY"
                )
            );
            assertEquals(
                ownResume,
                fixture.service()
                    .saveAdvice(
                        fixture.thread(),
                        new ConsultationAdviceRequest(0L, "PUBLISHED", ownResume, null, content())
                    ).get("resumeId")
            );
        });
    }

    @Test
    void candidatesCannotWriteReportsOrPrivateNotesAndManagersCannotReadThem() {
        scenario(fixture -> {
            authenticate(fixture.candidate(), "CANDIDATE");
            assertEquals(
                403,
                assertThrows(
                    ApiException.class,
                    () -> fixture.service().saveAdvice(fixture.thread(), request(0L, "PUBLISHED"))
                ).status()
            );
            assertEquals(
                403,
                assertThrows(
                    ApiException.class,
                    () -> fixture.service()
                        .savePrivateNotes(fixture.thread(), new ConsultationPrivateNotesRequest(0L, "Forbidden", null))
                ).status()
            );
            authenticate(person(fixture.sql(), "MANAGER"), "MANAGER");
            assertEquals(
                403,
                assertThrows(ApiException.class, () -> fixture.service().advice(fixture.thread())).status()
            );
        });
    }

    @Test
    void closedLegacyConsultationRetainsPublishedReportButRejectsWrites() {
        scenario(fixture -> {
            authenticate(fixture.consultant(), "CAREER_CONSULTANT");
            fixture.service().saveAdvice(fixture.thread(), request(0L, "PUBLISHED"));
            fixture.sql()
                .update("UPDATE consultation_threads SET status='CLOSED',closed_at=now() WHERE id=?", fixture.thread());
            assertEquals(
                409,
                assertThrows(
                    ApiException.class,
                    () -> fixture.service().saveAdvice(fixture.thread(), request(1L, "PUBLISHED"))
                ).status()
            );
            authenticate(fixture.candidate(), "CANDIDATE");
            assertEquals("PUBLISHED", fixture.service().advice(fixture.thread()).get("status"));
        });
    }

    private void scenario(
        Consumer<Fixture> action) {
        String url = System.getProperty("consultation.testDatabaseUrl");
        if (url == null || !url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[a-z_]+_test")) {
            throw new IllegalArgumentException("A disposable localhost _test database is required");
        }
        var source = new DriverManagerDataSource(url, "edunexus_test", "");
        try {
            new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
                status.setRollbackOnly();
                SqlStore sql = new SqlStore(new JdbcTemplate(source));
                UUID candidate = person(sql, "CANDIDATE");
                UUID consultant = person(sql, "CAREER_CONSULTANT");
                UUID thread = sql.insert(
                    "consultation_threads",
                    fields(
                        "candidate_user_id",
                        candidate,
                        "consultant_user_id",
                        consultant,
                        "subject",
                        "Synthetic advice test",
                        "request_type",
                        "CV_REVIEW"
                    )
                );
                var repository = new ConsultationAdviceRepository(sql);
                var service = new ConsultationAdviceService(
                    repository,
                    new ConsultationEvaluationRepository(sql),
                    new CurrentUser()
                );
                action.accept(new Fixture(sql, repository, service, candidate, consultant, thread));
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
                "Synthetic Advice " + role,
                "role",
                role,
                "status",
                "ACTIVE"
            )
        );
    }

    private void authenticate(
        UUID actor,
        String role) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                actor.toString(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
            )
        );
    }

    private ConsultationAdviceRequest request(
        long version,
        String status) {
        return new ConsultationAdviceRequest(version, status, null, null, content());
    }

    private Map<String, Object> content() {
        return Map.of(
            "cvAdvice",
            Map.of("summary", "Quantify project outcomes"),
            "careerAdvice",
            Map.of("recommendedPositioning", "Apply for junior backend roles"),
            "applicationAdvice",
            Map.of("summary", "Tailor the cover letter to the position")
        );
    }

    private long count(
        Fixture fixture,
        String table,
        String key,
        UUID id) {
        if (!List.of("notifications", "consultation_messages", "consultation_advice_reports").contains(table)
            || !List.of("reference_id", "thread_id").contains(key)) {
            throw new IllegalArgumentException("Unsupported fixture table");
        }
        return ((Number) fixture.sql().scalar("SELECT count(*) FROM " + table + " WHERE " + key + "=?", id))
            .longValue();
    }
}
