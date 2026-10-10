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

    public UUID insertInvoices(
        Map<String, Object> fields) {
        return db.insert("invoices", fields);
    }

    public UUID insertNotifications(
        Map<String, Object> fields) {
        return db.insert("notifications", fields);
    }

}
