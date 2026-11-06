package com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class ConsultationCatalogRepository {

    private final SqlStore db;

    public ConsultationCatalogRepository(
        SqlStore db) {
        this.db = db;
    }

    public List<Map<String, Object>> services(
        boolean includeInactive) {
        return db.list(
            "SELECT * FROM consultation_services " + (includeInactive ? "" : "WHERE is_active AND is_session_pack ")
                + "ORDER BY price,service_code"
        );
    }

    public Map<String, Object> service(
        UUID id) {
        return db.one("SELECT * FROM consultation_services WHERE id=? FOR SHARE", id);
    }

    public Map<String, Object> save(
        UUID id,
        Map<String, Object> values) {
        if (id == null) {
            id = db.insert("consultation_services", values);
        } else {
            db.one("SELECT id FROM consultation_services WHERE id=? FOR UPDATE", id);
            if (!db.optional(
                "SELECT id FROM consultation_services WHERE service_code=? AND id<>?",
                values.get("service_code"),
                id
            ).isEmpty()) {
                throw com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException
                    .conflict("Service code already exists");
            }
            db.update(
                "UPDATE consultation_services SET service_code=?,display_name=?,description=?,"
                    + "delivery_mode=?,duration_minutes=?,price=?,is_active=?,sessions_count=?,"
                    + "is_session_pack=?,updated_at=now() WHERE id=?",
                values.get("service_code"),
                values.get("display_name"),
                values.get("description"),
                values.get("delivery_mode"),
                values.get("duration_minutes"),
                values.get("price"),
                values.get("is_active"),
                values.get("sessions_count"),
                values.get("is_session_pack"),
                id
            );
        }
        return db.one("SELECT * FROM consultation_services WHERE id=?", id);
    }

    public UUID insertOrder(
        Map<String, Object> values) {
        return db.insert("consultation_orders", values);
    }

    public List<Map<String, Object>> orders(
        UUID candidate,
        boolean admin) {
        return db.list(
            "SELECT o.*,p.id AS payment_order_id,p.status AS payment_status "
                + "FROM consultation_orders o JOIN payment_orders p ON p.consultation_order_id=o.id "
                + (admin ? "" : "WHERE o.candidate_user_id=? ") + "ORDER BY o.created_at DESC LIMIT 100",
            admin ? new Object[]{} : new Object[]{candidate}
        );
    }

    public void cancel(
        UUID id,
        UUID candidate) {
        // Same locking order as webhook: payment first, then consultation order.
        Map<String, Object> payment = db.one(
            "SELECT p.* FROM payment_orders p " + "JOIN consultation_orders o ON o.id=p.consultation_order_id "
                + "WHERE o.id=? AND o.candidate_user_id=? FOR UPDATE OF p",
            id,
            candidate
        );
        if (!"PENDING".equals(payment.get("status"))) {
            throw com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException
                .conflict("Only unpaid consultation orders can be cancelled");
        }
        db.update("UPDATE payment_orders SET status='CANCELLED' WHERE id=?", payment.get("id"));
        db.update("UPDATE consultation_orders SET status='CANCELLED' WHERE id=?", id);
    }
}
