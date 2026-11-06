package com.example.intelligent_cv_matching_ai_agent.billing;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.consultation.ConsultationOrderRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.billing.PaymentWebhookRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.billing.BillingRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.billing.EntitlementRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation.ConsultationCatalogRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.billing.BillingService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.billing.SePayPaymentService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.ConsultationCatalogService;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicitly opt in against a disposable local database; every scenario is rolled back. */
@EnabledIfSystemProperty(named = "consultation.testDatabaseUrl", matches = ".+")
class ConsultationBillingDatabaseTests {

    private void scenario(
        java.util.function.Consumer<SqlStore> action) {
        String url = System.getProperty("consultation.testDatabaseUrl");
        if (!url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[a-z_]+_test")) {
            throw new IllegalArgumentException("A disposable localhost _test database is required");
        }
        var source = new DriverManagerDataSource(url, "edunexus_test", "");
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            status.setRollbackOnly();
            action.accept(new SqlStore(new JdbcTemplate(source)));
        });
    }

    private UUID candidate(
        SqlStore sql) {
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
                "Billing integration test",
                "role",
                "CANDIDATE",
                "status",
                "ACTIVE"
            )
        );
    }

    private ConsultationCatalogService catalog(
        SqlStore sql,
        UUID candidate) {
        CurrentUser user = mock(CurrentUser.class);
        when(user.id()).thenReturn(candidate);
        SePayPaymentService gateway = mock(SePayPaymentService.class);
        when(gateway.consultationProvider()).thenReturn("SEPAY_SANDBOX");
        return new ConsultationCatalogService(
            new ConsultationCatalogRepository(sql),
            new BillingRepository(sql),
            gateway,
            user
        );
    }

    @Test
    void launchCatalogContainsAgreedPricesAndQuotas() {
        scenario(sql -> {
            assertEquals(6, ((Number) sql.scalar("SELECT count(*) FROM consultation_services")).intValue());
            assertEquals(
                4,
                ((Number) sql.scalar("SELECT count(*) FROM consultation_services WHERE is_active AND is_session_pack"))
                    .intValue()
            );
            assertEquals(
                new BigDecimal("49000.00"),
                sql.scalar("SELECT price FROM consultation_services WHERE service_code='CV_REVIEW_ASYNC'")
            );
            assertEquals(
                new BigDecimal("79000.00"),
                sql.scalar("SELECT price FROM consultation_services WHERE service_code='CAREER_ROADMAP_ASYNC'")
            );
            assertEquals(
                new BigDecimal("99000.00"),
                sql.scalar("SELECT price FROM consultation_services WHERE service_code='CAREER_CALL_30'")
            );
            assertEquals(
                new BigDecimal("179000.00"),
                sql.scalar("SELECT price FROM consultation_services WHERE service_code='CAREER_CALL_60'")
            );
            for (var entry : Map.of("FREE", 30000L, "BASIC", 250000L, "PRO", 800000L).entrySet()) {
                var row = sql.one(
                    "SELECT t.price_monthly,q.quota_value,q.quota_period FROM subscription_tiers t "
                        + "JOIN subscription_quotas q ON q.tier_id=t.id "
                        + "WHERE t.tier_code=? AND q.feature_code='AI_TOTAL_TOKENS'",
                    entry.getKey()
                );
                assertEquals(entry.getValue().longValue(), ((Number) row.get("quota_value")).longValue());
                assertEquals("FREE".equals(entry.getKey()) ? "MONTHLY" : "SUBSCRIPTION", row.get("quota_period"));
                assertEquals(
                    new BigDecimal(
                        "FREE".equals(entry.getKey())
                            ? "0.00"
                            : "BASIC".equals(entry.getKey()) ? "29000.00" : "69000.00"
                    ),
                    row.get("price_monthly")
                );
            }
        });
    }

    @Test
    void paidPackRetainsPriceAndReplayedIpnDoesNotDuplicateCreditsOrInvoice() {
        scenario(sql -> {
            UUID owner = candidate(sql);
            var catalog = catalog(sql, owner);
            UUID product =
                (UUID) sql.scalar("SELECT id FROM consultation_services WHERE service_code='CAREER_PACK_5_30'");
            var order = catalog.order(new ConsultationOrderRequest(product, "Review", "Synthetic CV"));
            UUID payment = (UUID) order.get("payment_order_id");
            assertEquals(
                0L,
                ((Number) sql.scalar("SELECT count(*) FROM consultation_threads WHERE candidate_user_id=?", owner))
                    .longValue()
            );
            sql.update("UPDATE consultation_services SET price=590000,sessions_count=7 WHERE id=?", product);
            var event = new PaymentWebhookRequest(
                payment,
                payment.toString(),
                "test-" + payment,
                "COMPLETED",
                new BigDecimal("495000"),
                "VND"
            );
            var billing = new BillingService(new BillingRepository(sql), "MANUAL_GATEWAY");
            billing.confirmSePay(event, "SEPAY_SANDBOX");
            assertEquals(true, billing.confirmSePay(event, "SEPAY_SANDBOX").get("duplicate"));
            var paid = catalog.orders().getFirst();
            assertEquals("PAID", paid.get("status"));
            assertEquals(new BigDecimal("495000.00"), paid.get("amount"));
            assertNull(paid.get("thread_id"));
            assertEquals(5, ((Number) paid.get("sessions_total")).intValue());
            assertEquals(5, ((Number) paid.get("sessions_remaining")).intValue());
            assertEquals(
                0L,
                ((Number) sql.scalar("SELECT count(*) FROM consultation_threads WHERE candidate_user_id=?", owner))
                    .longValue()
            );
            assertEquals(
                1L,
                ((Number) sql.scalar("SELECT count(*) FROM invoices WHERE payment_order_id=?", payment)).longValue()
            );
            assertEquals(
                0L,
                ((Number) sql.scalar("SELECT count(*) FROM user_subscriptions WHERE user_id=?", owner)).longValue()
            );
            assertTrue(catalog(sql, candidate(sql)).orders().isEmpty());
            assertThrows(ApiException.class, () -> catalog.cancel((UUID) order.get("id")));
        });
    }

    @Test
    void cancellationPreventsLateWebhookFromReleasingConsultation() {
        scenario(sql -> {
            UUID owner = candidate(sql);
            var catalog = catalog(sql, owner);
            UUID product = (UUID) sql.scalar("SELECT id FROM consultation_services WHERE is_active LIMIT 1");
            var order = catalog.order(new ConsultationOrderRequest(product, "Review", "Synthetic CV"));
            UUID payment = (UUID) order.get("payment_order_id");
            catalog.cancel((UUID) order.get("id"));
            BigDecimal amount = (BigDecimal) order.get("amount");
            assertThrows(
                ApiException.class,
                () -> new BillingService(new BillingRepository(sql), "TEST").confirmSePay(
                    new PaymentWebhookRequest(
                        payment,
                        payment.toString(),
                        "late-" + payment,
                        "COMPLETED",
                        amount,
                        "VND"
                    ),
                    "SEPAY_SANDBOX"
                )
            );
            assertEquals("CANCELLED", catalog.orders().getFirst().get("status"));
            assertEquals(
                0L,
                ((Number) sql.scalar("SELECT count(*) FROM consultation_threads WHERE candidate_user_id=?", owner))
                    .longValue()
            );
        });
    }

    @Test
    void quotaUsesCurrentThirtyDayCycleAndIncludesInFlightReservations() {
        scenario(sql -> {
            UUID owner = candidate(sql);
            UUID tier = (UUID) sql.scalar("SELECT id FROM subscription_tiers WHERE tier_code='BASIC'");
            sql.update(
                "INSERT INTO user_subscriptions(user_id,tier_id,started_at,expires_at) "
                    + "VALUES (?,?,now()-interval '35 days',now()+interval '25 days')",
                owner,
                tier
            );
            sql.update(
                "INSERT INTO ai_token_reservations(user_id,feature_code,reserved_tokens,consumed_tokens,status,created_at) "
                    + "VALUES (?,'CAREER_CHAT',500,500,'COMPLETED',now()-interval '6 days'),"
                    + "(?,'CAREER_CHAT',100,100,'COMPLETED',now()-interval '4 days'),"
                    + "(?,'CAREER_CHAT',200,0,'RESERVED',now())",
                owner,
                owner,
                owner
            );
            assertEquals(300L, new EntitlementRepository(sql).tokensUsed(owner, null, "subscription"));
        });
    }
}
