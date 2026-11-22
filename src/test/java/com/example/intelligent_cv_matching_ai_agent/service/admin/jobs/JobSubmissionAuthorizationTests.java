package com.example.intelligent_cv_matching_ai_agent.service.admin.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.CrawlRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.ImportJobRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.jobs.JobCrawlRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs.JobRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.JobService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobSubmissionAuthorizationTests {

    private final UUID actor = UUID.randomUUID();

    private final JobRepository db = mock(JobRepository.class);

    private final CurrentUser user = new CurrentUser();

    private final JobService service = new JobService(db, user, mock(AiService.class));

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "MANAGER", "STAFF"})
    void operationsRolesImportActiveJobsWithCreatorAttribution(
        String role) {
        identity(role);
        UUID job = UUID.randomUUID();
        when(db.insertMarketJobPosts(any())).thenReturn(job);
        when(db.getMarketJobPostsWithCreatorById(job)).thenReturn(new LinkedHashMap<>(Map.of("id", job)));

        assertEquals(job, service.importJob(input()).get("id"));

        verify(db).insertMarketJobPosts(
            argThat(
                fields -> "ACTIVE".equals(fields.get("status")) && actor.equals(fields.get("created_by_user_id"))
                    && "MANUAL".equals(fields.get("ingestion_method"))
            )
        );
        verify(db).updateMarketJobEmbeddingsByMarketJobId(input().sourceUrl(), job);
        verify(db).updateMarketJobPostsBySourceUrl(input().sourceUrl(), job);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANDIDATE", "CAREER_CONSULTANT", "MANAGEMENT"})
    void otherRolesCannotReadOrMutateSourcesThroughImport(
        String role) {
        identity(role);

        assertEquals(403, assertThrows(ApiException.class, () -> service.importJob(input())).status());
        verifyNoInteractions(db);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANDIDATE", "CAREER_CONSULTANT", "MANAGEMENT"})
    void otherRolesCannotCreateCrawlReportsOrAccessTheNetwork(
        String role) {
        identity(role);
        JobService jobs = mock(JobService.class);
        JobCrawlRepository crawls = mock(JobCrawlRepository.class);
        JobCrawlService crawler = new JobCrawlService(crawls, user, jobs, mock(PlatformTransactionManager.class)) {

            @Override
            protected HttpPage fetch(
                String source,
                String url) {
                fail("Unauthorized roles must never access an external source");
                return null;
            }
        };

        assertEquals(
            403,
            assertThrows(
                ApiException.class,
                () -> crawler.crawl(
                    new CrawlRequest(
                        "ITVIEC",
                        "Java",
                        "Backend",
                        "Ho Chi Minh",
                        List.of("https://itviec.com/it-jobs/java-fixture"),
                        1,
                        1
                    )
                )
            ).status()
        );
        verifyNoInteractions(crawls, jobs);
    }

    private void identity(
        String role) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(actor, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))
        );
    }

    private ImportJobRequest input() {
        return new ImportJobRequest(
            "ITVIEC",
            "https://itviec.com/it-jobs/java-fixture",
            "Java Engineer",
            "Synthetic Company",
            "Ho Chi Minh",
            null,
            "Backend Developer",
            "Develop Java applications and maintain production systems using Spring Boot.",
            List.of()
        );
    }
}
