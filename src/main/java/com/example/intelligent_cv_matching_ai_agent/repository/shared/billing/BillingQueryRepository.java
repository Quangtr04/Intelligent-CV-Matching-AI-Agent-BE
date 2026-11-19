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
                + "FROM subscription_tiers st WHERE st.is_active AND st.tier_type='SUBSCRIPTION' ORDER BY "
                + "st.price_monthly"),
            args
        );
    }

    public List<Map<String, Object>> listActiveTokenPacks() {
        return db.list(
            "SELECT id,tier_code,display_name,price_monthly AS price, currency,token_amount "
                + "FROM subscription_tiers WHERE is_active AND tier_type='TOKEN_TOPUP' ORDER BY price_monthly,token_amount"
        );
    }

    public long walletBalance(
        UUID user) {
        Object value = db.scalar("SELECT COALESCE((SELECT balance FROM user_token_wallets WHERE user_id=?),0)", user);
        return ((Number) value).longValue();
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
        return db.list(
            "SELECT p.*,c.service_name,t.display_name AS tier_display_name,t.token_amount FROM payment_orders p "
                + "LEFT JOIN consultation_orders c ON c.id=p.consultation_order_id "
                + "LEFT JOIN subscription_tiers t ON t.id=p.tier_id WHERE p.user_id=? ORDER BY p.created_at DESC LIMIT 100",
            args
        );
    }

    public Map<String, Object> getPaymentOrdersById(
        Object... args) {
        return db.one(
            "SELECT p.*,c.service_name,t.display_name AS tier_display_name,t.token_amount FROM payment_orders p "
                + "LEFT JOIN consultation_orders c ON c.id=p.consultation_order_id "
                + "LEFT JOIN subscription_tiers t ON t.id=p.tier_id WHERE p.id=? AND p.user_id=?",
            args
        );
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
