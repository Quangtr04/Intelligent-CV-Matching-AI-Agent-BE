package com.example.intelligent_cv_matching_ai_agent.service.shared.billing;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.billing.PaymentOrderRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.billing.PaymentReportRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.billing.PaymentWebhookRequest;

import com.example.intelligent_cv_matching_ai_agent.util.shared.billing.WebhookVerifier;

import org.springframework.stereotype.Service;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.billing.BillingQueryRepository;
import tools.jackson.databind.json.JsonMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BillingQueryService {

    private final BillingQueryRepository db;

    private final CurrentUser user;

    private final BillingService billing;

    private final EntitlementService entitlement;

    private final WebhookVerifier verifier;

    private final JsonMapper mapper = JsonMapper.builder().build();

    public BillingQueryService(
        BillingQueryRepository db,
        CurrentUser user,
        BillingService billing,
        EntitlementService entitlement,
        WebhookVerifier verifier) {
        this.db = db;
        this.user = user;
        this.billing = billing;
        this.entitlement = entitlement;
        this.verifier = verifier;
    }

    public List<Map<String, Object>> tiers() {
        return db.listSubscriptionQuotasByTierId();
    }

    public Map<String, Object> subscription() {
        return entitlement.summary(user.id());
    }

    public List<Map<String, Object>> history() {
        return db.listUserSubscriptionsByUserId(user.id());
    }

    public Map<String, Object> cancel(
        UUID id) {
        db.getUserSubscriptionsById(id, user.id());

        db.updateUserSubscriptionsById(id, user.id());

        return Map.of("message", "Subscription cancelled");
    }

    public Map<String, Object> order(
        PaymentOrderRequest body) {
        return billing.order(user.id(), body.tierId(), body.durationMonths());
    }

    public List<Map<String, Object>> orders() {
        return db.listPaymentOrdersByUserId(user.id());
    }

    public Map<String, Object> order(
        UUID id) {
        return db.getPaymentOrdersById(id, user.id());
    }

    public Map<String, Object> report(
        UUID id,
        PaymentReportRequest body) {
        order(id);

        UUID report = db.insertPaymentReports(
            Map.of(
                "payment_order_id",
                id,
                "user_id",
                user.id(),
                "subject",
                body.subject(),
                "description",
                body.description(),
                "status",
                "OPEN",
                "created_at",
                OffsetDateTime.now()
            )
        );

        return db.getPaymentReportsById(report);
    }

    public List<Map<String, Object>> reports() {
        return db.listPaymentReportsByUserId(user.id());
    }

    public List<Map<String, Object>> invoices() {
        return db.listInvoicesByUserId(user.id());
    }

    public Map<String, Object> invoice(
        UUID id) {
        return db.getInvoicesById(id, user.id());
    }

    public Map<String, Object> webhook(
        String timestamp,
        String signature,
        String body) {
        if (body.length() > 65536)
            throw ApiException.badRequest("Webhook body too large");

        verifier.verify(timestamp, signature, body);
        PaymentWebhookRequest event;

        try {
            event = mapper.readValue(body, PaymentWebhookRequest.class);
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("Invalid webhook JSON");
        }

        return billing.webhook(event);
    }
}
