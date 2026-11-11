package com.example.intelligent_cv_matching_ai_agent.service.admin.management;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.management.ManagerOperationsRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

@Service
public class ManagerOperationsService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ManagerOperationsRepository db;

    private final AdminService admin;

    private final CurrentUser user;

    public ManagerOperationsService(
        ManagerOperationsRepository db,
        AdminService admin,
        CurrentUser user) {
        this.db = db;
        this.admin = admin;
        this.user = user;
    }

    private void requireOperator() {
        user.id();
        if (!user.isStrictAdmin() && !user.hasRole("MANAGER")) {
            throw ApiException.forbidden("An administrator or manager account is required");
        }
    }

    public Map<String, Object> dashboard() {
        requireOperator();
        Map<String, Object> result = new LinkedHashMap<>(admin.dashboard());
        result.put("service_metrics", db.serviceMetrics());
        return result;
    }

    public Map<String, Object> analytics(
        LocalDate from,
        LocalDate to) {
        requireOperator();
        LocalDate[] range = range(from, to);
        var start = range[0].atStartOfDay(ZONE).toOffsetDateTime();
        var until = range[1].plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
        return Map.of(
            "from",
            range[0],
            "to",
            range[1],
            "time_zone",
            ZONE.getId(),
            "usage_by_day",
            db.dailyUsage(start, until),
            "revenue_by_day",
            db.dailyRevenue(start, until),
            "consultations",
            db.consultationSummary(start, until)
        );
    }

    public List<Map<String, Object>> subscriptions(
        String status,
        String search,
        int limit,
        int offset) {
        requireOperator();
        String state = state(status, Set.of("ACTIVE", "EXPIRED", "CANCELLED"));
        return db.subscriptions(state, search(search), pageSize(limit), offset(offset));
    }

    public Map<String, Object> subscription(
        UUID id) {
        requireOperator();
        Map<String, Object> result = new LinkedHashMap<>(db.subscription(id));
        result.put("quotas", db.quotas(result.get("tier_id")));
        return result;
    }

    public List<Map<String, Object>> payments(
        String status,
        String search,
        int limit,
        int offset) {
        requireOperator();
        String state = state(status, Set.of("PENDING", "COMPLETED", "FAILED", "CANCELLED", "REFUNDED"));
        return db.payments(state, search(search), pageSize(limit), offset(offset));
    }

    public Map<String, Object> payment(
        UUID id) {
        requireOperator();
        Map<String, Object> result = new LinkedHashMap<>(db.payment(id));
        result.put("invoice", db.paymentInvoice(id).orElse(null));
        return result;
    }

    public List<Map<String, Object>> invoices(
        String status,
        String search,
        int limit,
        int offset) {
        requireOperator();
        return db.invoices(
            state(status, Set.of("DRAFT", "ISSUED", "PAID", "VOID", "CANCELLED", "REFUNDED")),
            search(search),
            pageSize(limit),
            offset(offset)
        );
    }

    public Map<String, Object> invoice(
        UUID id) {
        requireOperator();
        return db.invoice(id);
    }

    public List<Map<String, Object>> usage(
        LocalDate from,
        LocalDate to,
        String feature,
        int limit,
        int offset) {
        requireOperator();
        LocalDate[] range = range(from, to);
        String code = feature == null || feature.isBlank() ? null : feature.strip().toUpperCase(Locale.ROOT);
        if (code != null && !code.matches("[A-Z][A-Z0-9_]{0,99}")) {
            throw ApiException.badRequest("Enter a valid feature code");
        }
        return db.usage(
            range[0].atStartOfDay(ZONE).toOffsetDateTime(),
            range[1].plusDays(1).atStartOfDay(ZONE).toOffsetDateTime(),
            code,
            pageSize(limit),
            offset(offset)
        );
    }

    public List<Map<String, Object>> consultants(
        String search,
        int limit,
        int offset) {
        requireOperator();
        return db.consultants(search(search), pageSize(limit), offset(offset));
    }

    public List<Map<String, Object>> consultantBookings(
        UUID id,
        String status,
        int limit,
        int offset) {
        requireOperator();
        String state = state(status, Set.of("PENDING", "ACCEPTED", "DECLINED", "CANCELLED", "COMPLETED"));
        db.consultant(id);
        return db.consultantBookings(id, state, pageSize(limit), offset(offset));
    }

    public List<Map<String, Object>> crawlReports(
        String source,
        String status,
        int limit,
        int offset) {
        requireOperator();
        String site = state(source, Set.of("ITVIEC", "TOPCV"));
        String result = state(status, Set.of("RUNNING", "COMPLETED", "PARTIAL", "FAILED", "CANCELLED"));
        return db.crawlReports(site, result, pageSize(limit), offset(offset));
    }

    public List<Map<String, Object>> feedback(
        int limit,
        int offset) {
        requireOperator();
        return db.feedback(pageSize(limit), offset(offset));
    }

    private static String search(
        String value) {
        if (value != null && value.length() > 255) {
            throw ApiException.badRequest("Search must contain at most 255 characters");
        }
        return "%" + (value == null ? "" : value.strip()) + "%";
    }

    private static String state(
        String value,
        Set<String> allowed) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw ApiException.badRequest("Invalid status or source filter");
        }
        return normalized;
    }

    private static LocalDate[] range(
        LocalDate from,
        LocalDate to) {
        LocalDate until = to == null ? LocalDate.now(ZONE) : to;
        LocalDate start = from == null ? until.minusDays(29) : from;
        if (start.isAfter(until) || ChronoUnit.DAYS.between(start, until) > 365) {
            throw ApiException.badRequest("Report date range must be ordered and contain at most 366 days");
        }
        return new LocalDate[]{start, until};
    }
}
