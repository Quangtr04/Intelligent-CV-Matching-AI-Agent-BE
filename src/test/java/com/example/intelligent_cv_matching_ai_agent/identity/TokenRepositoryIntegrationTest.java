package com.example.intelligent_cv_matching_ai_agent.identity;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.TokenRepository;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs against an isolated schema in an explicit test database or a disposable PostgreSQL container. */
class TokenRepositoryIntegrationTest {

    private static final String TEST_JWT_SECRET = TokenService.random("");

    private static PostgreSQLContainer container;

    private static DriverManagerDataSource database;

    private JdbcTemplate jdbc;

    private TokenRepository repository;

    private TokenService tokens;

    private TransactionTemplate transaction;

    private String schema;

    private UUID user;

    @BeforeAll
    static void connectToDisposablePostgres() {
        String databaseUrl = System.getenv("EDUNEXUS_TEST_DATABASE_URL");
        if (databaseUrl != null && !databaseUrl.isBlank()) {
            database = new DriverManagerDataSource(
                databaseUrl,
                System.getenv().getOrDefault("EDUNEXUS_TEST_DATABASE_USER", "edunexus_test"),
                System.getenv().getOrDefault("EDUNEXUS_TEST_DATABASE_PASSWORD", "")
            );
            return;
        }

        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "A disposable PostgreSQL database is required");
        container = new PostgreSQLContainer("postgres:17-alpine");
        container.start();
        database =
            new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    @AfterAll
    static void stopDisposablePostgres() {
        if (container != null) {
            container.stop();
        }
    }

    @BeforeEach
    void createIsolatedSchema() {
        schema = "token_security_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(database).execute("CREATE SCHEMA " + schema);

        DriverManagerDataSource dataSource =
            new DriverManagerDataSource(database.getUrl(), database.getUsername(), database.getPassword());
        Properties properties = new Properties();
        properties.setProperty("currentSchema", schema);
        dataSource.setConnectionProperties(properties);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(5);
        repository = new TokenRepository(new SqlStore(jdbc));
        tokens = new TokenService(repository, TEST_JWT_SECRET, 900, 2592000);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setTimeout(10);

        jdbc.execute(
            "CREATE TABLE users(id uuid PRIMARY KEY,role varchar(32) NOT NULL,full_name varchar(255) NOT NULL,"
                + "status varchar(32) NOT NULL CHECK(status IN ('PENDING','ACTIVE','SUSPENDED','DELETED')),"
                + "deleted_at timestamptz)"
        );
        jdbc.execute(
            "CREATE TABLE refresh_tokens(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),user_id uuid NOT NULL "
                + "REFERENCES users(id),token_hash varchar(255) NOT NULL UNIQUE,expires_at timestamptz NOT NULL,"
                + "revoked_at timestamptz,created_at timestamptz NOT NULL DEFAULT now())"
        );
        user = activeUser();
    }

    @AfterEach
    void dropIsolatedSchema() {
        if (schema != null) {
            new JdbcTemplate(database).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void accessAndRefreshLookupsRejectExpiredRevokedAndInactiveAccounts() {
        String activeHash = tokenHash(user, OffsetDateTime.now().plusHours(1), null);
        String expiredHash = tokenHash(user, OffsetDateTime.now().minusHours(1), null);
        String revokedHash = tokenHash(user, OffsetDateTime.now().plusHours(1), OffsetDateTime.now());

        assertAccepted(activeHash);
        assertRejected(expiredHash);
        assertRejected(revokedHash);
        assertTrue(repository.findRefreshTokensByTokenHash2(expiredHash).isEmpty());
        assertTrue(repository.findRefreshTokensByTokenHash2(revokedHash).isEmpty());

        for (String status : List.of("PENDING", "SUSPENDED", "DELETED")) {
            jdbc.update("UPDATE users SET status=? WHERE id=?", status, user);
            assertRejected(activeHash);
            transaction.executeWithoutResult(state -> assertTrue(repository.findUsersByIdForUpdate(user).isEmpty()));
        }

        jdbc.update("UPDATE users SET status='ACTIVE',deleted_at=now() WHERE id=?", user);
        assertRejected(activeHash);
        transaction.executeWithoutResult(state -> assertTrue(repository.findUsersByIdForUpdate(user).isEmpty()));
    }

    @Test
    void consumptionIsBoundToTheOwnerAndSucceedsOnlyOnceBeforeExpiry() {
        String activeHash = tokenHash(user, OffsetDateTime.now().plusHours(1), null);
        UUID tokenId = tokenId(activeHash);
        assertEquals(0, repository.updateRefreshTokensById(tokenId, activeUser()));
        assertNull(jdbc.queryForObject("SELECT revoked_at FROM refresh_tokens WHERE id=?", Object.class, tokenId));

        assertEquals(1, repository.updateRefreshTokensById(tokenId, user));
        assertEquals(0, repository.updateRefreshTokensById(tokenId, user));
        assertRejected(activeHash);

        String expiredHash = tokenHash(user, OffsetDateTime.now().minusHours(1), null);
        assertEquals(0, repository.updateRefreshTokensById(tokenId(expiredHash), user));
        assertNull(
            jdbc.queryForObject("SELECT revoked_at FROM refresh_tokens WHERE token_hash=?", Object.class, expiredHash)
        );
    }

    @Test
    void consumptionRejectsTokenThatExpiresAfterTheTransactionBegins() {
        String hash = tokenHash(user, OffsetDateTime.now().plusHours(1), null);
        UUID tokenId = tokenId(hash);

        transaction.executeWithoutResult(status -> {
            jdbc.update(
                "UPDATE refresh_tokens SET expires_at=clock_timestamp()+interval '200 milliseconds' WHERE id=?",
                tokenId
            );
            jdbc.execute("SELECT pg_sleep(0.3)");

            assertTrue(
                Boolean.TRUE.equals(
                    jdbc.queryForObject(
                        "SELECT expires_at>now() AND expires_at<=clock_timestamp() FROM refresh_tokens WHERE id=?",
                        Boolean.class,
                        tokenId
                    )
                )
            );
            assertEquals(0, repository.updateRefreshTokensById(tokenId, user));
            assertNull(jdbc.queryForObject("SELECT revoked_at FROM refresh_tokens WHERE id=?", Object.class, tokenId));
        });
    }

    @Test
    void concurrentConditionalConsumptionHasExactlyOneWinner() throws Exception {
        String hash = tokenHash(user, OffsetDateTime.now().plusHours(1), null);
        UUID tokenId = tokenId(hash);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return transaction.execute(status -> repository.updateRefreshTokensById(tokenId, user));
            });
            var second = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return transaction.execute(status -> repository.updateRefreshTokensById(tokenId, user));
            });
            start.countDown();

            int firstCount = first.get(10, TimeUnit.SECONDS);
            int secondCount = second.get(10, TimeUnit.SECONDS);
            assertEquals(1, firstCount + secondCount);
            assertNotEquals(firstCount, secondCount);
        }
        assertRejected(hash);
    }

    @Test
    void concurrentRotationIssuesOnlyOneReplacementPair() throws Exception {
        Map<String, Object> initial = transaction.execute(status -> tokens.issue(user));
        String refresh = (String) initial.get("refreshToken");
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> rotateAfterStart(start, refresh));
            var second = executor.submit(() -> rotateAfterStart(start, refresh));
            start.countDown();

            Object firstResult = first.get(10, TimeUnit.SECONDS);
            Object secondResult = second.get(10, TimeUnit.SECONDS);
            assertNotEquals(firstResult instanceof Map, secondResult instanceof Map);
            ApiException rejected =
                assertInstanceOf(ApiException.class, firstResult instanceof ApiException ? firstResult : secondResult);
            assertEquals(401, rejected.status());
            Map<?, ?> replacement =
                assertInstanceOf(Map.class, firstResult instanceof Map ? firstResult : secondResult);
            assertNotEquals(refresh, replacement.get("refreshToken"));
            assertEquals(user, tokens.authenticate((String) replacement.get("accessToken")).get("id"));
        }

        assertEquals(
            401,
            assertThrows(ApiException.class, () -> transaction.execute(status -> tokens.rotate(refresh))).status()
        );
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class));
        assertEquals(
            1,
            jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NOT NULL", Integer.class)
        );
    }

    @Test
    void revocationWhileRotationWaitsForAccountLockPreventsIssuingNewTokens() throws Exception {
        Map<String, Object> initial = transaction.execute(status -> tokens.issue(user));
        String refresh = (String) initial.get("refreshToken");
        CountDownLatch accountLocked = new CountDownLatch(1);
        CountDownLatch accountLockAttempted = new CountDownLatch(1);
        CountDownLatch revokeNow = new CountDownLatch(1);
        TokenRepository observedRepository = new TokenRepository(new SqlStore(jdbc)) {

            @Override
            public Optional<Map<String, Object>> findUsersByIdForUpdate(
                Object... args) {
                accountLockAttempted.countDown();
                return super.findUsersByIdForUpdate(args);
            }
        };
        TokenService rotatingTokens = new TokenService(observedRepository, TEST_JWT_SECRET, 900, 2592000);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var revocation = executor.submit(() -> transaction.executeWithoutResult(status -> {
                repository.getUsersByIdForUpdate(user);
                accountLocked.countDown();
                assertTrue(assertDoesNotThrow(() -> revokeNow.await(5, TimeUnit.SECONDS)));
                assertEquals(2, repository.updateRefreshTokensByUserId(user));
            }));
            try {
                assertTrue(accountLocked.await(5, TimeUnit.SECONDS));
                var rotation = executor.submit(() -> {
                    try {
                        return transaction.execute(status -> rotatingTokens.rotate(refresh));
                    } catch (ApiException exception) {
                        return exception;
                    }
                });
                assertTrue(accountLockAttempted.await(5, TimeUnit.SECONDS));
                revokeNow.countDown();
                revocation.get(10, TimeUnit.SECONDS);
                assertEquals(401, assertInstanceOf(ApiException.class, rotation.get(10, TimeUnit.SECONDS)).status());
            } finally {
                revokeNow.countDown();
            }
        }
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class));
        assertEquals(
            2,
            jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NOT NULL", Integer.class)
        );
    }

    private Object rotateAfterStart(
        CountDownLatch start,
        String refresh)
        throws InterruptedException {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try {
            return transaction.execute(status -> tokens.rotate(refresh));
        } catch (ApiException exception) {
            return exception;
        }
    }

    private void assertAccepted(
        String hash) {
        assertEquals(user, repository.findRefreshTokensByTokenHash(hash).orElseThrow().get("id"));
        transaction.executeWithoutResult(status -> {
            assertTrue(repository.findUsersByIdForUpdate(user).isPresent());
            assertEquals(user, repository.findRefreshTokensByTokenHashForUpdate(hash).orElseThrow().get("user_id"));
        });
    }

    private void assertRejected(
        String hash) {
        assertTrue(repository.findRefreshTokensByTokenHash(hash).isEmpty());
        transaction.executeWithoutResult(
            status -> assertTrue(repository.findRefreshTokensByTokenHashForUpdate(hash).isEmpty())
        );
    }

    private UUID activeUser() {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO users(id,role,full_name,status) VALUES(?,'CANDIDATE','Token Security Test','ACTIVE')",
            id
        );
        return id;
    }

    private String tokenHash(
        UUID owner,
        OffsetDateTime expiresAt,
        OffsetDateTime revokedAt) {
        String hash = TokenService.hash(UUID.randomUUID().toString());
        repository.insertRefreshTokens(Map.of("user_id", owner, "token_hash", hash, "expires_at", expiresAt));
        if (revokedAt != null) {
            jdbc.update("UPDATE refresh_tokens SET revoked_at=? WHERE token_hash=?", revokedAt, hash);
        }
        return hash;
    }

    private UUID tokenId(
        String hash) {
        return jdbc.queryForObject("SELECT id FROM refresh_tokens WHERE token_hash=?", UUID.class, hash);
    }
}
