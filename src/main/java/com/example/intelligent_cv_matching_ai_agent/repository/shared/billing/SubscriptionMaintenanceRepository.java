package com.example.intelligent_cv_matching_ai_agent.repository.shared.billing;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by SubscriptionMaintenance. */
@Repository
public class SubscriptionMaintenanceRepository {

    private final SqlStore db;

    public SubscriptionMaintenanceRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public List<Map<String, Object>> listUserSubscriptionsByStatusForUpdate(
        Object... args) {
        return db.list(
            ("WITH due AS (SELECT id FROM user_subscriptions WHERE status='ACTIVE' "
                + "AND expires_at<=now() ORDER BY expires_at LIMIT ? FOR UPDATE SKIP "
                + "LOCKED) UPDATE user_subscriptions us SET status='EXPIRED',"
                + "auto_renew=false,updated_at=now() FROM due WHERE us.id=due.id " + "RETURNING us.id,us.user_id"),
            args
        );
    }

    public List<Map<String, Object>> listUserSubscriptionsByStatusForUpdate2(
        Object... args) {
        return db.list(
            ("SELECT id,user_id FROM user_subscriptions WHERE status='ACTIVE' AND "
                + "expires_at>now()+interval '1 day' AND expires_at<=now()+interval '7 "
                + "days' AND NOT EXISTS (SELECT 1 FROM " + "subscription_renewal_notifications n WHERE "
                + "n.subscription_id=user_subscriptions.id AND "
                + "n.notification_type='RENEWAL_7D') ORDER BY expires_at LIMIT ? FOR " + "UPDATE SKIP LOCKED"),
            args
        );
    }

    public List<Map<String, Object>> listUserSubscriptionsByStatusForUpdate3(
        Object... args) {
        return db.list(
            ("SELECT id,user_id FROM user_subscriptions WHERE status='ACTIVE' AND "
                + "expires_at>now() AND expires_at<=now()+interval '1 day' AND NOT "
                + "EXISTS (SELECT 1 FROM subscription_renewal_notifications n WHERE "
                + "n.subscription_id=user_subscriptions.id AND "
                + "n.notification_type='RENEWAL_1D') ORDER BY expires_at LIMIT ? FOR " + "UPDATE SKIP LOCKED"),
            args
        );
    }

    public int updateSubscriptionRenewalNotifications(
        Object... args) {
        return db.update(
            ("INSERT INTO subscription_renewal_notifications(user_id,"
                + "subscription_id,notification_type,sent_at) VALUES (?,?,?,now()) ON "
                + "CONFLICT(subscription_id,notification_type) DO NOTHING"),
            args
        );
    }

    public UUID insertNotifications(
        Map<String, Object> fields) {
        return db.insert("notifications", fields);
    }

}
