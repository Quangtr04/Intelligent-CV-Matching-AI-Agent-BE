package com.example.intelligent_cv_matching_ai_agent.repository.shared.billing;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by BillingService. */
@Repository
public class BillingRepository {

    private final SqlStore db;

    public BillingRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public Map<String, Object> getSubscriptionTiersById(
        Object... args) {
        return db.one("SELECT * FROM subscription_tiers WHERE id=? AND is_active=true", args);
    }

    public UUID insertPaymentOrders(
        Map<String, Object> fields) {
        return db.insert("payment_orders", fields);
    }

    public Map<String, Object> getPaymentOrdersById(
        Object... args) {
        return db.one("SELECT * FROM payment_orders WHERE id=?", args);
    }

    public Map<String, Object> getPaymentOrdersByIdForUpdate(
        Object... args) {
        return db.one("SELECT * FROM payment_orders WHERE id=? FOR UPDATE", args);
    }

    public int updatePaymentWebhookEvents(
        Object... args) {
        return db.update(
            ("INSERT INTO payment_webhook_events(provider,event_id,payment_order_id,"
                + "received_at) VALUES (?,?,?,now()) ON CONFLICT(provider,event_id) DO " + "NOTHING"),
            args
        );
    }

    public Map<String, Object> getPaymentWebhookEventsByProvider(
        Object... args) {
        return db.one("SELECT payment_order_id FROM payment_webhook_events WHERE provider=? AND event_id=?", args);
    }

    public int updatePaymentOrdersById(
        Object... args) {
        return db.update("UPDATE payment_orders SET status=? WHERE id=?", args);
    }

    public Map<String, Object> getUsersByIdForUpdate(
        Object... args) {
        return db.one("SELECT id FROM users WHERE id=? FOR UPDATE", args);
    }

    public int updateUserSubscriptionsByUserId(
        Object... args) {
        return db.update(
            ("UPDATE user_subscriptions SET status='CANCELLED',cancelled_at=now(),"
                + "updated_at=now() WHERE user_id=? AND status='ACTIVE'"),
            args
        );
    }

    public UUID insertUserSubscriptions(
        Map<String, Object> fields) {
        return db.insert("user_subscriptions", fields);
    }

    public int updatePaymentOrdersById2(
        Object... args) {
        return db.update(
            ("UPDATE payment_orders SET status='COMPLETED',completed_at=now()," + "subscription_id=? WHERE id=?"),
            args
        );
    }

    public int updatePaymentOrdersById3(
        Object... args) {
        return db.update("UPDATE payment_orders SET status='COMPLETED',completed_at=now() WHERE id=?", args);
    }

    public void creditTokenWallet(
        Map<String, Object> order) {
        db.update(
            "INSERT INTO user_token_wallets(user_id,balance) VALUES(?,0) ON CONFLICT(user_id) DO NOTHING",
            order.get("user_id")
        );
        int inserted = db.update(
            "INSERT INTO token_wallet_ledger(user_id,payment_order_id,entry_type,amount,balance_after) "
                + "SELECT ?,?, 'PURCHASE', st.token_amount, w.balance + st.token_amount "
                + "FROM subscription_tiers st JOIN user_token_wallets w ON w.user_id=? "
                + "WHERE st.id=? ON CONFLICT(payment_order_id) DO NOTHING",
            order.get("user_id"),
            order.get("id"),
            order.get("user_id"),
            order.get("tier_id")
        );
        if (inserted > 0) {
            db.update(
                "UPDATE user_token_wallets SET balance=balance+(SELECT token_amount FROM subscription_tiers WHERE id=?),updated_at=now() WHERE user_id=?",
                order.get("tier_id"),
                order.get("user_id")
            );
        }
    }

    public UUID insertInvoices(
        Map<String, Object> fields) {
        return db.insert("invoices", fields);
    }

    public UUID insertNotifications(
        Map<String, Object> fields) {
        return db.insert("notifications", fields);
    }

    public void activateConsultation(
        Map<String, Object> payment) {
        Map<String, Object> order = db.one(
            "SELECT * FROM consultation_orders WHERE id=? AND status='PENDING_PAYMENT' FOR UPDATE",
            payment.get("consultation_order_id")
        );
        if (Boolean.TRUE.equals(order.get("is_session_pack"))) {
            db.update(
                "UPDATE consultation_orders SET status='PAID',paid_at=now(),"
                    + "sessions_remaining=sessions_total WHERE id=?",
                order.get("id")
            );
            db.update("UPDATE payment_orders SET status='COMPLETED',completed_at=now() WHERE id=?", payment.get("id"));
            db.insert(
                "notifications",
                com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields(
                    "user_id",
                    order.get("candidate_user_id"),
                    "type",
                    "CONSULTATION_CREDITS_READY",
                    "title",
                    "Gói lượt tư vấn đã sẵn sàng",
                    "content",
                    "Bạn đã có " + order.get("sessions_total") + " lượt tư vấn. Hãy chọn cố vấn và lịch hẹn.",
                    "reference_type",
                    "CONSULTATION_ORDER",
                    "reference_id",
                    order.get("id")
                )
            );
            return;
        }
        UUID thread = db.insert(
            "consultation_threads",
            com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields(
                "candidate_user_id",
                order.get("candidate_user_id"),
                "subject",
                order.get("subject"),
                "request_type",
                order.get("service_code"),
                "request_description",
                order.get("description"),
                "priority",
                "NORMAL"
            )
        );
        db.update(
            "UPDATE consultation_orders SET status='PAID',paid_at=now(),thread_id=? WHERE id=?",
            thread,
            order.get("id")
        );
        db.update("UPDATE payment_orders SET status='COMPLETED',completed_at=now() WHERE id=?", payment.get("id"));
        db.update(
            "INSERT INTO notifications(user_id,type,title,content,reference_type,reference_id) "
                + "SELECT id,'CONSULTATION_REQUEST','Paid consultation request',?,'CONSULTATION',? "
                + "FROM users WHERE role IN ('ADMIN','MANAGER') AND status='ACTIVE'",
            order.get("subject"),
            thread
        );
    }

    public void cancelConsultation(
        Map<String, Object> payment) {
        db.update(
            "UPDATE consultation_orders SET status='CANCELLED' WHERE id=? AND status='PENDING_PAYMENT'",
            payment.get("consultation_order_id")
        );
    }

}
