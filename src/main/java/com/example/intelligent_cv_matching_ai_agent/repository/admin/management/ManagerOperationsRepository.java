package com.example.intelligent_cv_matching_ai_agent.repository.admin.management;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Operational projections deliberately exclude account credentials, provider payloads and candidate CV data. */
@Repository
public class ManagerOperationsRepository {

    private static final String SUBSCRIPTIONS = "SELECT s.id,s.user_id,s.tier_id,s.status,s.started_at,s.expires_at,"
        + "s.auto_renew,s.cancelled_at,s.created_at,s.updated_at,u.email,u.full_name,t.tier_code,"
        + "t.display_name AS tier_name "
        + "FROM user_subscriptions s JOIN users u ON u.id=s.user_id JOIN subscription_tiers t ON t.id=s.tier_id ";

    private static final String PAYMENTS = "SELECT p.id,p.user_id,p.subscription_id,p.tier_id,p.order_type,"
        + "p.consultation_order_id,p.duration_months,p.amount,p.currency,p.payment_provider,p.status,p.completed_at,"
        + "p.created_at,u.full_name AS payer_name,u.email AS payer_email,t.display_name AS tier_name "
        + "FROM payment_orders p JOIN users u ON u.id=p.user_id LEFT JOIN subscription_tiers t ON t.id=p.tier_id ";

    private static final String INVOICES = "SELECT i.id,i.user_id,i.payment_order_id,i.invoice_number,i.subtotal,"
        + "i.tax_amount,i.total_amount,i.currency,i.status,i.issued_at,i.paid_at,i.invoice_url,"
        + "u.full_name AS payer_name,u.email AS payer_email " + "FROM invoices i JOIN users u ON u.id=i.user_id ";

    private static final String CONSULTANTS = "SELECT u.id AS user_id,u.full_name,u.email,u.status,"
        + "COALESCE(p.bio,'') AS bio,COALESCE(p.target_roles,'[]'::jsonb) AS target_roles,"
        + "COALESCE(b.total_bookings,0) AS total_bookings,COALESCE(b.completed_bookings,0) AS completed_bookings,"
        + "COALESCE(b.cancelled_bookings,0) AS cancelled_bookings,COALESCE(b.declined_bookings,0) AS declined_bookings,"
        + "COALESCE(b.upcoming_bookings,0) AS upcoming_bookings,r.average_rating,"
        + "COALESCE(r.rating_count,0) AS rating_count "
        + "FROM users u LEFT JOIN consultant_profiles p ON p.user_id=u.id LEFT JOIN ("
        + "SELECT consultant_user_id,count(*) AS total_bookings,"
        + "count(*) FILTER(WHERE status='COMPLETED') AS completed_bookings,"
        + "count(*) FILTER(WHERE status='CANCELLED') AS cancelled_bookings,"
        + "count(*) FILTER(WHERE status='DECLINED') AS declined_bookings,"
        + "count(*) FILTER(WHERE status IN ('PENDING','ACCEPTED') AND starts_at>now()) AS upcoming_bookings "
        + "FROM consultation_bookings GROUP BY consultant_user_id) b ON b.consultant_user_id=u.id LEFT JOIN ("
        + "SELECT consultant_user_id,round(avg(rating),2) AS average_rating,count(*) AS rating_count "
        + "FROM consultation_ratings WHERE NOT is_removed GROUP BY consultant_user_id) r ON r.consultant_user_id=u.id ";

    private final SqlStore db;

    public ManagerOperationsRepository(
        SqlStore db) {
        this.db = db;
    }

    public Map<String, Object> serviceMetrics() {
        return db.one("""
            SELECT (SELECT count(*) FROM users
                     WHERE role='CAREER_CONSULTANT' AND status='ACTIVE') AS active_consultants,
                   (SELECT count(*) FROM consultation_bookings WHERE status='PENDING') AS pending_bookings,
                   (SELECT count(*) FROM consultation_bookings
                     WHERE status='ACCEPTED' AND ends_at>now()) AS accepted_bookings,
                   (SELECT count(*) FROM consultation_bookings WHERE status='COMPLETED') AS completed_bookings,
                   (SELECT round(avg(rating),2) FROM consultation_ratings WHERE NOT is_removed) AS average_consultation_rating,
                   (SELECT count(*) FROM payment_reports WHERE status<>'RESOLVED') AS open_payment_reports
            """);
    }

    public List<Map<String, Object>> subscriptions(
        String status,
        String search,
        int limit,
        int offset) {
        return db.list(
            SUBSCRIPTIONS + "WHERE (?::varchar IS NULL OR s.status=?) "
                + "AND (u.email ILIKE ? OR u.full_name ILIKE ? OR t.display_name ILIKE ?) "
                + "ORDER BY s.created_at DESC,s.id LIMIT ? OFFSET ?",
            status,
            status,
            search,
            search,
            search,
            limit,
            offset
        );
    }

    public Map<String, Object> subscription(
        UUID id) {
        return db.one(SUBSCRIPTIONS + "WHERE s.id=?", id);
    }

    public List<Map<String, Object>> quotas(
        Object tierId) {
        return db.list(
            "SELECT feature_code,quota_value,quota_period FROM subscription_quotas "
                + "WHERE tier_id=? ORDER BY feature_code",
            tierId
        );
    }

    public List<Map<String, Object>> payments(
        String status,
        String search,
        int limit,
        int offset) {
        return db.list(
            PAYMENTS + "WHERE (?::varchar IS NULL OR p.status=?) AND (u.email ILIKE ? OR u.full_name ILIKE ?) "
                + "ORDER BY p.created_at DESC,p.id LIMIT ? OFFSET ?",
            status,
            status,
            search,
            search,
            limit,
            offset
        );
    }

    public Map<String, Object> payment(
        UUID id) {
        return db.one(PAYMENTS + "WHERE p.id=?", id);
    }

    public Optional<Map<String, Object>> paymentInvoice(
        UUID id) {
        return db.optional(INVOICES + "WHERE i.payment_order_id=? ORDER BY i.issued_at DESC,i.id LIMIT 1", id);
    }

    public List<Map<String, Object>> invoices(
        String status,
        String search,
        int limit,
        int offset) {
        return db.list(
            INVOICES + "WHERE (?::varchar IS NULL OR i.status=?) "
                + "AND (i.invoice_number ILIKE ? OR u.email ILIKE ? OR u.full_name ILIKE ?) "
                + "ORDER BY i.issued_at DESC,i.id LIMIT ? OFFSET ?",
            status,
            status,
            search,
            search,
            search,
            limit,
            offset
        );
    }

    public Map<String, Object> invoice(
        UUID id) {
        return db.one(INVOICES + "WHERE i.id=?", id);
    }

    public List<Map<String, Object>> usage(
        OffsetDateTime from,
        OffsetDateTime until,
        String feature,
        int limit,
        int offset) {
        return db.list(
            "SELECT a.user_id,u.email,u.full_name,a.feature_code,count(*) AS requests,"
                + "COALESCE(sum(a.total_tokens),0) AS total_tokens,COALESCE(sum(a.estimated_cost),0) AS estimated_cost,"
                + "count(*) FILTER(WHERE a.success) AS successful_requests,"
                + "count(*) FILTER(WHERE NOT a.success) AS failed_requests,"
                + "round(avg(a.latency_ms),2) AS average_latency_ms "
                + "FROM ai_usage_logs a LEFT JOIN users u ON u.id=a.user_id WHERE a.created_at>=? AND a.created_at<? "
                + "AND (?::varchar IS NULL OR a.feature_code=?) GROUP BY a.user_id,u.email,u.full_name,a.feature_code "
                + "ORDER BY total_tokens DESC,a.user_id NULLS LAST,a.feature_code LIMIT ? OFFSET ?",
            from,
            until,
            feature,
            feature,
            limit,
            offset
        );
    }

    public List<Map<String, Object>> dailyUsage(
        OffsetDateTime from,
        OffsetDateTime until) {
        return db.list(
            "SELECT (created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS day,count(*) AS requests,"
                + "COALESCE(sum(total_tokens),0) AS total_tokens,COALESCE(sum(estimated_cost),0) AS estimated_cost "
                + "FROM ai_usage_logs WHERE created_at>=? AND created_at<? GROUP BY day ORDER BY day",
            from,
            until
        );
    }

    public List<Map<String, Object>> dailyRevenue(
        OffsetDateTime from,
        OffsetDateTime until) {
        return db.list(
            "SELECT (completed_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS day,currency,"
                + "COALESCE(sum(amount),0) AS amount,count(*) AS orders FROM payment_orders "
                + "WHERE status='COMPLETED' AND completed_at>=? AND completed_at<? "
                + "GROUP BY day,currency ORDER BY day,currency",
            from,
            until
        );
    }

    public Map<String, Object> consultationSummary(
        OffsetDateTime from,
        OffsetDateTime until) {
        return db.one(
            "SELECT count(*) AS bookings,count(*) FILTER(WHERE b.status='COMPLETED') AS completed,"
                + "count(*) FILTER(WHERE b.status='CANCELLED') AS cancelled,"
                + "count(*) FILTER(WHERE b.status='DECLINED') AS declined,round(avg(r.rating),2) AS average_rating "
                + "FROM consultation_bookings b LEFT JOIN consultation_ratings r ON r.booking_id=b.id AND NOT r.is_removed "
                + "WHERE b.starts_at>=? AND b.starts_at<?",
            from,
            until
        );
    }

    public List<Map<String, Object>> consultants(
        String search,
        int limit,
        int offset) {
        return db.list(
            CONSULTANTS + "WHERE u.role='CAREER_CONSULTANT' AND u.status<>'DELETED' "
                + "AND (u.email ILIKE ? OR u.full_name ILIKE ?) ORDER BY u.full_name,u.id LIMIT ? OFFSET ?",
            search,
            search,
            limit,
            offset
        );
    }

    public Map<String, Object> consultant(
        UUID id) {
        return db.one("SELECT id FROM users WHERE id=? AND role='CAREER_CONSULTANT' AND status<>'DELETED'", id);
    }

    public List<Map<String, Object>> consultantBookings(
        UUID id,
        String status,
        int limit,
        int offset) {
        return db.list(
            "SELECT b.id,b.candidate_user_id,b.consultant_user_id,b.target_role,b.subject,b.starts_at,"
                + "b.ends_at,b.duration_minutes,b.status,b.credit_refunded,b.created_at,b.accepted_at,b.completed_at,"
                + "b.meeting_status,b.meeting_opened_at,b.meeting_ended_at,c.full_name AS candidate_name,"
                + "u.full_name AS consultant_name,r.rating,r.comment AS rating_comment,"
                + "r.created_at AS rating_created_at "
                + "FROM consultation_bookings b JOIN users c ON c.id=b.candidate_user_id "
                + "JOIN users u ON u.id=b.consultant_user_id LEFT JOIN consultation_ratings r ON r.booking_id=b.id AND NOT r.is_removed "
                + "WHERE b.consultant_user_id=? AND (?::varchar IS NULL OR b.status=?) "
                + "ORDER BY b.starts_at DESC,b.id LIMIT ? OFFSET ?",
            id,
            status,
            status,
            limit,
            offset
        );
    }

    public List<Map<String, Object>> crawlReports(
        String source,
        String status,
        int limit,
        int offset) {
        return db.list(
            "SELECT id,source_site,keyword,role_category,requested_location,requested_limit,status,"
                + "fetched_urls,parsed_jobs,imported_jobs,imported_skills,skipped_duplicates,search_pages_checked,"
                + "candidate_urls_discovered,detail_pages_checked,wrong_region_pages,rejected_pages,"
                + "started_at,finished_at " + "FROM market_crawl_runs WHERE (?::varchar IS NULL OR source_site=?) "
                + "AND (?::varchar IS NULL OR status=?) ORDER BY started_at DESC,id LIMIT ? OFFSET ?",
            source,
            source,
            status,
            status,
            limit,
            offset
        );
    }

    public List<Map<String, Object>> feedback(
        int limit,
        int offset) {
        return db.list(
            "SELECT f.id,f.user_id,u.full_name,u.email,f.source,f.rating,f.feedback_text,f.created_at FROM ("
                + "SELECT id,user_id,'MATCHING' AS source,rating,comment AS feedback_text,created_at "
                + "FROM job_match_feedback "
                + "UNION ALL SELECT id,user_id,'ASSISTANT' AS source,rating,feedback_text,created_at "
                + "FROM career_assistant_feedback UNION ALL SELECT id,candidate_user_id,'CONSULTATION' AS source,"
                + "rating,comment,created_at FROM consultation_ratings WHERE NOT is_removed) f LEFT JOIN users u ON u.id=f.user_id "
                + "ORDER BY f.created_at DESC,f.id LIMIT ? OFFSET ?",
            limit,
            offset
        );
    }
}
