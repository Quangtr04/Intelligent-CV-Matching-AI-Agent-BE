package com.example.intelligent_cv_matching_ai_agent.service.shared.billing;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.billing.BillingRepository;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.billing.PaymentWebhookRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class BillingService {

    private final BillingRepository db;

    private final String provider;

    public BillingService(
        BillingRepository db,
        @Value("${app.payment.provider:MANUAL_GATEWAY}")
        String provider) {
        this.db = db;
        this.provider = provider;
    }

    @Transactional
    public Map<String, Object> order(
        UUID user,
        UUID tierId,
        int months) {
        if (months < 1 || months > 12)
            throw ApiException.badRequest("Duration must be between 1 and 12 months");

        Map<String, Object> tier = db.getSubscriptionTiersById(tierId);
        BigDecimal price = new BigDecimal(tier.get("price_monthly").toString());
        if (price.signum() <= 0)
            throw ApiException.badRequest("The free tier does not require payment");

        BigDecimal amount = price.multiply(BigDecimal.valueOf(months));
        if (amount.compareTo(new BigDecimal("99999999.99")) > 0)
            throw ApiException.badRequest("Payment total exceeds the supported amount; select a shorter duration");

        UUID id = UUID.randomUUID();

        db.insertPaymentOrders(
            Map.of(
                "id",
                id,
                "user_id",
                user,
                "tier_id",
                tierId,
                "duration_months",
                months,
                "amount",
                amount,
                "currency",
                tier.get("currency"),
                "payment_provider",
                provider,
                "provider_order_id",
                id.toString(),
                "status",
                "PENDING",
                "created_at",
                OffsetDateTime.now()
            )
        );

        return db.getPaymentOrdersById(id);
    }

    @Transactional
    public Map<String, Object> webhook(
        PaymentWebhookRequest event) {
        if (event.orderId() == null || event.providerOrderId() == null || event.eventId() == null
            || event.eventId().isBlank() || event.eventId().length() > 255 || event.providerOrderId().length() > 255
            || event.amount() == null || event.currency() == null)
            throw ApiException.badRequest("Invalid payment webhook fields");
        if (event.status() == null || !java.util.Set.of("COMPLETED", "FAILED", "CANCELLED").contains(event.status()))
            throw ApiException.badRequest("Unsupported payment status");

        Map<String, Object> order = db.getPaymentOrdersByIdForUpdate(event.orderId());
        if (!provider.equals(order.get("payment_provider"))
            || !event.providerOrderId().equals(order.get("provider_order_id")))
            throw ApiException.badRequest("Payment provider order mismatch");
        if (new BigDecimal(order.get("amount").toString()).compareTo(event.amount()) != 0
            || !order.get("currency").toString().trim().equals(event.currency()))
            throw ApiException.badRequest("Payment amount or currency mismatch");

        int received = db.updatePaymentWebhookEvents(provider, event.eventId(), event.orderId());

        if (received == 0) {
            Map<String, Object> previous = db.getPaymentWebhookEventsByProvider(provider, event.eventId());
            if (!event.orderId().equals(previous.get("payment_order_id")))
                throw ApiException.conflict("Event ID reused for another order");

            return Map.of("accepted", true, "duplicate", true, "orderId", event.orderId());
        }

        String current = order.get("status").toString();

        if (!"PENDING".equals(current)) {
            if (current.equals(event.status()))
                return Map.of("accepted", true, "duplicate", true, "orderId", event.orderId());

            throw ApiException.conflict("Payment already finalized");
        }

        if ("COMPLETED".equals(event.status()))
            activate(order);
        else
            db.updatePaymentOrdersById(event.status(), event.orderId());

        return Map.of("accepted", true, "duplicate", false, "orderId", event.orderId());
    }

    private void activate(
        Map<String, Object> order) {
        UUID user = (UUID) order.get("user_id");
        db.getUsersByIdForUpdate(user);

        db.updateUserSubscriptionsByUserId(user);

        OffsetDateTime now = OffsetDateTime.now();
        int months = ((Number) order.get("duration_months")).intValue();

        UUID subscription = db.insertUserSubscriptions(
            Map.of(
                "user_id",
                user,
                "tier_id",
                order.get("tier_id"),
                "status",
                "ACTIVE",
                "started_at",
                now,
                "expires_at",
                now.plusMonths(months),
                "auto_renew",
                false,
                "created_at",
                now,
                "updated_at",
                now
            )
        );
        db.updatePaymentOrdersById2(subscription, order.get("id"));
        db.insertInvoices(
            Map.of(
                "user_id",
                user,
                "payment_order_id",
                order.get("id"),
                "invoice_number",
                "EN-" + order.get("id"),
                "subtotal",
                order.get("amount"),
                "tax_amount",
                BigDecimal.ZERO,
                "total_amount",
                order.get("amount"),
                "currency",
                order.get("currency"),
                "status",
                "PAID",
                "issued_at",
                now,
                "paid_at",
                now
            )
        );
        db.insertNotifications(
            Map.of(
                "user_id",
                user,
                "type",
                "PAYMENT_SUCCESS",
                "title",
                "Subscription activated",
                "content",
                "Your paid subscription is now active.",
                "reference_type",
                "PAYMENT_ORDER",
                "reference_id",
                order.get("id"),
                "created_at",
                now
            )
        );
    }
}
