package com.example.intelligent_cv_matching_ai_agent.identity;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.AuthRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.ResetPasswordTokenRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.TokenRepository;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.AuthService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses real reset-token migration and persistence in an isolated, disposable test schema. */
class AuthPasswordResetIntegrationTest {

    private static final String TEST_JWT_SECRET = TokenService.random("");

    private static final String EMAIL = "candidate@example.invalid";

    private static final String ORIGINAL_PASSWORD = "original-fixture-password";

    private static final String NEW_PASSWORD = "new-fixture-password";

    private static PostgreSQLContainer container;

    private static DriverManagerDataSource database;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    private JdbcTemplate jdbc;

    private AuthService auth;

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
    void createIsolatedSchemaAndApplyTheRealResetMigration() throws SQLException {
        schema = "password_reset_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(database).execute("CREATE SCHEMA " + schema);

        DriverManagerDataSource dataSource =
            new DriverManagerDataSource(database.getUrl(), database.getUsername(), database.getPassword());
        Properties properties = new Properties();
        properties.setProperty("currentSchema", schema);
        dataSource.setConnectionProperties(properties);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(5);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setTimeout(10);

        jdbc.execute(
            "CREATE TABLE users(id uuid PRIMARY KEY,email varchar(255) NOT NULL UNIQUE,"
                + "password_hash varchar(255) NOT NULL,role varchar(32) NOT NULL,full_name varchar(255) NOT NULL,"
                + "status varchar(32) NOT NULL,auth_provider varchar(32) NOT NULL,deleted_at timestamptz,"
                + "created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now())"
        );
        jdbc.execute(
            "CREATE TABLE refresh_tokens(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),user_id uuid NOT NULL "
                + "REFERENCES users(id),token_hash varchar(255) NOT NULL UNIQUE,expires_at timestamptz NOT NULL,"
                + "revoked_at timestamptz,created_at timestamptz NOT NULL DEFAULT now())"
        );
        try (var connection = dataSource.getConnection()) {
            ScriptUtils
                .executeSqlScript(connection, new ClassPathResource("db/migration/V21__password_reset_tokens.sql"));
        }

        SqlStore store = new SqlStore(jdbc);
        tokens = new TokenService(new TokenRepository(store), TEST_JWT_SECRET, 900, 2592000);
        auth = new AuthService(
            new AuthRepository(store),
            new ResetPasswordTokenRepository(store),
            encoder,
            tokens,
            new DefaultListableBeanFactory().getBeanProvider(JavaMailSender.class),
            true,
            "noreply@example.invalid",
            "https://frontend.example.invalid/reset-password",
            false
        );
        user = activeUser(EMAIL);
    }

    @AfterEach
    void dropOnlyTheIsolatedSchema() {
        if (schema != null) {
            new JdbcTemplate(database).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void resetChangesOnlyTheOwnerPasswordAndRevokesBothSessionTokensExactlyOnce() {
        UUID otherUser = activeUser("other@example.invalid");
        String otherPasswordHash = passwordHash(otherUser);
        Map<String, Object> session = transaction.execute(status -> tokens.issue(user));
        assertNotNull(session);
        String access = (String) session.get("accessToken");
        String refresh = (String) session.get("refreshToken");
        assertEquals(user, tokens.authenticate(access).get("id"));

        String token = requestReset(" CANDIDATE@EXAMPLE.INVALID ");
        assertTrue(token.matches("p_[A-Za-z0-9_-]{43}"));
        String hash = TokenService.hash(token);
        assertEquals(
            user,
            jdbc.queryForObject("SELECT user_id FROM reset_password_tokens WHERE token_hash=?", UUID.class, hash)
        );
        assertNotEquals(token, hash);
        OffsetDateTime createdAt = jdbc.queryForObject(
            "SELECT created_at FROM reset_password_tokens WHERE token_hash=?",
            OffsetDateTime.class,
            hash
        );
        OffsetDateTime expiresAt = jdbc.queryForObject(
            "SELECT expires_at FROM reset_password_tokens WHERE token_hash=?",
            OffsetDateTime.class,
            hash
        );
        assertEquals(Duration.ofMinutes(10), Duration.between(createdAt, expiresAt));
        assertTrue(expiresAt.isAfter(OffsetDateTime.now()));

        reset(token, NEW_PASSWORD);

        assertTrue(encoder.matches(NEW_PASSWORD, passwordHash(user)));
        assertFalse(encoder.matches(ORIGINAL_PASSWORD, passwordHash(user)));
        assertEquals(otherPasswordHash, passwordHash(otherUser));
        assertNotNull(
            jdbc.queryForObject(
                "SELECT revoked_at FROM reset_password_tokens WHERE token_hash=?",
                OffsetDateTime.class,
                hash
            )
        );
        assertEquals(
            2,
            jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id=? AND revoked_at IS NOT NULL",
                Integer.class,
                user
            )
        );
        assertEquals(401, assertThrows(ApiException.class, () -> tokens.authenticate(access)).status());
        assertEquals(
            401,
            assertThrows(ApiException.class, () -> transaction.execute(status -> tokens.rotate(refresh))).status()
        );
        assertEquals(400, assertThrows(ApiException.class, () -> reset(token, "another-fixture-password")).status());
        assertTrue(encoder.matches(NEW_PASSWORD, passwordHash(user)));
    }

    @Test
    void expiredResetLinkCannotChangeThePasswordOrRevokeExistingSessions() {
        Map<String, Object> session = transaction.execute(status -> tokens.issue(user));
        assertNotNull(session);
        String token = requestReset(EMAIL);
        String originalHash = passwordHash(user);
        jdbc.update(
            "UPDATE reset_password_tokens SET expires_at=now()-interval '1 second' WHERE token_hash=?",
            TokenService.hash(token)
        );

        assertEquals(400, assertThrows(ApiException.class, () -> reset(token, NEW_PASSWORD)).status());

        assertEquals(originalHash, passwordHash(user));
        assertNull(
            jdbc.queryForObject(
                "SELECT revoked_at FROM reset_password_tokens WHERE token_hash=?",
                OffsetDateTime.class,
                TokenService.hash(token)
            )
        );
        assertEquals(user, tokens.authenticate((String) session.get("accessToken")).get("id"));
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id=? AND revoked_at IS NOT NULL",
                Integer.class,
                user
            )
        );
    }

    @Test
    void cooldownPreventsDuplicateLinksAndTheNextLinkRevokesThePreviousOne() {
        String first = requestReset(EMAIL);
        assertEquals(429, assertThrows(ApiException.class, () -> requestReset(EMAIL)).status());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM reset_password_tokens", Integer.class));
        jdbc.update(
            "UPDATE reset_password_tokens SET created_at=now()-interval '61 seconds' WHERE token_hash=?",
            TokenService.hash(first)
        );

        String replacement = requestReset(EMAIL);

        assertNotEquals(first, replacement);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM reset_password_tokens", Integer.class));
        assertNotNull(
            jdbc.queryForObject(
                "SELECT revoked_at FROM reset_password_tokens WHERE token_hash=?",
                OffsetDateTime.class,
                TokenService.hash(first)
            )
        );
        assertNull(
            jdbc.queryForObject(
                "SELECT revoked_at FROM reset_password_tokens WHERE token_hash=?",
                OffsetDateTime.class,
                TokenService.hash(replacement)
            )
        );
        assertEquals(400, assertThrows(ApiException.class, () -> reset(first, NEW_PASSWORD)).status());
        assertTrue(encoder.matches(ORIGINAL_PASSWORD, passwordHash(user)));
        reset(replacement, NEW_PASSWORD);
        assertTrue(encoder.matches(NEW_PASSWORD, passwordHash(user)));
    }

    private UUID activeUser(
        String email) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO users(id,email,password_hash,role,full_name,status,auth_provider) "
                + "VALUES(?,?,?,'CANDIDATE','Password Reset Test','ACTIVE','EMAIL')",
            id,
            email,
            encoder.encode(ORIGINAL_PASSWORD)
        );
        return id;
    }

    private String passwordHash(
        UUID id) {
        return jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, id);
    }

    private String requestReset(
        String email) {
        Map<String, Object> response = transaction.execute(status -> auth.forgotPassword(email));
        assertNotNull(response);
        assertEquals(600, response.get("expiresIn"));
        return assertInstanceOf(String.class, response.get("developmentToken"));
    }

    private void reset(
        String token,
        String password) {
        transaction.executeWithoutResult(status -> auth.resetPassword(token, password, password));
    }
}
