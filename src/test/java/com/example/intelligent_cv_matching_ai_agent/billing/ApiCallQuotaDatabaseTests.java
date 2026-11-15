package com.example.intelligent_cv_matching_ai_agent.billing;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.management.ManagerQuotaRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.billing.EntitlementRepository;
import com.example.intelligent_cv_matching_ai_agent.service.shared.billing.EntitlementService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "roles.testDatabaseUrl", matches = ".+")
class ApiCallQuotaDatabaseTests {

    private DriverManagerDataSource source() {
        String url = System.getProperty("roles.testDatabaseUrl");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/[a-z_]+_test")) {
            throw new IllegalArgumentException("Only isolated local test databases are permitted");
        }
        return new DriverManagerDataSource(url, "edunexus_test", "");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "MANAGER", "CAREER_CONSULTANT"})
    void staffIgnoreZeroTokenAndCallQuotasButRoleChangesRestoreEnforcement(
        String role) {
        var source = source();
        SqlStore sql = new SqlStore(new JdbcTemplate(source));
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            status.setRollbackOnly();
            UUID user = person(sql, role);
            UUID tier = sql.insert(
                "subscription_tiers",
                fields(
                    "tier_code",
                    "TEST_" + UUID.randomUUID().toString().substring(0, 20),
                    "display_name",
                    "Zero quota fixture",
                    "price_monthly",
                    0
                )
            );
            sql.insert("user_subscriptions", fields("user_id", user, "tier_id", tier));
            for (String feature : List.of("AI_TOTAL_TOKENS", "CV_ANALYZE", "API_CALLS_TOTAL", "API_CALLS_CV_ANALYZE")) {
                sql.insert(
                    "subscription_quotas",
                    fields("tier_id", tier, "feature_code", feature, "quota_value", 0, "quota_period", "MONTHLY")
                );
            }
            var repository = new EntitlementRepository(sql);
            var service = new EntitlementService(repository);
            UUID reservation = service.reserve(user, "CV_ANALYZE", 20000);
            assertEquals(20000, repository.tokensUsed(user, null, "month"));
            assertEquals(1, repository.requestsUsed(user, "CV_ANALYZE", "month"));
            service.complete(reservation, 15000);
            assertEquals(15000, repository.tokensUsed(user, null, "month"));
            UUID cancelled = service.reserve(user, "CV_ANALYZE", 20000);
            service.cancel(cancelled);
            assertEquals(15000, repository.tokensUsed(user, null, "month"));
            assertEquals(1, repository.requestsUsed(user, null, "month"));

            sql.update("UPDATE users SET role='CANDIDATE' WHERE id=?", user);
            assertEquals(
                403,
                assertThrows(ApiException.class, () -> service.reserve(user, "CV_ANALYZE", 100)).status()
            );
            sql.update("UPDATE users SET role=?,status='SUSPENDED' WHERE id=?", role, user);
            assertThrows(ApiException.class, () -> service.reserve(user, "CV_ANALYZE", 100));
        });
    }

    @Test
    void apiCountsExcludeCancelledAndRespectFeatureAndPeriod() {
        var source = source();
        SqlStore sql = new SqlStore(new JdbcTemplate(source));
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            status.setRollbackOnly();
            UUID user = person(sql, "CANDIDATE");
            sql.update(
                "INSERT INTO ai_token_reservations(user_id,feature_code,reserved_tokens,consumed_tokens,status) "
                    + "VALUES (?,'CV_BUILD',100,0,'RESERVED'),(?,'CV_BUILD',100,20,'COMPLETED'),"
                    + "(?,'CV_BUILD',100,0,'CANCELLED'),(?,'JOB_ATS',100,20,'COMPLETED')",
                user,
                user,
                user,
                user
            );
            sql.update(
                "INSERT INTO ai_token_reservations(user_id,feature_code,reserved_tokens,consumed_tokens,"
                    + "status,created_at) "
                    + "VALUES (?,'CV_BUILD',100,20,'COMPLETED',date_trunc('month',now())-interval '1 day')",
                user
            );
            var repository = new EntitlementRepository(sql);
            assertEquals(3, repository.requestsUsed(user, null, "month"));
            assertEquals(2, repository.requestsUsed(user, "CV_BUILD", "month"));
            assertEquals(3, repository.requestsUsed(user, "CV_BUILD", "all"));
            assertEquals(120, repository.tokensUsed(user, "CV_BUILD", "month"));
            assertThrows(IllegalArgumentException.class, () -> repository.requestsUsed(user, null, "untrusted"));
        });
    }

    @Test
    void delegatedLimitsPersistOnlyForManagerTarget() {
        var source = source();
        SqlStore sql = new SqlStore(new JdbcTemplate(source));
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            status.setRollbackOnly();
            UUID manager = person(sql, "MANAGER");
            UUID admin = person(sql, "ADMIN");
            var repository = new ManagerQuotaRepository(sql);
            repository.requireManager(manager);
            assertTrue(repository.limits(manager).isEmpty());
            repository.save(manager, 1500, 5, admin);
            assertEquals(1500L, repository.limits(manager).orElseThrow().get("max_token_quota"));
            repository.save(manager, 2000, 8, admin);
            assertEquals(8L, repository.limits(manager).orElseThrow().get("max_api_quota"));
            assertThrows(ApiException.class, () -> repository.requireManager(admin));
        });
    }

    @Test
    void concurrentRequestsCannotOverspendLastCallAndCancellationReleasesIt() throws Exception {
        var source = source();
        SqlStore sql = new SqlStore(new JdbcTemplate(source));
        UUID user = person(sql, "CANDIDATE");
        UUID tier = sql.insert(
            "subscription_tiers",
            fields(
                "tier_code",
                "Q_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                "display_name",
                "Synthetic call budget",
                "price_monthly",
                0
            )
        );
        try {
            sql.insert("user_subscriptions", fields("user_id", user, "tier_id", tier));
            sql.insert(
                "subscription_quotas",
                fields(
                    "tier_id",
                    tier,
                    "feature_code",
                    "AI_TOTAL_TOKENS",
                    "quota_value",
                    10000,
                    "quota_period",
                    "MONTHLY"
                )
            );
            sql.insert(
                "subscription_quotas",
                fields("tier_id", tier, "feature_code", "API_CALLS_TOTAL", "quota_value", 1, "quota_period", "MONTHLY")
            );
            var repository = new EntitlementRepository(sql);
            var service = new EntitlementService(repository);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            Callable<Integer> request = () -> {
                try {
                    tx.execute(status -> service.reserve(user, "CV_ANALYZE", 100));
                    return 200;
                } catch (ApiException ex) {
                    return ex.status();
                }
            };
            try (var executor = Executors.newFixedThreadPool(2)) {
                var results = executor.invokeAll(List.of(request, request), 10, TimeUnit.SECONDS);
                assertEquals(List.of(200, 429), results.stream().map(result -> {
                    try {
                        return result.get();
                    } catch (Exception ex) {
                        throw new AssertionError(ex);
                    }
                }).sorted().toList());
            }
            assertEquals(1, repository.requestsUsed(user, null, "month"));
            sql.update("UPDATE ai_token_reservations SET status='CANCELLED' WHERE user_id=?", user);
            assertDoesNotThrow(() -> tx.execute(status -> service.reserve(user, "CV_ANALYZE", 100)));
            assertEquals(1, repository.requestsUsed(user, null, "month"));
        } finally {
            sql.update("DELETE FROM ai_token_reservations WHERE user_id=?", user);
            sql.update("DELETE FROM user_subscriptions WHERE user_id=?", user);
            sql.update("DELETE FROM subscription_quotas WHERE tier_id=?", tier);
            sql.update("DELETE FROM subscription_tiers WHERE id=?", tier);
            sql.update("DELETE FROM users WHERE id=?", user);
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
                "Synthetic quota fixture",
                "role",
                role,
                "status",
                "ACTIVE"
            )
        );
    }
}
