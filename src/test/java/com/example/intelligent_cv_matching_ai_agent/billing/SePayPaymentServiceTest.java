package com.example.intelligent_cv_matching_ai_agent.billing;

import com.example.intelligent_cv_matching_ai_agent.config.shared.billing.SePayProperties;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.billing.PaymentOrderRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.billing.PaymentWebhookRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.response.shared.billing.SePayCheckoutResponse;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.billing.BillingQueryRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.billing.BillingService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.billing.SePayPaymentService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SePayPaymentServiceTest {

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "merchantId", "merchantSecret", "ipnSecret", "environment", "method"})
    void incompleteCheckoutConfigurationDoesNotCreateAnOrder(
        String setting) {
        Fixture fixture = new Fixture();
        switch (setting) {
            case "disabled" -> fixture.config.setEnabled(false);
            case "merchantId" -> fixture.config.setMerchantId("");
            case "merchantSecret" -> fixture.config.setMerchantSecret("YOUR_MERCHANT_SECRET_KEY");
            case "ipnSecret" -> fixture.config.setIpnSecret("short");
            case "environment" -> fixture.config.setEnvironment("unknown");
            case "method" -> fixture.config.setPaymentMethod("UNSUPPORTED");
            default -> throw new AssertionError(setting);
        }
        assertEquals(
            503,
            assertThrows(ApiException.class, () -> fixture.service.createCheckout(fixture.request())).status()
        );
        verifyNoInteractions(fixture.billing, fixture.db);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "http://frontend.example.test/candidate/subscription",
            "https://localhost:5173/candidate/subscription",
            "https://127.0.0.1/candidate/subscription",
            "https://user:password@frontend.example.test/candidate/subscription",
            "https://frontend.example.test/#subscription",
            "not-a-url"}
    )
    void invalidReturnConfigurationFailsBeforeCreatingAnyOrder(
        String returnUrl) {
        Fixture fixture = new Fixture();
        fixture.config.setReturnUrl(returnUrl);
        assertEquals(
            503,
            assertThrows(ApiException.class, () -> fixture.service.createCheckout(fixture.request())).status()
        );
        verifyNoInteractions(fixture.billing, fixture.db);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sandbox", "production"})
    void checkoutUsesTheOfficialEnvironmentUrlAndOrderedFormWithoutReturningSecrets(
        String environment) {
        Fixture fixture = new Fixture();
        fixture.config.setEnvironment(environment);
        when(fixture.billing.sepayOrder(eq(fixture.owner), eq(fixture.tier), eq(1), anyString()))
            .thenReturn(fixture.order(environment));
        SePayCheckoutResponse response = fixture.service.createCheckout(fixture.request());
        assertEquals(
            "sandbox".equals(environment)
                ? "https://pay-sandbox.sepay.vn/v1/checkout/init"
                : "https://pay.sepay.vn/v1/checkout/init",
            response.checkoutUrl()
        );
        assertEquals("POST", response.method());
        assertEquals(environment, response.environment());
        assertEquals(
            List.of(
                "order_amount",
                "merchant",
                "currency",
                "operation",
                "order_description",
                "order_invoice_number",
                "customer_id",
                "payment_method",
                "success_url",
                "error_url",
                "cancel_url",
                "signature"
            ),
            response.formFields().stream().map(SePayCheckoutResponse.FormField::name).toList()
        );
        Map<String, String> fields = new LinkedHashMap<>();
        response.formFields().forEach(field -> fields.put(field.name(), field.value()));
        assertEquals("199000", fields.get("order_amount"));
        assertEquals(fixture.orderId.toString(), fields.get("order_invoice_number"));
        assertEquals(fixture.owner.toString(), fields.get("customer_id"));
        assertTrue(fields.get("success_url").contains("payment=success"));
        assertTrue(fields.get("success_url").contains("orderId=" + fixture.orderId));
        assertEquals(32, java.util.Base64.getDecoder().decode(fields.get("signature")).length);
        String json = fixture.mapper.writeValueAsString(response);
        assertFalse(json.contains(fixture.config.getMerchantSecret()));
        assertFalse(json.contains(fixture.config.getIpnSecret()));
        verify(fixture.billing).sepayOrder(
            fixture.owner,
            fixture.tier,
            1,
            "sandbox".equals(environment) ? "SEPAY_SANDBOX" : "SEPAY_PRODUCTION"
        );
        verify(fixture.billing, never()).confirmSePay(any(), anyString());
    }

    @Test
    void signingAnExistingPendingOrderDoesNotCreateAnotherOrder() {
        Fixture fixture = new Fixture();
        when(fixture.db.getPaymentOrdersById(fixture.orderId, fixture.owner)).thenReturn(fixture.order("sandbox"));
        assertEquals(fixture.orderId, fixture.service.checkout(fixture.orderId).order().get("id"));
        verify(fixture.db).getPaymentOrdersById(fixture.orderId, fixture.owner);
        verifyNoInteractions(fixture.billing);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"wrong-ipn-secret", "short"})
    void ipnAuthenticatesBeforeAttemptingToParseAnInvalidBody(
        String header) {
        Fixture fixture = new Fixture();
        assertEquals(401, assertThrows(ApiException.class, () -> fixture.service.ipn(header, "{not-json")).status());
        verifyNoInteractions(fixture.billing, fixture.db);
    }

    @Test
    void missingIpnConfigurationCannotAuthenticateAnyPayload() {
        Fixture fixture = new Fixture();
        fixture.config.setIpnSecret("");
        assertEquals(
            503,
            assertThrows(ApiException.class, () -> fixture.service.ipn("", fixture.body(fixture.paid()))).status()
        );
        verifyNoInteractions(fixture.billing, fixture.db);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "", "null", "[]"})
    void authenticatedMalformedJsonIsRejectedWithoutSettlement(
        String body) {
        Fixture fixture = new Fixture();
        assertEquals(
            400,
            assertThrows(ApiException.class, () -> fixture.service.ipn(fixture.config.getIpnSecret(), body)).status()
        );
        verifyNoInteractions(fixture.billing, fixture.db);
    }

    @Test
    void oversizedIpnIsRejectedWithoutSettlement() {
        Fixture fixture = new Fixture();
        assertEquals(
            400,
            assertThrows(
                ApiException.class,
                () -> fixture.service.ipn(fixture.config.getIpnSecret(), "x".repeat(65537))
            ).status()
        );
        verifyNoInteractions(fixture.billing, fixture.db);
    }

    @Test
    void delayedAuthenticatedIpnMapsMerchantInvoiceRatherThanSePayRemoteOrderId() {
        Fixture fixture = new Fixture();
        when(fixture.billing.confirmSePay(any(), eq("SEPAY_SANDBOX")))
            .thenReturn(Map.of("accepted", true, "duplicate", false));
        Map<String, Object> paid = fixture.paid();
        paid.put("timestamp", 1L);
        var result = fixture.service.ipn(fixture.config.getIpnSecret(), fixture.body(paid));
        assertEquals(true, result.get("success"));
        ArgumentCaptor<PaymentWebhookRequest> event = ArgumentCaptor.forClass(PaymentWebhookRequest.class);
        verify(fixture.billing).confirmSePay(event.capture(), eq("SEPAY_SANDBOX"));
        assertEquals(fixture.orderId, event.getValue().orderId());
        assertEquals(fixture.orderId.toString(), event.getValue().providerOrderId());
        assertEquals("ORDER_PAID:" + fixture.transactionId, event.getValue().eventId());
        assertEquals("COMPLETED", event.getValue().status());
        assertEquals(0, new BigDecimal("199000").compareTo(event.getValue().amount()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"notification", "orderStatus", "transactionStatus", "transactionType"})
    void unconfirmedGatewayStatesCannotBeTranslatedIntoCompletedPayments(
        String changed) {
        Fixture fixture = new Fixture();
        Map<String, Object> event = fixture.paid();
        switch (changed) {
            case "notification" -> event.put("notification_type", "UNSUPPORTED");
            case "orderStatus" -> nested(event, "order").put("order_status", "PENDING");
            case "transactionStatus" -> nested(event, "transaction").put("transaction_status", "DECLINED");
            case "transactionType" -> nested(event, "transaction").put("transaction_type", "REFUND");
            default -> throw new AssertionError(changed);
        }
        assertEquals(
            400,
            assertThrows(
                ApiException.class,
                () -> fixture.service.ipn(fixture.config.getIpnSecret(), fixture.body(event))
            ).status()
        );
        verifyNoInteractions(fixture.billing, fixture.db);
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "orderCurrency", "transactionCurrency", "fraction"})
    void inconsistentOrNonIntegralVndAmountsCannotReachSettlement(
        String changed) {
        Fixture fixture = new Fixture();
        Map<String, Object> event = fixture.paid();
        switch (changed) {
            case "amount" -> nested(event, "transaction").put("transaction_amount", "1");
            case "orderCurrency" -> nested(event, "order").put("order_currency", "USD");
            case "transactionCurrency" -> nested(event, "transaction").put("transaction_currency", "USD");
            case "fraction" -> {
                nested(event, "order").put("order_amount", "199000.50");
                nested(event, "transaction").put("transaction_amount", "199000.50");
            }
            default -> throw new AssertionError(changed);
        }
        assertEquals(
            400,
            assertThrows(
                ApiException.class,
                () -> fixture.service.ipn(fixture.config.getIpnSecret(), fixture.body(event))
            ).status()
        );
        verifyNoInteractions(fixture.billing, fixture.db);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(
        Map<String, Object> event,
        String key) {
        return (Map<String, Object>) event.get(key);
    }

    private static final class Fixture {

        final SePayProperties config = new SePayProperties();

        final BillingService billing = mock(BillingService.class);

        final BillingQueryRepository db = mock(BillingQueryRepository.class);

        final CurrentUser user = mock(CurrentUser.class);

        final JsonMapper mapper = JsonMapper.builder().build();

        final UUID owner = UUID.randomUUID();

        final UUID tier = UUID.randomUUID();

        final UUID orderId = UUID.randomUUID();

        final UUID transactionId = UUID.randomUUID();

        final SePayPaymentService service;

        Fixture() {
            config.setEnabled(true);
            config.setMerchantId("MERCHANT_UNIT");
            config.setMerchantSecret("unit-only-merchant-secret-value");
            config.setIpnSecret("unit-only-ipn-secret-at-least-thirty-two-bytes");
            config.setReturnUrl("https://frontend.example.test/candidate/subscription");
            when(user.id()).thenReturn(owner);
            service = new SePayPaymentService(config, billing, db, user, mapper);
        }

        PaymentOrderRequest request() {
            return new PaymentOrderRequest(tier, 1);
        }

        Map<String, Object> order(
            String environment) {
            return Map.of(
                "id",
                orderId,
                "user_id",
                owner,
                "tier_id",
                tier,
                "duration_months",
                1,
                "amount",
                new BigDecimal("199000.00"),
                "currency",
                "VND",
                "status",
                "PENDING",
                "payment_provider",
                "sandbox".equals(environment) ? "SEPAY_SANDBOX" : "SEPAY_PRODUCTION",
                "provider_order_id",
                orderId.toString()
            );
        }

        Map<String, Object> paid() {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("timestamp", 1L);
            event.put("notification_type", "ORDER_PAID");
            event.put(
                "order",
                new LinkedHashMap<>(
                    Map.of(
                        "id",
                        UUID.randomUUID().toString(),
                        "order_id",
                        "GW-123",
                        "order_status",
                        "CAPTURED",
                        "order_currency",
                        "VND",
                        "order_amount",
                        "199000.00",
                        "order_invoice_number",
                        orderId.toString()
                    )
                )
            );
            event.put(
                "transaction",
                new LinkedHashMap<>(
                    Map.of(
                        "id",
                        transactionId.toString(),
                        "transaction_id",
                        "remote-transaction-123",
                        "transaction_type",
                        "PAYMENT",
                        "transaction_status",
                        "APPROVED",
                        "transaction_amount",
                        "199000",
                        "transaction_currency",
                        "VND"
                    )
                )
            );
            event.put("customer", Map.of("id", UUID.randomUUID().toString(), "customer_id", owner.toString()));
            return event;
        }

        String body(
            Map<String, Object> value) {
            return mapper.writeValueAsString(value);
        }
    }
}
