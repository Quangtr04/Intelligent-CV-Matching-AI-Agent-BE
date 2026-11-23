package com.example.intelligent_cv_matching_ai_agent.service.admin.management;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.management.StaffOperationsRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.offset;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.pageSize;

@Service
public class StaffOperationsService {

    private final StaffOperationsRepository db;

    private final CurrentUser user;

    public StaffOperationsService(
        StaffOperationsRepository db,
        CurrentUser user) {
        this.db = db;
        this.user = user;
    }

    public Map<String, Object> dashboard() {
        user.requireStaffOrOperationsRole();
        return db.dashboard();
    }

    public List<Map<String, Object>> consultants(
        String search,
        int limit,
        int offset) {
        user.requireStaffOrOperationsRole();
        return db.consultants(search(search), pageSize(limit), offset(offset));
    }

    public List<Map<String, Object>> consultantRatings(
        UUID consultantId,
        Integer rating,
        Boolean includeRemoved,
        int limit,
        int offset) {
        user.requireStaffOrOperationsRole();
        if (rating != null && (rating < 1 || rating > 5)) {
            throw ApiException.badRequest("Rating filter must be between 1 and 5");
        }
        return db.consultantRatings(consultantId, rating, includeRemoved, pageSize(limit), offset(offset));
    }

    public List<Map<String, Object>> feedback(
        String source,
        int limit,
        int offset) {
        user.requireStaffOrOperationsRole();
        String normalizedSource = state(source, Set.of("CONSULTATION", "MATCHING", "ASSISTANT"));
        return db.feedback(normalizedSource, pageSize(limit), offset(offset));
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
}
