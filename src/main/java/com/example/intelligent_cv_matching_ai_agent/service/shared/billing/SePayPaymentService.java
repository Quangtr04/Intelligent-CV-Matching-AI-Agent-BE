package com.example.intelligent_cv_matching_ai_agent.service.shared.billing;

import com.example.intelligent_cv_matching_ai_agent.config.shared.billing.SePayProperties;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.billing.PaymentOrderRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.billing.PaymentWebhookRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.billing.SePayIpnRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.response.shared.billing.SePayCheckoutResponse;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.billing.BillingQueryRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.util.shared.billing.SePayCheckoutSigner;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

/** SePay Payment Gateway form signing and authenticated IPN; never trusts browser redirects as payment. */
@Service
public class SePayPaymentService {

    private final SePayProperties config;

    private final BillingService billing;

    private final BillingQueryRepository db;

    private final CurrentUser user;

    private final ObjectMapper mapper;

    public SePayPaymentService(
        SePayProperties config,
        BillingService billing,
        BillingQueryRepository db,
        CurrentUser user,
        ObjectMapper mapper) {
        this.config = config;
        this.billing = billing;
        this.db = db;
        this.user = user;
        this.mapper = mapper;
    }

    @Transactional
    public SePayCheckoutResponse createCheckout(
        PaymentOrderRequest request) {
        requireCheckout();
        Map<String, Object> order =
            billing.sepayOrder(user.id(), request.tierId(), request.durationMonths(), provider());
        return form(order);
    }

    public SePayCheckoutResponse checkout(
        UUID orderId) {
        requireCheckout();
        return form(db.getPaymentOrdersById(orderId, user.id()));
    }

    private SePayCheckoutResponse form(
        Map<String, Object> order) {
        if (!provider().equals(order.get("payment_provider")))
            throw ApiException.badRequest("Payment order does not belong to this SePay environment");
        if (!"PENDING".equals(order.get("status")))
            throw ApiException.conflict("Only pending payment orders can be checked out");
        BigDecimal amount = new BigDecimal(order.get("amount").toString());
        if (!"VND".equals(order.get("currency").toString().trim()) || amount.signum() <= 0
            || amount.stripTrailingZeros().scale() > 0)
            throw ApiException.badRequest("SePay requires a positive whole-number VND amount");
        String id = order.get("id").toString();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("order_amount", amount.setScale(0).toPlainString());
        fields.put("merchant", config.getMerchantId());
        fields.put("currency", "VND");
        fields.put("operation", "PURCHASE");
        fields.put("order_description", "Thanh toan goi dang ky EduNexus " + order.get("duration_months") + " thang");
        fields.put("order_invoice_number", order.get("provider_order_id").toString());
        fields.put("customer_id", order.get("user_id").toString());
        fields.put("payment_method", config.getPaymentMethod());
        fields.put("success_url", returnUrl(id, "success"));
        fields.put("error_url", returnUrl(id, "error"));
        fields.put("cancel_url", returnUrl(id, "cancel"));
        fields.put("signature", SePayCheckoutSigner.sign(fields, config.getMerchantSecret()));
        return new SePayCheckoutResponse(
            order,
            checkoutUrl(),
            "POST",
            fields.entrySet().stream().map(item -> new SePayCheckoutResponse.FormField(item.getKey(), item.getValue()))
                .toList(),
            environment()
        );
    }

    public Map<String, Object> ipn(
        String secret,
        String body) {
        requireEnabled();
        String expected = config.getIpnSecret();
        if (expected == null || expected.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new ApiException(503, "SePay IPN authentication is not configured");
        if (secret == null || secret.length() > 512 || !MessageDigest
            .isEqual(expected.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8)))
            throw new ApiException(401, "Invalid SePay IPN secret");
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > 65536)
            throw ApiException.badRequest("Invalid SePay IPN body");
        SePayIpnRequest event;
        try {
            event = mapper.readValue(body, SePayIpnRequest.class);
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("Invalid SePay IPN JSON");
        }
        if (event == null || event.timestamp() == null || event.timestamp() <= 0 || event.order() == null
            || event.transaction() == null)
            throw ApiException.badRequest("Missing SePay IPN fields");
        String invoice = required(event.order().orderInvoiceNumber(), "invoice number", 255);
        UUID orderId;
        try {
            orderId = UUID.fromString(invoice);
            if (!orderId.toString().equals(invoice))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("Invalid SePay merchant invoice reference");
        }
        required(event.order().id(), "gateway order ID", 255);
        required(event.order().orderId(), "gateway order reference", 255);
        String transactionId = required(event.transaction().id(), "gateway transaction ID", 200);
        String type = event.notificationType();
        if ("TRANSACTION_VOID".equals(type)) {
            var result = new LinkedHashMap<>(
                billing.acknowledgeSePayVoid(orderId, invoice, "TRANSACTION_VOID:" + transactionId, provider())
            );
            result.put("success", true);
            return result;
        }
        if (!"ORDER_PAID".equals(type) || !"CAPTURED".equals(event.order().orderStatus())
            || !"PAYMENT".equals(event.transaction().transactionType())
            || !"APPROVED".equals(event.transaction().transactionStatus()))
            throw ApiException.badRequest("SePay IPN is not a confirmed payment");
        BigDecimal amount = event.order().orderAmount();
        BigDecimal transferred = event.transaction().transactionAmount();
        if (amount == null || transferred == null || amount.signum() <= 0 || amount.compareTo(transferred) != 0
            || amount.stripTrailingZeros().scale() > 0 || !"VND".equals(event.order().orderCurrency())
            || !"VND".equals(event.transaction().transactionCurrency()))
            throw ApiException.badRequest("SePay order and transaction amounts or currencies do not match");
        var result = new LinkedHashMap<>(
            billing.confirmSePay(
                new PaymentWebhookRequest(orderId, invoice, "ORDER_PAID:" + transactionId, "COMPLETED", amount, "VND"),
                provider()
            )
        );
        result.put("success", true);
        return result;
    }

    private String returnUrl(
        String orderId,
        String state) {
        return UriComponentsBuilder.fromUriString(config.getReturnUrl()).replaceQueryParam("orderId", orderId)
            .replaceQueryParam("payment", state).build().encode().toUriString();
    }

    private String checkoutUrl() {
        return "sandbox".equals(environment())
            ? "https://pay-sandbox.sepay.vn/v1/checkout/init"
            : "https://pay.sepay.vn/v1/checkout/init";
    }

    private String provider() {
        return "SEPAY_" + environment().toUpperCase(Locale.ROOT);
    }

    private String environment() {
        String value = config.getEnvironment();
        if (!"sandbox".equals(value) && !"production".equals(value))
            throw new ApiException(503, "Configure a valid SePay environment");
        return value;
    }

    private void requireEnabled() {
        if (!config.isEnabled())
            throw new ApiException(503, "SePay Payment Gateway is not enabled");
        environment();
    }

    private void requireCheckout() {
        requireEnabled();
        if (placeholder(config.getMerchantId()) || placeholder(config.getMerchantSecret()))
            throw new ApiException(503, "Configure SePay merchant credentials on the backend");
        if (config.getIpnSecret() == null || config.getIpnSecret().getBytes(StandardCharsets.UTF_8).length < 32)
            throw new ApiException(503, "Configure SePay IPN authentication before creating payment orders");
        if (!Set.of("BANK_TRANSFER", "NAPAS_BANK_TRANSFER", "CARD").contains(config.getPaymentMethod()))
            throw new ApiException(503, "Configure a supported SePay payment method");
        try {
            URI target = URI.create(config.getReturnUrl());
            String host = target.getHost();
            if (!"https".equalsIgnoreCase(target.getScheme()) || host == null || target.getUserInfo() != null
                || target.getFragment() != null || host.equalsIgnoreCase("localhost") || host.endsWith(".localhost")
                || host.startsWith("127.") || host.equals("[::1]") || host.equals("::1"))
                throw new IllegalArgumentException();
        } catch (RuntimeException ex) {
            throw new ApiException(503, "Configure a public HTTPS SePay return URL");
        }
    }

    private static boolean placeholder(
        String value) {
        return value == null || value.isBlank() || value.startsWith("YOUR_") || value.startsWith("replace-");
    }

    private static String required(
        String value,
        String name,
        int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw ApiException.badRequest("Invalid SePay " + name);
        return value;
    }
}
