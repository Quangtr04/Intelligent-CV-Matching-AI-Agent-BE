package com.example.intelligent_cv_matching_ai_agent.repository.admin.management;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** SQL persistence operations for management profiles, permissions, task assignments, and job creator analytics. */
@Repository
public class ManagementTaskRepository {

    private static final String STAFF_MEMBER_SELECT = """
        SELECT u.id AS user_id,
               u.email,
               u.full_name,
               u.role,
               u.status,
               COALESCE(mp.sub_role, CASE WHEN u.role = 'ADMIN' THEN 'ADMIN'
                 WHEN u.role = 'STAFF' THEN 'STAFF' ELSE 'MANAGER' END) AS sub_role,
               COALESCE(mp.permissions, '[]'::jsonb) AS permissions,
               mp.manager_user_id,
               mgr.full_name AS manager_full_name,
               mgr.email AS manager_email,
               mp.department,
               mp.notes,
               mp.updated_by_user_id,
               mp.updated_at AS profile_updated_at,
               (SELECT count(*) FROM market_job_posts j WHERE j.created_by_user_id = u.id) AS total_jobs_created,
               (SELECT count(*) FROM market_job_posts j
                 WHERE j.created_by_user_id = u.id AND j.ingestion_method = 'MANUAL') AS manual_jobs_entered,
               (SELECT count(*) FROM market_job_posts j
                 WHERE j.created_by_user_id = u.id AND j.ingestion_method IN ('CRAWL', 'SCHEDULED_CRAWL'))
                   AS crawled_jobs_sourced,
               (SELECT count(*) FROM market_job_posts j
                 WHERE j.created_by_user_id = u.id AND j.status = 'PENDING') AS pending_jobs_count,
               (SELECT count(*) FROM market_job_posts j
                 WHERE j.created_by_user_id = u.id AND j.status = 'ACTIVE') AS active_jobs_count,
               (SELECT count(*) FROM management_tasks t
                 WHERE t.assigned_to_user_id = u.id AND t.status IN ('TODO', 'IN_PROGRESS')) AS open_tasks_count,
               (SELECT count(*) FROM management_tasks t
                 WHERE t.assigned_to_user_id = u.id AND t.status = 'DONE') AS completed_tasks_count
          FROM users u
          LEFT JOIN management_profiles mp ON mp.user_id = u.id
          LEFT JOIN users mgr ON mgr.id = mp.manager_user_id
        """;

    private static final String TASK_SELECT = """
        SELECT t.*,
               assigner.full_name AS assigned_by_full_name,
               assigner.email AS assigned_by_email,
               assigner.role AS assigned_by_role,
               assignee.full_name AS assigned_to_full_name,
               assignee.email AS assigned_to_email,
               assignee.role AS assigned_to_role,
               COALESCE(mp.sub_role, CASE WHEN assignee.role = 'ADMIN' THEN 'ADMIN'
                 WHEN assignee.role = 'STAFF' THEN 'STAFF' ELSE 'MANAGER' END) AS assigned_to_sub_role,
               (SELECT count(*) FROM market_job_posts j WHERE j.task_id = t.id) AS submitted_jobs_count,
               (SELECT count(*) FROM market_job_posts j
                 WHERE j.task_id = t.id AND j.status = 'ACTIVE') AS approved_jobs_count,
               (SELECT count(*) FROM market_job_posts j
                 WHERE j.task_id = t.id AND j.status = 'PENDING') AS pending_jobs_count,
               (SELECT count(*) FROM market_crawl_runs r WHERE r.task_id = t.id) AS crawl_runs_count
          FROM management_tasks t
          JOIN users assigner ON assigner.id = t.assigned_by_user_id
          JOIN users assignee ON assignee.id = t.assigned_to_user_id
          LEFT JOIN management_profiles mp ON mp.user_id = t.assigned_to_user_id
        """;

    private final SqlStore db;

    public ManagementTaskRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public List<Map<String, Object>> listStaffMembers(
        String subRole) {
        return db.list(
            STAFF_MEMBER_SELECT + " WHERE u.role IN ('ADMIN', 'MANAGEMENT', 'STAFF') AND u.status <> 'DELETED' "
                + "AND (?::varchar IS NULL OR COALESCE(mp.sub_role, "
                + "CASE WHEN u.role = 'ADMIN' THEN 'ADMIN' WHEN u.role = 'STAFF' THEN 'STAFF' ELSE 'MANAGER' END) = ?) "
                + "ORDER BY (u.role = 'ADMIN') DESC, "
                + "COALESCE(mp.sub_role, CASE WHEN u.role = 'STAFF' THEN 'STAFF' ELSE 'MANAGER' END), "
                + "u.full_name, u.id",
            subRole,
            subRole
        );
    }

    public Map<String, Object> getStaffMember(
        UUID userId) {
        return db.one(
            STAFF_MEMBER_SELECT
                + " WHERE u.id = ? AND u.role IN ('ADMIN', 'MANAGEMENT', 'STAFF') AND u.status <> 'DELETED'",
            userId
        );
    }

    public Optional<Map<String, Object>> findActorRoleAndSubRole(
        UUID userId) {
        return db.optional(
            "SELECT u.id, u.role, u.status, "
                + "COALESCE(mp.sub_role, CASE WHEN u.role = 'ADMIN' THEN 'ADMIN' "
                + "WHEN u.role = 'STAFF' THEN 'STAFF' ELSE 'MANAGER' END) AS sub_role, "
                + "COALESCE(mp.permissions, '[]'::jsonb) AS permissions "
                + "FROM users u LEFT JOIN management_profiles mp ON mp.user_id = u.id "
                + "WHERE u.id = ? AND u.status = 'ACTIVE' AND u.deleted_at IS NULL",
            userId
        );
    }

    public boolean actorHasPermission(
        UUID userId,
        String permission) {
        return Boolean.TRUE.equals(
            db.scalar(
                "SELECT EXISTS(SELECT 1 FROM management_profiles mp "
                    + "WHERE mp.user_id = ? AND mp.permissions @> jsonb_build_array(?::text))",
                userId,
                permission
            )
        );
    }

    public Map<String, Object> requireAssignableUser(
        UUID userId) {
        return db.one(
            "SELECT u.id, u.full_name, u.email, u.role, "
                + "COALESCE(mp.sub_role, CASE WHEN u.role = 'ADMIN' THEN 'ADMIN' "
                + "WHEN u.role = 'STAFF' THEN 'STAFF' ELSE 'MANAGER' END) AS sub_role "
                + "FROM users u LEFT JOIN management_profiles mp ON mp.user_id = u.id "
                + "WHERE u.id = ? AND u.role IN ('ADMIN', 'MANAGEMENT', 'STAFF') "
                + "AND u.status = 'ACTIVE' AND u.deleted_at IS NULL",
            userId
        );
    }

    public void upsertManagementProfile(
        UUID userId,
        String subRole,
        Object permissionsJson,
        UUID managerUserId,
        String department,
        String notes,
        UUID updatedBy) {
        db.update(
            "INSERT INTO management_profiles("
                + "user_id, sub_role, permissions, manager_user_id, department, notes, updated_by_user_id, "
                + "created_at, updated_at" + ") VALUES (?, ?, ?, ?, ?, ?, ?, now(), now()) "
                + "ON CONFLICT (user_id) DO UPDATE SET " + "sub_role = EXCLUDED.sub_role, "
                + "permissions = EXCLUDED.permissions, " + "manager_user_id = EXCLUDED.manager_user_id, "
                + "department = EXCLUDED.department, " + "notes = EXCLUDED.notes, "
                + "updated_by_user_id = EXCLUDED.updated_by_user_id, " + "updated_at = now()",
            userId,
            subRole,
            permissionsJson,
            managerUserId,
            department,
            notes,
            updatedBy
        );
    }

    public UUID insertTask(
        Map<String, Object> fields) {
        return db.insert("management_tasks", fields);
    }

    public Map<String, Object> getTask(
        UUID id) {
        return db.one(TASK_SELECT + " WHERE t.id = ?", id);
    }

    public Map<String, Object> getTaskForUpdate(
        UUID id) {
        return db.one("SELECT * FROM management_tasks WHERE id = ? FOR UPDATE", id);
    }

    public List<Map<String, Object>> listTasks(
        UUID assignedTo,
        UUID assignedBy,
        String status,
        String taskType,
        int limit,
        int offset) {
        return db.list(
            TASK_SELECT + " WHERE (?::uuid IS NULL OR t.assigned_to_user_id = ?::uuid) "
                + "AND (?::uuid IS NULL OR t.assigned_by_user_id = ?::uuid) "
                + "AND (?::varchar IS NULL OR t.status = ?) " + "AND (?::varchar IS NULL OR t.task_type = ?) "
                + "ORDER BY t.created_at DESC, t.id LIMIT ? OFFSET ?",
            assignedTo,
            assignedTo,
            assignedBy,
            assignedBy,
            status,
            status,
            taskType,
            taskType,
            limit,
            offset
        );
    }

    public int updateTask(
        String title,
        String description,
        String taskType,
        UUID assignedToUserId,
        String roleCategory,
        String sourceSite,
        String keyword,
        String targetLocation,
        int targetCount,
        String priority,
        java.time.OffsetDateTime dueAt,
        UUID id) {
        return db.update(
            "UPDATE management_tasks SET title=?, description=?, task_type=?, assigned_to_user_id=?, "
                + "role_category=?, source_site=?, keyword=?, target_location=?, target_count=?, "
                + "priority=?, due_at=?, updated_at=now() WHERE id=?",
            title,
            description,
            taskType,
            assignedToUserId,
            roleCategory,
            sourceSite,
            keyword,
            targetLocation,
            targetCount,
            priority,
            dueAt,
            id
        );
    }

    public int updateTaskStatus(
        String status,
        UUID id) {
        return db.update(
            "UPDATE management_tasks SET status=?, "
                + "completed_at = CASE WHEN ? = 'DONE' THEN COALESCE(completed_at, now()) "
                + "WHEN ? IN ('TODO', 'IN_PROGRESS') THEN NULL ELSE completed_at END, "
                + "updated_at = now() WHERE id=?",
            status,
            status,
            status,
            id
        );
    }

    public List<Map<String, Object>> listJobsByTaskId(
        UUID taskId) {
        return db.list(
            "SELECT j.id, j.job_title, j.company_name, j.location, j.role_category, j.source_site, j.source_url, "
                + "j.status, j.ingestion_method, j.collected_at, j.created_by_user_id, "
                + "creator.full_name AS created_by_full_name, creator.email AS created_by_email, "
                + "j.reviewed_by_user_id, reviewer.full_name AS reviewed_by_full_name, j.reviewed_at, j.review_note "
                + "FROM market_job_posts j " + "LEFT JOIN users creator ON creator.id = j.created_by_user_id "
                + "LEFT JOIN users reviewer ON reviewer.id = j.reviewed_by_user_id "
                + "WHERE j.task_id = ? ORDER BY j.collected_at DESC, j.id LIMIT 200",
            taskId
        );
    }

    public Map<String, Object> summaryMetrics() {
        return db.one("""
            SELECT (SELECT count(*) FROM users WHERE role IN ('MANAGEMENT', 'STAFF') AND status = 'ACTIVE')
                     AS active_management_users,
                   (SELECT count(*) FROM users u LEFT JOIN management_profiles mp ON mp.user_id = u.id
                     WHERE u.role = 'MANAGEMENT' AND u.status = 'ACTIVE'
                       AND COALESCE(mp.sub_role, 'MANAGER') = 'MANAGER') AS active_managers,
                   (SELECT count(*) FROM users u LEFT JOIN management_profiles mp ON mp.user_id = u.id
                     WHERE u.status = 'ACTIVE'
                       AND (u.role = 'STAFF' OR (u.role = 'MANAGEMENT' AND mp.sub_role = 'STAFF'))) AS active_staff,
                   (SELECT count(*) FROM management_tasks WHERE status = 'TODO') AS todo_tasks,
                   (SELECT count(*) FROM management_tasks WHERE status = 'IN_PROGRESS') AS in_progress_tasks,
                   (SELECT count(*) FROM management_tasks WHERE status = 'DONE') AS completed_tasks,
                   (SELECT count(*) FROM market_job_posts WHERE status = 'PENDING') AS pending_approval_jobs,
                   (SELECT count(*) FROM market_job_posts WHERE status = 'ACTIVE') AS active_jobs,
                   (SELECT count(*) FROM market_job_posts WHERE ingestion_method = 'MANUAL') AS manual_entered_jobs,
                   (SELECT count(*) FROM market_job_posts
                     WHERE ingestion_method IN ('CRAWL', 'SCHEDULED_CRAWL')) AS crawled_jobs
            """);
    }

    public int insertNotifications(
        UUID userId,
        String type,
        String title,
        String content,
        UUID referenceId) {
        return db.update(
            "INSERT INTO notifications(user_id, type, title, content, reference_type, reference_id, created_at) "
                + "VALUES (?, ?, ?, ?, 'MANAGEMENT_TASK', ?, now())",
            userId,
            type,
            title,
            content,
            referenceId
        );
    }
}
