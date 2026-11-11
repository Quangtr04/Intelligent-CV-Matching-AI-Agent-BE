package com.example.intelligent_cv_matching_ai_agent.admin;

import com.example.intelligent_cv_matching_ai_agent.repository.admin.management.ManagerOperationsRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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

/**
 * Native PostgreSQL coverage for operational report projections, with rolled-back synthetic fixtures.
 */
@EnabledIfSystemProperty(named = "roles.testDatabaseUrl", matches = ".+")
class ManagerOperationsDatabaseTests {

    @Test
    void subscriptionPaymentInvoiceProjectionsPreserveMoneyAndExcludeGatewayPayloads() {
        scenario(sql -> {
            var db = new ManagerOperationsRepository(sql);
            UUID candidate = person(sql, "CANDIDATE");
            UUID tier = sql.insert(
                "subscription_tiers",
                fields("tier_code", "TEST_" + randomCode(), "display_name", "Synthetic plan", "price_monthly", 12000)
            );
            UUID subscription = sql.insert("user_subscriptions", fields("user_id", candidate, "tier_id", tier));
            UUID payment = sql.insert(
                "payment_orders",
                fields(
                    "user_id",
                    candidate,
                    "tier_id",
                    tier,
                    "subscription_id",
                    subscription,
                    "duration_months",
                    1,
                    "amount",
                    12000,
                    "payment_provider",
                    "SEPAY",
                    "provider_order_id",
                    "PRIVATE_" + randomCode(),
                    "status",
                    "COMPLETED",
                    "completed_at",
                    OffsetDateTime.now(ZoneOffset.UTC)
                )
            );
            UUID invoice = sql.insert(
                "invoices",
                fields(
                    "user_id",
                    candidate,
                    "payment_order_id",
                    payment,
                    "invoice_number",
                    "TEST_" + randomCode(),
                    "subtotal",
                    12000,
                    "total_amount",
                    12000,
                    "status",
                    "PAID"
                )
            );
            assertEquals(candidate, db.subscription(subscription).get("user_id"));
            assertEquals(
                subscription,
                db.subscriptions("ACTIVE", "%" + candidate + "%", 20, 0).stream()
                    .filter(row -> subscription.equals(row.get("id"))).map(row -> row.get("id")).findFirst()
                    .orElse(null)
            );
            assertEquals(new BigDecimal("12000.00"), db.payment(payment).get("amount"));
            assertFalse(db.payment(payment).containsKey("provider_order_id"));
            assertEquals(invoice, db.paymentInvoice(payment).orElseThrow().get("id"));
            assertEquals(payment, db.invoice(invoice).get("payment_order_id"));
            assertFalse(db.payments("COMPLETED", "%" + candidate + "%", 20, 0).isEmpty());
            assertFalse(db.invoices("PAID", "%" + candidate + "%", 20, 0).isEmpty());
            assertFalse(db.dailyRevenue(OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1)).isEmpty());
            assertTrue(db.quotas(tier).isEmpty());
        });
    }

    @Test
    void aiUsageAggregatesCostAndSuccessWithoutExposingProviderErrors() {
        scenario(sql -> {
            var db = new ManagerOperationsRepository(sql);
            UUID candidate = person(sql, "CANDIDATE");
            String feature = "TEST_" + randomCode();
            for (boolean success : List.of(true, false)) {
                sql.insert(
                    "ai_usage_logs",
                    fields(
                        "user_id",
                        candidate,
                        "feature_code",
                        feature,
                        "input_tokens",
                        20,
                        "total_tokens",
                        20,
                        "estimated_cost",
                        new BigDecimal("0.001"),
                        "latency_ms",
                        10,
                        "success",
                        success,
                        "error_message",
                        "private-provider-error"
                    )
                );
            }
            OffsetDateTime from = OffsetDateTime.now().minusDays(1);
            OffsetDateTime until = OffsetDateTime.now().plusDays(1);
            Map<String, Object> row = db.usage(from, until, feature, 20, 0).getFirst();
            assertEquals(2L, row.get("requests"));
            assertEquals(40L, ((Number) row.get("total_tokens")).longValue());
            assertEquals(1L, row.get("successful_requests"));
            assertEquals(1L, row.get("failed_requests"));
            assertEquals(0, new BigDecimal("0.002").compareTo((BigDecimal) row.get("estimated_cost")));
            assertFalse(row.containsKey("error_message"));
            assertFalse(db.dailyUsage(from, until).isEmpty());
        });
    }

    @Test
    void consultantQualityBookingAndFeedbackReportsUseRealRatingData() {
        scenario(sql -> {
            var db = new ManagerOperationsRepository(sql);
            UUID candidate = person(sql, "CANDIDATE");
            UUID consultant = person(sql, "CAREER_CONSULTANT");
            UUID service =
                (UUID) sql.scalar("SELECT id FROM consultation_services WHERE service_code='CAREER_PACK_5_30'");
            UUID order = sql.insert(
                "consultation_orders",
                fields(
                    "candidate_user_id",
                    candidate,
                    "service_id",
                    service,
                    "service_code",
                    "CAREER_PACK_5_30",
                    "service_name",
                    "Synthetic pack",
                    "delivery_mode",
                    "LIVE",
                    "duration_minutes",
                    30,
                    "amount",
                    1000,
                    "subject",
                    "Synthetic credit",
                    "description",
                    "Test fixture",
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
            OffsetDateTime start = OffsetDateTime.now().minusHours(2);
            UUID booking = sql.insert(
                "consultation_bookings",
                fields(
                    "candidate_user_id",
                    candidate,
                    "consultant_user_id",
                    consultant,
                    "credit_order_id",
                    order,
                    "target_role",
                    "Backend Developer",
                    "subject",
                    "Synthetic completed session",
                    "description",
                    "Confidential CV discussion",
                    "starts_at",
                    start,
                    "ends_at",
                    start.plusMinutes(30),
                    "duration_minutes",
                    30,
                    "status",
                    "COMPLETED"
                )
            );
            sql.insert(
                "consultation_ratings",
                fields(
                    "candidate_user_id",
                    candidate,
                    "consultant_user_id",
                    consultant,
                    "booking_id",
                    booking,
                    "rating",
                    4,
                    "comment",
                    "Helpful guidance"
                )
            );
            Map<String, Object> consultantRow = db.consultants("%" + consultant + "%", 20, 0).getFirst();
            assertEquals(1L, consultantRow.get("completed_bookings"));
            assertEquals(1L, consultantRow.get("rating_count"));
            assertEquals(new BigDecimal("4.00"), consultantRow.get("average_rating"));
            Map<String, Object> bookingRow = db.consultantBookings(consultant, "COMPLETED", 20, 0).getFirst();
            assertEquals(booking, bookingRow.get("id"));
            assertEquals(4, ((Number) bookingRow.get("rating")).intValue());
            assertFalse(bookingRow.containsKey("description"));
            assertFalse(bookingRow.containsKey("meeting_url"));
            assertEquals(
                new BigDecimal("4.00"),
                db.consultationSummary(start.minusMinutes(1), start.plusHours(1)).get("average_rating")
            );
            assertTrue(
                db.feedback(100, 0).stream().anyMatch(row -> "Helpful guidance".equals(row.get("feedback_text")))
            );
            assertTrue(((Number) db.serviceMetrics().get("completed_bookings")).longValue() >= 1);
            db.crawlReports(null, null, 20, 0);
        });
    }

    private void scenario(
        Consumer<SqlStore> action) {
        String url = System.getProperty("roles.testDatabaseUrl");
        if (url == null || !url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[a-z_]+_test")) {
            throw new IllegalArgumentException("A disposable localhost _test database is required");
        }
        var source = new DriverManagerDataSource(url, "edunexus_test", "");
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            status.setRollbackOnly();
            action.accept(new SqlStore(new JdbcTemplate(source)));
        });
    }

    private UUID person(
        SqlStore sql,
        String role) {
        UUID id = UUID.randomUUID();
        return sql.insert(
            "users",
            fields(
                "id",
                id,
                "email",
                id + "@example.invalid",
                "full_name",
                "Synthetic " + id,
                "role",
                role,
                "status",
                "ACTIVE",
                "password_hash",
                "private-placeholder"
            )
        );
    }

    private String randomCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
