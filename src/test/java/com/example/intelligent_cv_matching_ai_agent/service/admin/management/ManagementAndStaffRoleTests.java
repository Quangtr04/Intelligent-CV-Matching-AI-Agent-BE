package com.example.intelligent_cv_matching_ai_agent.service.admin.management;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.jobs.ImportJobRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.management.AccountCreateRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.management.ManagementProfileRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.management.ManagementTaskRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.management.ManagementTaskStatusRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.management.AdminRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.management.ManagementTaskRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.jobs.JobRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.admin.monitoring.AuditService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.identity.TokenService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.JobEmbeddingService;
import com.example.intelligent_cv_matching_ai_agent.service.shared.jobs.JobService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagementAndStaffRoleTests {

    @Test
    void staffAccountCreationInitializesStaffProfileAndPermissions() {
        SqlStore store = mock(SqlStore.class);
        AdminRepository db = new AdminRepository(store);
        CurrentUser user = mock(CurrentUser.class);
        AuditService audit = mock(AuditService.class);
        TokenService tokens = mock(TokenService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        UUID managerId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        when(user.id()).thenReturn(managerId);
        when(store.scalar(startsWith("SELECT EXISTS(SELECT 1 FROM users u"), eq(managerId))).thenReturn(false);
        when(store.optional(eq("SELECT id FROM users WHERE email=?"), any())).thenReturn(Optional.empty());
        when(encoder.encode(any())).thenReturn("hashed");
        when(store.insert(eq("users"), anyMap())).thenReturn(staffId);
        when(store.one(startsWith("SELECT u.id,u.email"), eq(staffId)))
            .thenReturn(new LinkedHashMap<>(Map.of("id", staffId, "role", "STAFF", "management_sub_role", "STAFF")));

        AdminService service = new AdminService(db, user, audit, tokens, encoder);
        Map<String, Object> created = service
            .create(new AccountCreateRequest("staff@edunexus.local", "Staff Member", "STAFF", "temporary-pass-123"));

        assertEquals("STAFF", created.get("role"));
        assertEquals("STAFF", created.get("management_sub_role"));
        verify(store)
            .update(startsWith("INSERT INTO management_profiles"), eq(staffId), eq("STAFF"), any(), eq(managerId));
    }

    @Test
    void staffCannotCreateAccountsOrAssignTasksUnlessGrantedPermission() {
        SqlStore store = mock(SqlStore.class);
        ManagementTaskRepository taskDb = new ManagementTaskRepository(store);
        CurrentUser user = mock(CurrentUser.class);
        AuditService audit = mock(AuditService.class);

        UUID staffId = UUID.randomUUID();
        UUID targetStaffId = UUID.randomUUID();
        when(user.id()).thenReturn(staffId);
        when(store.optional(startsWith("SELECT u.id, u.role, u.status"), eq(staffId)))
            .thenReturn(Optional.of(Map.of("id", staffId, "role", "STAFF", "sub_role", "STAFF")));
        when(store.scalar(startsWith("SELECT EXISTS(SELECT 1 FROM management_profiles mp"), eq(staffId), any()))
            .thenReturn(false);

        ManagementTaskService taskService = new ManagementTaskService(taskDb, user, audit);
        ManagementTaskRequest request = new ManagementTaskRequest(
            "Import 10 Java jobs",
            "Collect from TopCV",
            "JOB_ENTRY",
            targetStaffId,
            "Backend",
            "TOPCV",
            "Java",
            "Ha Noi",
            10,
            "HIGH",
            null
        );

        ApiException denied = assertThrows(ApiException.class, () -> taskService.createTask(request));
        assertEquals(403, denied.status());
    }

    @Test
    void staffCanOnlyUpdateStatusOfOwnAssignedTasksAndCannotCancel() {
        SqlStore store = mock(SqlStore.class);
        ManagementTaskRepository taskDb = new ManagementTaskRepository(store);
        CurrentUser user = mock(CurrentUser.class);
        AuditService audit = mock(AuditService.class);

        UUID staffId = UUID.randomUUID();
        UUID otherStaffId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(user.id()).thenReturn(staffId);
        when(store.optional(startsWith("SELECT u.id, u.role, u.status"), eq(staffId)))
            .thenReturn(Optional.of(Map.of("id", staffId, "role", "STAFF", "sub_role", "STAFF")));
        when(store.scalar(startsWith("SELECT EXISTS(SELECT 1 FROM management_profiles mp"), eq(staffId), any()))
            .thenReturn(false);
        when(store.one(eq("SELECT * FROM management_tasks WHERE id = ? FOR UPDATE"), eq(taskId))).thenReturn(
            new LinkedHashMap<>(Map.of("id", taskId, "assigned_to_user_id", otherStaffId, "status", "TODO"))
        );

        ManagementTaskService taskService = new ManagementTaskService(taskDb, user, audit);

        ApiException notAssignee = assertThrows(
            ApiException.class,
            () -> taskService.updateTaskStatus(taskId, new ManagementTaskStatusRequest("IN_PROGRESS"))
        );
        assertEquals(403, notAssignee.status());

        when(store.one(eq("SELECT * FROM management_tasks WHERE id = ? FOR UPDATE"), eq(taskId)))
            .thenReturn(new LinkedHashMap<>(Map.of("id", taskId, "assigned_to_user_id", staffId, "status", "TODO")));

        ApiException cancelDenied = assertThrows(
            ApiException.class,
            () -> taskService.updateTaskStatus(taskId, new ManagementTaskStatusRequest("CANCELLED"))
        );
        assertEquals(403, cancelDenied.status());
    }

    @Test
    void staffJobImportCreatesPendingJobWhileManagerImportCreatesActiveJobAndQueuesEmbedding() {
        SqlStore store = mock(SqlStore.class);
        JobRepository jobDb = new JobRepository(store);
        CurrentUser user = mock(CurrentUser.class);
        AiService ai = mock(AiService.class);
        JobEmbeddingService embeddings = mock(JobEmbeddingService.class);

        UUID staffId = UUID.randomUUID();
        UUID managerId = UUID.randomUUID();
        UUID staffJobId = UUID.randomUUID();
        UUID managerJobId = UUID.randomUUID();

        when(store.insert(eq("market_job_posts"), argThat(m -> "PENDING".equals(m.get("status")))))
            .thenReturn(staffJobId);
        when(store.insert(eq("market_job_posts"), argThat(m -> "ACTIVE".equals(m.get("status")))))
            .thenReturn(managerJobId);
        when(store.one(startsWith("SELECT j.*,j.source_url"), eq(staffJobId)))
            .thenReturn(new LinkedHashMap<>(Map.of("id", staffJobId, "status", "PENDING")));
        when(store.one(startsWith("SELECT j.*,j.source_url"), eq(managerJobId)))
            .thenReturn(new LinkedHashMap<>(Map.of("id", managerJobId, "status", "ACTIVE")));

        JobService service = new JobService(jobDb, user, ai, embeddings);
        ImportJobRequest request = new ImportJobRequest(
            "ITviec",
            "https://itviec.com/it-jobs/java-backend-developer-1",
            "Java Backend Developer",
            "EduNexus",
            "Ha Noi",
            "2000 USD",
            "Backend",
            "Build scalable Java backend microservices with PostgreSQL and Spring Boot.",
            List.of()
        );

        // 1. Staff imports job -> status is PENDING, embedding is NOT queued
        when(user.id()).thenReturn(staffId);
        when(store.scalar(startsWith("SELECT EXISTS(SELECT 1 FROM users u LEFT JOIN management_profiles"), eq(staffId)))
            .thenReturn(true);

        Map<String, Object> staffResult = service.importJob(request);
        assertEquals("PENDING", staffResult.get("status"));
        verify(embeddings, never()).enqueue(eq(staffJobId), anyString(), any());

        // 2. Manager imports job -> status is ACTIVE, embedding IS queued immediately
        when(user.id()).thenReturn(managerId);
        when(
            store.scalar(startsWith("SELECT EXISTS(SELECT 1 FROM users u LEFT JOIN management_profiles"), eq(managerId))
        ).thenReturn(false);

        ImportJobRequest managerRequest = new ImportJobRequest(
            "ITviec",
            "https://itviec.com/it-jobs/java-backend-developer-2",
            "Senior Java Architect",
            "EduNexus",
            "Ho Chi Minh",
            "3500 USD",
            "Backend",
            "Design distributed Java backend systems with high availability and resilience.",
            List.of()
        );
        Map<String, Object> managerResult = service.importJob(managerRequest);
        assertEquals("ACTIVE", managerResult.get("status"));
        verify(embeddings).enqueue(eq(managerJobId), anyString(), eq(managerId));

        // 3. Staff cannot approve job without JOB_APPROVAL permission
        when(user.id()).thenReturn(staffId);
        when(store.scalar(startsWith("SELECT EXISTS(SELECT 1 FROM management_profiles mp"), eq(staffId)))
            .thenReturn(false);
        ApiException approvalDenied = assertThrows(
            ApiException.class,
            () -> service.status(staffJobId, Map.of("status", "ACTIVE", "reviewNote", "Looks good"))
        );
        assertEquals(403, approvalDenied.status());
    }
}
