package com.example.intelligent_cv_matching_ai_agent.service.shared.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.consultation.ConsultationServiceRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.consultation.ConsultationOrderRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation.ConsultationCatalogRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.billing.BillingRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.shared.billing.SePayPaymentService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;

@Service
public class ConsultationCatalogService {

    private final ConsultationCatalogRepository db;

    private final BillingRepository billing;

    private final SePayPaymentService sepay;

    private final CurrentUser user;

    public ConsultationCatalogService(
        ConsultationCatalogRepository db,
        BillingRepository billing,
        SePayPaymentService sepay,
        CurrentUser user) {
        this.db = db;
        this.billing = billing;
        this.sepay = sepay;
        this.user = user;
    }

    public List<Map<String, Object>> services() {
        return db.services(false);
    }

    public List<Map<String, Object>> adminServices() {
        user.requireOperationsRole();
        return db.services(true);
    }

    @Transactional
    public Map<String, Object> save(
        UUID id,
        ConsultationServiceRequest input) {
        user.requireOperationsRole();
        boolean live = "LIVE".equals(input.deliveryMode());
        if (live != (input.durationMinutes() != null)) {
            throw ApiException.badRequest("Only live consultations require a duration");
        }
        boolean pack = input.isSessionPack() == null ? live : input.isSessionPack();
        if ((pack && (!live || !Set.of(30, 60).contains(input.durationMinutes()))) || (input.isActive() && !pack)) {
            throw ApiException.badRequest("Active products must be 30 or 60 minute consultation session packs");
        }
        try {
            return db.save(
                id,
                fields(
                    "service_code",
                    input.serviceCode(),
                    "display_name",
                    input.displayName(),
                    "description",
                    input.description(),
                    "delivery_mode",
                    input.deliveryMode(),
                    "duration_minutes",
                    input.durationMinutes(),
                    "price",
                    input.price(),
                    "currency",
                    "VND",
                    "is_active",
                    input.isActive(),
                    "sessions_count",
                    input.sessionsCount() == null ? 1 : input.sessionsCount(),
                    "is_session_pack",
                    pack
                )
            );
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw ApiException.conflict("Service code already exists");
        }
    }

    @Transactional
    public Map<String, Object> order(
        ConsultationOrderRequest input) {
        user.requireRole("CANDIDATE");
        String provider = sepay.consultationProvider();
        Map<String, Object> service = db.service(input.serviceId());
        if (!Boolean.TRUE.equals(service.get("is_active"))) {
            throw ApiException.conflict("Consultation service is unavailable");
        }
        if (!Boolean.TRUE.equals(service.get("is_session_pack"))) {
            throw ApiException.badRequest("Select a consultation session pack before booking");
        }
        UUID id = db.insertOrder(
            fields(
                "candidate_user_id",
                user.id(),
                "service_id",
                input.serviceId(),
                "service_code",
                service.get("service_code"),
                "service_name",
                service.get("display_name"),
                "delivery_mode",
                service.get("delivery_mode"),
                "duration_minutes",
                service.get("duration_minutes"),
                "amount",
                service.get("price"),
                "currency",
                service.get("currency"),
                "subject",
                input.subject() == null || input.subject().isBlank() ? service.get("display_name") : input.subject(),
                "description",
                input.description() == null ? "" : input.description(),
                "sessions_total",
                service.get("sessions_count"),
                "sessions_remaining",
                0,
                "is_session_pack",
                true,
                "status",
                "PENDING_PAYMENT"
            )
        );
        UUID payment = UUID.randomUUID();
        billing.insertPaymentOrders(
            fields(
                "id",
                payment,
                "user_id",
                user.id(),
                "order_type",
                "CONSULTATION",
                "consultation_order_id",
                id,
                "amount",
                service.get("price"),
                "currency",
                service.get("currency"),
                "payment_provider",
                provider,
                "provider_order_id",
                payment.toString(),
                "status",
                "PENDING"
            )
        );
        return fields(
            "id",
            id,
            "payment_order_id",
            payment,
            "status",
            "PENDING_PAYMENT",
            "amount",
            service.get("price"),
            "currency",
            service.get("currency")
        );
    }

    public List<Map<String, Object>> orders() {
        user.id();
        return db.orders(user.id(), user.hasOperationsRole());
    }

    @Transactional
    public Map<String, Object> cancel(
        UUID id) {
        user.requireRole("CANDIDATE");
        db.cancel(id, user.id());
        return Map.of("id", id, "status", "CANCELLED");
    }
}
