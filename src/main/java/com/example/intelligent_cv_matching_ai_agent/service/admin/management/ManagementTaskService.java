package com.example.intelligent_cv_matching_ai_agent.service.admin.management;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.management.ManagementProfileRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.management.ManagementTaskRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.management.ManagementTaskStatusRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.management.ManagementTaskRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.admin.monitoring.AuditService;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

/**
 * Business logic for MANAGEMENT role hierarchy (MANAGER / STAFF), granular permissions,
 * task assignments, and staff data-entry / job-sourcing tracking.
 */
@Service
public class ManagementTaskService {

    private static final List<String> DEFAULT_MANAGER_PERMISSIONS =
        List.of("JOB_ENTRY", "JOB_SOURCING", "JOB_APPROVAL", "TASK_ASSIGNMENT", "STAFF_MANAGEMENT");

    private static final List<String> DEFAULT_STAFF_PERMISSIONS = List.of("JOB_ENTRY", "JOB_SOURCING");

    private final ManagementTaskRepository db;

    private final CurrentUser user;

    private final AuditService audit;

    public ManagementTaskService(
        ManagementTaskRepository db,
        CurrentUser user,
        AuditService audit) {
        this.db = db;
        this.user = user;
        this.audit = audit;
    }

    private void requireAdminOrManagement() {
        user.requireRole("ADMIN");
    }

    private void requireManagerOrAdmin() {
        requireAdminOrManagement();
        Map<String, Object> actor = db.findActorRoleAndSubRole(user.id())
            .orElseThrow(() -> ApiException.forbidden("Active administrator or manager account required"));
        if ("MANAGEMENT".equals(actor.get("role")) && "STAFF".equals(actor.get("sub_role")))
            throw ApiException.forbidden("Only ADMIN or MANAGEMENT (MANAGER) can perform this operation");
    }

    public Map<String, Object> overview() {
        requireAdminOrManagement();
        Map<String, Object> result = new LinkedHashMap<>(db.summaryMetrics());
        result.put("staff_members", db.listStaffMembers(null));
        return result;
    }

    public Map<String, Object> myProfile() {
        requireAdminOrManagement();
        Map<String, Object> result = new LinkedHashMap<>(db.getStaffMember(user.id()));
        result.put("assigned_tasks", db.listTasks(user.id(), null, null, null, 20, 0));
        return result;
    }

    public List<Map<String, Object>> staffMembers(
        String subRole) {
        requireAdminOrManagement();
        String normalized = subRole == null || subRole.isBlank() ? null : subRole.strip().toUpperCase(Locale.ROOT);
        if (normalized != null && !Set.of("ADMIN", "MANAGER", "STAFF").contains(normalized))
            throw ApiException.badRequest("Invalid subRole filter; allowed: ADMIN, MANAGER, STAFF");
        return db.listStaffMembers(normalized);
    }

    public Map<String, Object> staffMember(
        UUID userId) {
        requireAdminOrManagement();
        return db.getStaffMember(userId);
    }

    @Transactional
    public Map<String, Object> updateProfile(
        UUID userId,
        ManagementProfileRequest body) {
        requireManagerOrAdmin();
        Map<String, Object> before = db.getStaffMember(userId);
        if (!"MANAGEMENT".equals(before.get("role")) && !"ADMIN".equals(before.get("role")))
            throw ApiException.badRequest("Management profile can only be configured for MANAGEMENT or ADMIN users");

        if (body.managerUserId() != null) {
            if (body.managerUserId().equals(userId))
                throw ApiException.badRequest("A user cannot be their own manager");
            Map<String, Object> manager = db.requireAssignableUser(body.managerUserId());
            if ("STAFF".equals(manager.get("sub_role")) && !"ADMIN".equals(manager.get("role")))
                throw ApiException.badRequest("managerUserId must reference an ADMIN or MANAGER");
        }

        List<String> permissions = body.permissions() == null || body.permissions().isEmpty()
            ? ("STAFF".equals(body.subRole()) ? DEFAULT_STAFF_PERMISSIONS : DEFAULT_MANAGER_PERMISSIONS)
            : body.permissions().stream().distinct().toList();

        db.upsertManagementProfile(
            userId,
            body.subRole(),
            db.json(permissions),
            body.managerUserId(),
            body.department() == null || body.department().isBlank() ? null : body.department().strip(),
            body.notes() == null || body.notes().isBlank() ? null : body.notes().strip(),
            user.id()
        );

        Map<String, Object> after = db.getStaffMember(userId);
        audit.record("UPDATE_MANAGEMENT_PROFILE", "MANAGEMENT_PROFILE", userId, before, after);
        return after;
    }

    public List<Map<String, Object>> listTasks(
        UUID assignedTo,
        UUID assignedBy,
        String status,
        String taskType,
        int limit,
        int offset) {
        requireAdminOrManagement();
        String normalizedStatus = status == null || status.isBlank() ? null : status.strip().toUpperCase(Locale.ROOT);
        if (normalizedStatus != null && !Set.of("TODO", "IN_PROGRESS", "DONE", "CANCELLED").contains(normalizedStatus))
            throw ApiException.badRequest("Invalid task status filter");
        String normalizedType =
            taskType == null || taskType.isBlank() ? null : taskType.strip().toUpperCase(Locale.ROOT);
        if (normalizedType != null
            && !Set.of("JOB_ENTRY", "JOB_SOURCING", "JOB_REVIEW", "GENERAL").contains(normalizedType))
            throw ApiException.badRequest("Invalid task type filter");

        return db.listTasks(assignedTo, assignedBy, normalizedStatus, normalizedType, pageSize(limit), offset(offset));
    }

    public Map<String, Object> getTask(
        UUID id) {
        requireAdminOrManagement();
        Map<String, Object> result = new LinkedHashMap<>(db.getTask(id));
        result.put("jobs", db.listJobsByTaskId(id));
        return result;
    }

    @Transactional
    public Map<String, Object> createTask(
        ManagementTaskRequest body) {
        requireManagerOrAdmin();
        db.requireAssignableUser(body.assignedToUserId());

        OffsetDateTime now = OffsetDateTime.now();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("title", body.title().strip());
        fields.put("description", body.description() == null ? null : body.description().strip());
        fields.put("task_type", body.taskType());
        fields.put("assigned_by_user_id", user.id());
        fields.put("assigned_to_user_id", body.assignedToUserId());
        fields.put("role_category", body.roleCategory() == null ? null : body.roleCategory().strip());
        fields
            .put("source_site", body.sourceSite() == null ? null : body.sourceSite().strip().toUpperCase(Locale.ROOT));
        fields.put("keyword", body.keyword() == null ? null : body.keyword().strip());
        fields.put("target_location", body.targetLocation() == null ? null : body.targetLocation().strip());
        fields.put("target_count", body.targetCount() == null ? 1 : body.targetCount());
        fields.put("priority", body.priority() == null ? "NORMAL" : body.priority());
        fields.put("status", "TODO");
        fields.put("due_at", body.dueAt());
        fields.put("created_at", now);
        fields.put("updated_at", now);

        UUID id = db.insertTask(fields);
        if (!user.id().equals(body.assignedToUserId())) {
            db.insertNotifications(
                body.assignedToUserId(),
                "MANAGEMENT_TASK_ASSIGNED",
                "Bạn được giao nhiệm vụ mới",
                body.title().strip(),
                id
            );
        }

        Map<String, Object> after = getTask(id);
        audit.record("CREATE_MANAGEMENT_TASK", "MANAGEMENT_TASK", id, null, after);
        return after;
    }

    @Transactional
    public Map<String, Object> updateTask(
        UUID id,
        ManagementTaskRequest body) {
        requireManagerOrAdmin();
        Map<String, Object> before = db.getTaskForUpdate(id);
        db.requireAssignableUser(body.assignedToUserId());

        db.updateTask(
            body.title().strip(),
            body.description() == null ? null : body.description().strip(),
            body.taskType(),
            body.assignedToUserId(),
            body.roleCategory() == null ? null : body.roleCategory().strip(),
            body.sourceSite() == null ? null : body.sourceSite().strip().toUpperCase(Locale.ROOT),
            body.keyword() == null ? null : body.keyword().strip(),
            body.targetLocation() == null ? null : body.targetLocation().strip(),
            body.targetCount() == null ? 1 : body.targetCount(),
            body.priority() == null ? "NORMAL" : body.priority(),
            body.dueAt(),
            id
        );

        if (!body.assignedToUserId().equals(before.get("assigned_to_user_id"))
            && !user.id().equals(body.assignedToUserId())) {
            db.insertNotifications(
                body.assignedToUserId(),
                "MANAGEMENT_TASK_ASSIGNED",
                "Bạn được phân công nhiệm vụ",
                body.title().strip(),
                id
            );
        }

        Map<String, Object> after = getTask(id);
        audit.record("UPDATE_MANAGEMENT_TASK", "MANAGEMENT_TASK", id, before, after);
        return after;
    }

    @Transactional
    public Map<String, Object> updateTaskStatus(
        UUID id,
        ManagementTaskStatusRequest body) {
        requireAdminOrManagement();
        Map<String, Object> before = db.getTaskForUpdate(id);
        Map<String, Object> actor =
            db.findActorRoleAndSubRole(user.id()).orElseThrow(() -> ApiException.forbidden("Active account required"));

        boolean isStaff = "MANAGEMENT".equals(actor.get("role")) && "STAFF".equals(actor.get("sub_role"));
        if (isStaff && !user.id().equals(before.get("assigned_to_user_id")))
            throw ApiException.forbidden("Staff can only update the status of tasks assigned to them");
        if (isStaff && "CANCELLED".equals(body.status()))
            throw ApiException.forbidden("Only managers or administrators can cancel a task");

        db.updateTaskStatus(body.status(), id);
        Map<String, Object> after = getTask(id);
        audit.record("UPDATE_MANAGEMENT_TASK_STATUS", "MANAGEMENT_TASK", id, before, after);
        return after;
    }
}
