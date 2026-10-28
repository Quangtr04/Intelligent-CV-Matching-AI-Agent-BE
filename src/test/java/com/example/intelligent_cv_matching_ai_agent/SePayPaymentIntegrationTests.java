package com.example.intelligent_cv_matching_ai_agent;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** SePay checkout/IPN contract against a disposable database; never contacts a payment provider. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
        "APP_ENV_FILE=src/test/resources/test.env",
        "app.security.jwt-secret=${random.uuid}${random.uuid}",
        "spring.docker.compose.enabled=false",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "app.ai.enabled=false",
        "app.jobs.collection.enabled=false",
        "app.jobs.expiration.enabled=false",
        "app.jobs.embedding.enabled=false",
        "app.billing.maintenance-enabled=false",
        "app.mail.enabled=false",
        "app.ocr.enabled=false",
        "app.payment.provider=MANUAL_GATEWAY",
        "app.payment.webhook-secret=legacy-integration-secret-at-least-32-characters",
        "app.payment.sepay.enabled=true",
        "app.payment.sepay.environment=sandbox",
        "app.payment.sepay.merchant-id=integration-merchant",
        "app.payment.sepay.merchant-secret=checkout-integration-secret-never-a-real-merchant-key",
        "app.payment.sepay.ipn-secret=ipn-integration-secret-at-least-32-characters",
        "app.payment.sepay.return-url=https://frontend.example.test/candidate/subscription",
        "app.payment.sepay.payment-method=BANK_TRANSFER"}
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class SePayPaymentIntegrationTests {

    private static final String CHECKOUT = "/api/v1/payments/sepay/checkout";

    private static final String IPN = "/api/v1/payments/sepay/ipn";

    private static final String IPN_SECRET = "ipn-integration-secret-at-least-32-characters";

    private static final String MERCHANT_SECRET = "checkout-integration-secret-never-a-real-merchant-key";

    private static final String LEGACY_SECRET = "legacy-integration-secret-at-least-32-characters";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SqlStore db;

    @Autowired
    private TokenService tokens;

    @Autowired
    private JsonMapper mapper;

    private UUID owner;

    private UUID other;

    private UUID tier;

    private String authorization;

    private String otherAuthorization;

    private boolean committedFixtures;

    @BeforeEach
    void fixtures() {
        committedFixtures = !TransactionSynchronizationManager.isActualTransactionActive();
        owner = createUser();
        other = createUser();
        authorization = "Bearer " + tokens.issue(owner).get("accessToken");
        otherAuthorization = "Bearer " + tokens.issue(other).get("accessToken");
        tier = (UUID) db.scalar("SELECT id FROM subscription_tiers WHERE tier_code='PRO'");
    }

    @AfterEach
    void removeCommittedFixtures() {
        if (!committedFixtures) {
            return;
        }
        for (UUID account : List.of(owner, other)) {
            db.update(
                "DELETE FROM payment_webhook_events WHERE payment_order_id IN "
                    + "(SELECT id FROM payment_orders WHERE user_id=?)",
                account
            );
            db.update("DELETE FROM invoices WHERE user_id=?", account);
            db.update("DELETE FROM notifications WHERE user_id=?", account);
            db.update("DELETE FROM payment_orders WHERE user_id=?", account);
            db.update("DELETE FROM user_subscriptions WHERE user_id=?", account);
            db.update("DELETE FROM refresh_tokens WHERE user_id=?", account);
            db.update("DELETE FROM users WHERE id=?", account);
        }
    }

    @Test
    void checkoutUsesTheServerPriceAndDoesNotActivateASubscriptionBeforeIpn() throws Exception {
        Map<String, Object> checkout = checkout();
        Map<String, Object> order = order(checkout);
        Map<String, String> fields = fields(checkout);
        BigDecimal expected =
            new BigDecimal(db.scalar("SELECT price_monthly FROM subscription_tiers WHERE id=?", tier).toString())
                .multiply(new BigDecimal("2"));

        assertEquals("sandbox", checkout.get("environment"));
        assertEquals("POST", checkout.get("method"));
        assertEquals("https://pay-sandbox.sepay.vn/v1/checkout/init", checkout.get("checkoutUrl"));
        assertEquals("SEPAY_SANDBOX", order.get("payment_provider"));
        assertEquals("PENDING", order.get("status"));
        assertEquals(owner.toString(), order.get("user_id"));
        assertEquals(0, expected.compareTo(new BigDecimal(order.get("amount").toString())));
        assertEquals(0, expected.compareTo(new BigDecimal(fields.get("order_amount"))));
        assertEquals(order.get("id"), fields.get("order_invoice_number"));
        assertEquals("integration-merchant", fields.get("merchant"));
        assertEquals("VND", fields.get("currency"));
        assertEquals("PURCHASE", fields.get("operation"));
        assertEquals("BANK_TRANSFER", fields.get("payment_method"));
        assertNotNull(fields.get("signature"));
        assertTrue(fields.get("signature").length() >= 40);
        String serialized = mapper.writeValueAsString(checkout);
        assertFalse(serialized.contains(MERCHANT_SECRET));
        assertFalse(serialized.contains(IPN_SECRET));
        assertEquals(0L, count("user_subscriptions", owner));
        assertEquals(0L, count("invoices", owner));
    }

    @Test
    void checkoutAndRetryRequireAuthenticationAndEnforceOrderOwnership() throws Exception {
        mvc.perform(post(CHECKOUT).contentType(MediaType.APPLICATION_JSON).content(checkoutBody()))
            .andExpect(status().isUnauthorized());
        Map<String, Object> created = checkout();
        UUID orderId = id(created);
        String retry = "/api/v1/payments/orders/" + orderId + "/sepay-checkout";
        mvc.perform(post(retry)).andExpect(status().isUnauthorized());
        mvc.perform(post(retry).header("Authorization", otherAuthorization)).andExpect(status().isNotFound());
        Map<String, Object> repeated = response(mvc.perform(post(retry).header("Authorization", authorization)));
        assertEquals(orderId.toString(), order(repeated).get("id"));
        assertEquals(fields(created).get("order_invoice_number"), fields(repeated).get("order_invoice_number"));
        assertEquals(1L, count("payment_orders", owner));

        db.update("UPDATE payment_orders SET status='CANCELLED' WHERE id=?", orderId);
        mvc.perform(post(retry).header("Authorization", authorization)).andExpect(status().isConflict());
    }

    @Test
    void ipnRequiresTheConfiguredSecretAndNeverUsesAnAccessTokenAsAuthentication() throws Exception {
        Map<String, Object> checkout = checkout();
        String body = mapper.writeValueAsString(paid(checkout, UUID.randomUUID()));
        mvc.perform(post(IPN).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(
            post(IPN).header("X-Secret-Key", "wrong-ipn-secret").contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isUnauthorized());
        mvc.perform(
            post(IPN).header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isUnauthorized());
        assertPending(id(checkout));
    }

    @Test
    void authenticatedPaidIpnActivatesOnceAndReplayOrAnotherTransactionCannotCreateAnotherInvoice() throws Exception {
        Map<String, Object> checkout = checkout();
        UUID orderId = id(checkout);
        UUID transaction = UUID.randomUUID();
        Map<String, Object> payload = paid(checkout, transaction);
        ipn(payload).andExpect(status().isOk());
        assertCompletedOnce(orderId);
        assertEquals(1L, ledger(orderId));
        ipn(payload).andExpect(status().isOk());
        assertCompletedOnce(orderId);
        assertEquals(1L, ledger(orderId));
        ipn(paid(checkout, UUID.randomUUID())).andExpect(status().isOk());
        assertCompletedOnce(orderId);
        assertEquals(2L, ledger(orderId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"order_amount", "order_currency", "transaction_amount", "transaction_currency"})
    void mismatchedGatewayAndTransactionTotalsCannotActivateThePaidPlan(
        String field)
        throws Exception {
        Map<String, Object> checkout = checkout();
        Map<String, Object> payload = paid(checkout, UUID.randomUUID());
        Map<String, Object> part = map(payload.get(field.startsWith("order_") ? "order" : "transaction"));
        part.put(field, field.endsWith("currency") ? "USD" : "1.00");
        ipn(payload).andExpect(status().isBadRequest());
        assertPending(id(checkout));
        assertEquals(0L, ledger(id(checkout)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"order_status", "transaction_status", "transaction_type"})
    void unpaidOrNonPaymentNotificationsCannotActivateAPlan(
        String field)
        throws Exception {
        Map<String, Object> checkout = checkout();
        Map<String, Object> payload = paid(checkout, UUID.randomUUID());
        Map<String, Object> part = map(payload.get(field.startsWith("order_") ? "order" : "transaction"));
        part.put(field, field.equals("transaction_type") ? "VERIFY" : "PENDING");
        ipn(payload).andExpect(status().isBadRequest());
        assertPending(id(checkout));
    }

    @Test
    void sandboxIpnCannotCompleteAProductionOrder() throws Exception {
        Map<String, Object> checkout = checkout();
        db.update("UPDATE payment_orders SET payment_provider='SEPAY_PRODUCTION' WHERE id=?", id(checkout));
        ipn(paid(checkout, UUID.randomUUID())).andExpect(status().isBadRequest());
        assertPending(id(checkout));
    }

    @Test
    void theLegacySignedWebhookCannotSettleASePayOrder() throws Exception {
        Map<String, Object> checkout = checkout();
        Map<String, Object> stored = order(checkout);
        Map<String, Object> legacy = Map.of(
            "orderId",
            stored.get("id"),
            "providerOrderId",
            stored.get("provider_order_id"),
            "eventId",
            UUID.randomUUID().toString(),
            "status",
            "COMPLETED",
            "amount",
            stored.get("amount"),
            "currency",
            "VND"
        );
        String body = mapper.writeValueAsString(legacy);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(LEGACY_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature =
            HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        mvc.perform(
            post("/api/v1/payments/webhook").header("X-Payment-Timestamp", timestamp)
                .header("X-Payment-Signature", signature).contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().is4xxClientError());
        assertPending(id(checkout));
        assertEquals(0L, ledger(id(checkout)));
    }

    @Test
    void transactionVoidIsAcknowledgedAndRecordedWithoutActivatingOrRevokingThePlan() throws Exception {
        Map<String, Object> checkout = checkout();
        Map<String, Object> payload = paid(checkout, UUID.randomUUID());
        payload.put("notification_type", "TRANSACTION_VOID");
        map(payload.get("transaction")).put("transaction_type", "VOID");
        ipn(payload).andExpect(status().isOk());
        assertPending(id(checkout));
        assertEquals(1L, ledger(id(checkout)));
        ipn(paid(checkout, UUID.randomUUID())).andExpect(status().isOk());
        assertCompletedOnce(id(checkout));
        ipn(payload).andExpect(status().isOk());
        assertCompletedOnce(id(checkout));
    }

    @Test
    void aGatewayTransactionCannotBeReusedForAnotherMerchantInvoice() throws Exception {
        Map<String, Object> first = checkout();
        Map<String, Object> second = checkout();
        UUID transaction = UUID.randomUUID();
        ipn(paid(first, transaction)).andExpect(status().isOk());
        ipn(paid(second, transaction)).andExpect(status().isConflict());
        assertEquals("PENDING", db.scalar("SELECT status FROM payment_orders WHERE id=?", id(second)));
        assertEquals(0L, ledger(id(second)));
        assertCompletedOnce(id(first));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentDuplicateIpnCreatesExactlyOneSubscriptionAndInvoice() throws Exception {
        Map<String, Object> checkout = checkout();
        String body = mapper.writeValueAsString(paid(checkout, UUID.randomUUID()));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return mvc.perform(
                    post(IPN).header("X-Secret-Key", IPN_SECRET).contentType(MediaType.APPLICATION_JSON).content(body)
                ).andReturn().getResponse().getStatus();
            });
            var second = executor.submit(() -> {
                start.await();
                return mvc.perform(
                    post(IPN).header("X-Secret-Key", IPN_SECRET).contentType(MediaType.APPLICATION_JSON).content(body)
                ).andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertEquals(200, first.get(20, TimeUnit.SECONDS));
            assertEquals(200, second.get(20, TimeUnit.SECONDS));
        }
        assertCompletedOnce(id(checkout));
        assertEquals(1L, ledger(id(checkout)));
    }

    private UUID createUser() {
        return db.insert(
            "users",
            Map.of(
                "email",
                "sepay-integration-" + UUID.randomUUID() + "@example.test",
                "full_name",
                "SePay Integration Candidate",
                "password_hash",
                "test-only-unusable-hash",
                "status",
                "ACTIVE",
                "role",
                "CANDIDATE"
            )
        );
    }

    private String checkoutBody() {
        return mapper.writeValueAsString(Map.of("tierId", tier, "durationMonths", 2));
    }

    private Map<String, Object> checkout() throws Exception {
        return response(
            mvc.perform(
                post(CHECKOUT).header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                    .content(checkoutBody())
            )
        );
    }

    private Map<String, Object> response(
        ResultActions action)
        throws Exception {
        return mapper
            .readValue(action.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), Map.class);
    }

    private ResultActions ipn(
        Map<String, Object> payload)
        throws Exception {
        return mvc.perform(
            post(IPN).header("X-Secret-Key", IPN_SECRET).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(payload))
        );
    }

    private Map<String, Object> paid(
        Map<String, Object> checkout,
        UUID transactionId) {
        Map<String, Object> stored = order(checkout);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.now().getEpochSecond());
        payload.put("notification_type", "ORDER_PAID");
        String gatewayOrderId =
            UUID.nameUUIDFromBytes(("sepay-order-" + stored.get("id")).getBytes(StandardCharsets.UTF_8)).toString();
        payload.put(
            "order",
            new LinkedHashMap<>(
                Map.of(
                    "id",
                    gatewayOrderId,
                    "order_id",
                    "GW-" + stored.get("id"),
                    "order_status",
                    "CAPTURED",
                    "order_currency",
                    "VND",
                    "order_amount",
                    stored.get("amount").toString(),
                    "order_invoice_number",
                    stored.get("id"),
                    "order_description",
                    "Integration subscription"
                )
            )
        );
        payload.put(
            "transaction",
            new LinkedHashMap<>(
                Map.of(
                    "id",
                    transactionId.toString(),
                    "payment_method",
                    "BANK_TRANSFER",
                    "transaction_id",
                    "TX-" + transactionId,
                    "transaction_type",
                    "PAYMENT",
                    "transaction_status",
                    "APPROVED",
                    "transaction_amount",
                    stored.get("amount").toString(),
                    "transaction_currency",
                    "VND"
                )
            )
        );
        payload.put("customer", Map.of("id", UUID.randomUUID().toString(), "customer_id", owner.toString()));
        return payload;
    }

    private UUID id(
        Map<String, Object> checkout) {
        return UUID.fromString(order(checkout).get("id").toString());
    }

    private Map<String, Object> order(
        Map<String, Object> checkout) {
        return map(checkout.get("order"));
    }

    private Map<String, String> fields(
        Map<String, Object> checkout) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Object entry : (List<?>) checkout.get("formFields")) {
            Map<String, Object> field = map(entry);
            result.put(field.get("name").toString(), field.get("value").toString());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(
        Object value) {
        return (Map<String, Object>) value;
    }

    private long count(
        String table,
        UUID account) {
        assertTrue(List.of("payment_orders", "invoices", "user_subscriptions").contains(table));
        return ((Number) db.scalar("SELECT count(*) FROM " + table + " WHERE user_id=?", account)).longValue();
    }

    private long ledger(
        UUID orderId) {
        return ((Number) db.scalar("SELECT count(*) FROM payment_webhook_events WHERE payment_order_id=?", orderId))
            .longValue();
    }

    private void assertPending(
        UUID orderId) {
        assertEquals("PENDING", db.scalar("SELECT status FROM payment_orders WHERE id=?", orderId));
        assertEquals(0L, count("invoices", owner));
        assertEquals(0L, count("user_subscriptions", owner));
    }

    private void assertCompletedOnce(
        UUID orderId) {
        assertEquals("COMPLETED", db.scalar("SELECT status FROM payment_orders WHERE id=?", orderId));
        assertEquals(
            1L,
            ((Number) db.scalar("SELECT count(*) FROM invoices WHERE payment_order_id=? AND status='PAID'", orderId))
                .longValue()
        );
        assertEquals(
            1L,
            ((Number) db.scalar("SELECT count(*) FROM user_subscriptions WHERE user_id=? AND status='ACTIVE'", owner))
                .longValue()
        );
        assertEquals(
            tier,
            db.scalar("SELECT tier_id FROM user_subscriptions WHERE user_id=? AND status='ACTIVE'", owner)
        );
    }
}
