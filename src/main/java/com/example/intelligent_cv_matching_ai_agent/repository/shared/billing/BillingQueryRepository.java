package com.example.intelligent_cv_matching_ai_agent.repository.shared.billing;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by BillingQueryService. */
@Repository
public class BillingQueryRepository {

    private final SqlStore db;

    public BillingQueryRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public List<Map<String, Object>> listSubscriptionQuotasByTierId(
        Object... args) {
        return db.list(
            ("SELECT st.*,COALESCE((SELECT " + "jsonb_agg(jsonb_build_object('featureCode',sq.feature_code,"
                + "'quotaValue',sq.quota_value,'quotaPeriod',sq.quota_period)) FROM "
                + "subscription_quotas sq WHERE sq.tier_id=st.id),'[]'::jsonb) AS quotas "
                + "FROM subscription_tiers st WHERE st.is_active ORDER BY " + "st.price_monthly"),
            args
        );
    }

    public List<Map<String, Object>> listUserSubscriptionsByUserId(
        Object... args) {
        return db.list(
            ("SELECT us.*,st.display_name FROM user_subscriptions us JOIN "
                + "subscription_tiers st ON st.id=us.tier_id WHERE us.user_id=? ORDER BY "
                + "us.created_at DESC LIMIT 100"),
            args
        );
    }

    public Map<String, Object> getUserSubscriptionsById(
        Object... args) {
        return db.one("SELECT id FROM user_subscriptions WHERE id=? AND user_id=?", args);
    }

    public int updateUserSubscriptionsById(
        Object... args) {
        return db.update(
            ("UPDATE user_subscriptions SET status='CANCELLED',auto_renew=false,"
                + "cancelled_at=now(),updated_at=now() WHERE id=? AND user_id=?"),
            args
        );
    }

    public List<Map<String, Object>> listPaymentOrdersByUserId(
        Object... args) {
        return db.list("SELECT * FROM payment_orders WHERE user_id=? ORDER BY created_at DESC LIMIT 100", args);
    }

    public Map<String, Object> getPaymentOrdersById(
        Object... args) {
        return db.one("SELECT * FROM payment_orders WHERE id=? AND user_id=?", args);
    }

    public UUID insertPaymentReports(
        Map<String, Object> fields) {
        return db.insert("payment_reports", fields);
    }

    public Map<String, Object> getPaymentReportsById(
        Object... args) {
        return db.one("SELECT * FROM payment_reports WHERE id=?", args);
    }

    public List<Map<String, Object>> listPaymentReportsByUserId(
        Object... args) {
        return db.list("SELECT * FROM payment_reports WHERE user_id=? ORDER BY created_at DESC LIMIT 100", args);
    }

    public List<Map<String, Object>> listInvoicesByUserId(
        Object... args) {
        return db.list("SELECT * FROM invoices WHERE user_id=? ORDER BY issued_at DESC LIMIT 100", args);
    }

    public Map<String, Object> getInvoicesById(
        Object... args) {
        return db.one("SELECT * FROM invoices WHERE id=? AND user_id=?", args);
    }

}
