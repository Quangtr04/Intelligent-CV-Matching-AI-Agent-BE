package com.example.intelligent_cv_matching_ai_agent.repository.admin.management;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by AdminService. */
@Repository
public class AdminRepository {

    private static final String ACCOUNT_FIELDS =
        ("u.id,u.email,u.full_name,u.role,u.status,u.auth_provider,u.preferred_language,"
            + "u.email_verified_at,u.last_login_at,u.created_at,u.updated_at,u.deleted_at,"
            + "COALESCE(mp.sub_role, CASE WHEN u.role='STAFF' THEN 'STAFF' "
            + "WHEN u.role='MANAGEMENT' THEN 'MANAGER' ELSE NULL END) AS management_sub_role,"
            + "mp.permissions AS management_permissions,"
            + "mp.manager_user_id,mp.department AS management_department");

    private static final String ACCOUNT_FROM = " FROM users u LEFT JOIN management_profiles mp ON mp.user_id=u.id ";

    private final SqlStore db;

    public AdminRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public List<Map<String, Object>> searchAccounts(
        String search,
        int limit,
        long offset) {
        String pattern = "%" + search + "%";
        return db.list(
            "SELECT " + ACCOUNT_FIELDS + ACCOUNT_FROM
                + ("WHERE u.email ILIKE ? OR u.full_name ILIKE ? ORDER BY " + "u.created_at DESC LIMIT ? OFFSET ?"),
            pattern,
            pattern,
            limit,
            offset
        );
    }

    public Map<String, Object> getAccount(
        UUID id) {
        return db.one("SELECT " + ACCOUNT_FIELDS + ACCOUNT_FROM + "WHERE u.id=?", id);
    }

    public Object countUsersByStatus(
        Object... args) {
        return db.scalar("SELECT count(*) FROM users WHERE status<>'DELETED'", args);
    }

    public Object countUserSubscriptionsByStatus(
        Object... args) {
        return db.scalar(
            ("SELECT count(*) FROM user_subscriptions WHERE status='ACTIVE' AND "
                + "(expires_at IS NULL OR expires_at>now())"),
            args
        );
    }

    public List<Map<String, Object>> listPaymentOrdersByStatus(
        Object... args) {
        return db.list(
            ("SELECT currency,COALESCE(sum(amount),0) AS amount FROM payment_orders "
                + "WHERE status='COMPLETED' GROUP BY currency"),
            args
        );
    }

    public Map<String, Object> getAiUsageLogsByCreatedAt(
        Object... args) {
        return db.one(
            ("SELECT COALESCE(sum(total_tokens),0) AS total_tokens,"
                + "COALESCE(sum(estimated_cost),0) AS estimated_cost,count(*) AS "
                + "requests FROM ai_usage_logs WHERE created_at>=date_trunc('month'," + "now())"),
            args
        );
    }

    public Object countSupportTicketsByStatus(
        Object... args) {
        return db.scalar("SELECT count(*) FROM support_tickets WHERE status IN ('OPEN','IN_PROGRESS')", args);
    }

    public Optional<Map<String, Object>> findUsersByEmail(
        Object... args) {
        return db.optional("SELECT id FROM users WHERE email=?", args);
    }

    public UUID insertUsers(
        Map<String, Object> fields) {
        return db.insert("users", fields);
    }

    public int updateCandidateProfiles(
        Object... args) {
        return db.update(
            ("INSERT INTO candidate_profiles(user_id,created_at,updated_at) VALUES " + "(?,now(),now())"),
            args
        );
    }

    public boolean isStaffActor(
        UUID actor) {
        return Boolean.TRUE.equals(
            db.scalar(
                "SELECT EXISTS(SELECT 1 FROM users u LEFT JOIN management_profiles mp ON mp.user_id=u.id "
                    + "WHERE u.id=? AND (u.role='STAFF' OR (u.role='MANAGEMENT' AND mp.sub_role='STAFF')))",
                actor
            )
        );
    }

    public int upsertManagementProfile(
        UUID userId,
        String subRole,
        Object permissionsJson,
        UUID updatedBy) {
        return db.update(
            "INSERT INTO management_profiles(user_id,sub_role,permissions,updated_by_user_id,created_at,updated_at) "
                + "VALUES(?,?,?,?,now(),now()) ON CONFLICT(user_id) DO UPDATE SET "
                + "sub_role=COALESCE(EXCLUDED.sub_role,management_profiles.sub_role),"
                + "updated_by_user_id=EXCLUDED.updated_by_user_id,updated_at=now()",
            userId,
            subRole,
            permissionsJson,
            updatedBy
        );
    }

    public Object readDatabaseValue(
        Object... args) {
        return db.scalar("SELECT pg_advisory_xact_lock(hashtext('ACCOUNT_ADMIN_MEMBERSHIP'))", args);
    }

    public int updateUsersById(
        Object... args) {
        return db.update("UPDATE users SET full_name=?,role=?,status=?,updated_at=now() WHERE id=?", args);
    }

    public int updateUsersById2(
        Object... args) {
        return db.update("UPDATE users SET status='DELETED',deleted_at=now(),updated_at=now() WHERE id=?", args);
    }

    public List<Map<String, Object>> listUsersByRoleForUpdate(
        Object... args) {
        return db.list("SELECT id FROM users WHERE role='ADMIN' AND status='ACTIVE' ORDER BY id FOR UPDATE", args);
    }

    public List<Map<String, Object>> listSubscriptionTiers(
        Object... args) {
        return db.list("SELECT * FROM subscription_tiers ORDER BY price_monthly", args);
    }

    public UUID insertSubscriptionTiers(
        Map<String, Object> fields) {
        return db.insert("subscription_tiers", fields);
    }

    public Map<String, Object> getSubscriptionTiersById(
        Object... args) {
        return db.one("SELECT * FROM subscription_tiers WHERE id=?", args);
    }

    public int updateSubscriptionTiersById(
        Object... args) {
        return db.update(
            ("UPDATE subscription_tiers SET tier_code=?,display_name=?,"
                + "price_monthly=?,currency=?,is_active=?,updated_at=now() WHERE id=?"),
            args
        );
    }

    public Map<String, Object> getSubscriptionTiersById2(
        Object... args) {
        return db.one("SELECT id FROM subscription_tiers WHERE id=?", args);
    }

    public List<Map<String, Object>> listSubscriptionQuotasByTierId(
        Object... args) {
        return db.list("SELECT * FROM subscription_quotas WHERE tier_id=?", args);
    }

    public int updateSubscriptionQuotas(
        Object... args) {
        return db.update(
            ("INSERT INTO subscription_quotas(tier_id,feature_code,quota_value,"
                + "quota_period) VALUES (?,?,?,?) ON CONFLICT(tier_id,feature_code) DO "
                + "UPDATE SET quota_value=excluded.quota_value," + "quota_period=excluded.quota_period"),
            args
        );
    }

    public List<Map<String, Object>> listSubscriptionQuotasByTierId2(
        Object... args) {
        return db.list("SELECT * FROM subscription_quotas WHERE tier_id=? ORDER BY feature_code", args);
    }

    public List<Map<String, Object>> listAiConfigurations(
        Object... args) {
        return db.list("SELECT * FROM ai_configurations ORDER BY config_type,version DESC LIMIT 100", args);
    }

    public void lockAiConfigurationType(
        String configType) {
        db.scalar("SELECT pg_advisory_xact_lock(hashtext('AI_CONFIG:' || ?))", configType);
    }

    public int getNextAiConfigurationVersion(
        String configType) {
        Number nextVersion = (Number) db
            .scalar("SELECT COALESCE(max(version),0)+1 FROM ai_configurations WHERE config_type=?", configType);

        return nextVersion.intValue();
    }

    public int updateAiConfigurationsByConfigType(
        Object... args) {
        return db.update("UPDATE ai_configurations SET is_active=false,updated_at=now() WHERE config_type=?", args);
    }

    public UUID insertAiConfigurations(
        Map<String, Object> fields) {
        return db.insert("ai_configurations", fields);
    }

    public Map<String, Object> getAiConfigurationsById(
        Object... args) {
        return db.one("SELECT * FROM ai_configurations WHERE id=?", args);
    }

    public List<Map<String, Object>> listAiPromptTemplates(
        Object... args) {
        return db.list("SELECT * FROM ai_prompt_templates ORDER BY feature_code,version DESC LIMIT 100", args);
    }

    public void lockAiPromptFeatureCode(
        String featureCode) {
        db.scalar("SELECT pg_advisory_xact_lock(hashtext('AI_PROMPT:' || ?))", featureCode);
    }

    public int getNextAiPromptVersion(
        String featureCode) {
        Number nextVersion = (Number) db
            .scalar("SELECT COALESCE(max(version),0)+1 FROM ai_prompt_templates WHERE feature_code=?", featureCode);

        return nextVersion.intValue();
    }

    public int updateAiPromptTemplatesByFeatureCode(
        Object... args) {
        return db.update(
            ("UPDATE ai_prompt_templates SET is_active=false,updated_at=now() WHERE " + "feature_code=?"),
            args
        );
    }

    public UUID insertAiPromptTemplates(
        Map<String, Object> fields) {
        return db.insert("ai_prompt_templates", fields);
    }

    public Map<String, Object> getAiPromptTemplatesById(
        Object... args) {
        return db.one("SELECT * FROM ai_prompt_templates WHERE id=?", args);
    }

    public List<Map<String, Object>> listAiUsageLogs(
        Object... args) {
        return db.list("SELECT * FROM ai_usage_logs ORDER BY created_at DESC LIMIT 100", args);
    }

    public List<Map<String, Object>> listVectorStoreMetrics(
        Object... args) {
        return db.list("SELECT * FROM vector_store_metrics ORDER BY measured_at DESC LIMIT 100", args);
    }

    public List<Map<String, Object>> listVectorIndexingJobs(
        Object... args) {
        return db.list("SELECT * FROM vector_indexing_jobs ORDER BY created_at DESC LIMIT 100", args);
    }

    public List<Map<String, Object>> listSystemLogs(
        Object... args) {
        return db.list("SELECT * FROM system_logs ORDER BY created_at DESC LIMIT 100", args);
    }

    public List<Map<String, Object>> listAuditLogs(
        Object... args) {
        return db.list("SELECT * FROM audit_logs ORDER BY created_at DESC LIMIT 100", args);
    }

    public List<Map<String, Object>> listPaymentOrders(
        Object... args) {
        return db.list("SELECT * FROM payment_orders ORDER BY created_at DESC LIMIT 100", args);
    }

    public List<Map<String, Object>> listMarketCrawlRuns(
        Object... args) {
        return db.list("SELECT * FROM market_crawl_runs ORDER BY started_at DESC LIMIT 100", args);
    }

    public List<Map<String, Object>> listPaymentReports(
        Object... args) {
        return db.list("SELECT * FROM payment_reports ORDER BY created_at DESC LIMIT 100", args);
    }

    public Map<String, Object> getPaymentReportsById(
        Object... args) {
        return db.one("SELECT * FROM payment_reports WHERE id=?", args);
    }

    public int updatePaymentReportsById(
        Object... args) {
        return db.update(
            ("UPDATE payment_reports SET status='RESOLVED',resolution=?,"
                + "assigned_admin_id=?,resolved_at=now() WHERE id=?"),
            args
        );
    }

}
