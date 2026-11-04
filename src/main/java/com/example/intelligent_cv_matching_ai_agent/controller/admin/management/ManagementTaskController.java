package com.example.intelligent_cv_matching_ai_agent.controller.admin.management;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.management.ManagementProfileRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.management.ManagementTaskRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.management.ManagementTaskStatusRequest;
import com.example.intelligent_cv_matching_ai_agent.service.admin.management.ManagementTaskService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * REST endpoints for MANAGEMENT hierarchy (MANAGER / STAFF), granular permissions,
 * task assignments, and tracking who entered or sourced jobs.
 */
@RestController
@RequestMapping({"/api/v1/admin/management", "/api/v1/management"})
public class ManagementTaskController {

    private final ManagementTaskService service;

    public ManagementTaskController(
        ManagementTaskService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return service.overview();
    }

    @GetMapping("/me")
    public Map<String, Object> myProfile() {
        return service.myProfile();
    }

    @GetMapping("/staff")
    public List<Map<String, Object>> staffMembers(
        @RequestParam(required = false)
        String subRole) {
        return service.staffMembers(subRole);
    }

    @GetMapping("/staff/{userId}")
    public Map<String, Object> staffMember(
        @PathVariable
        UUID userId) {
        return service.staffMember(userId);
    }

    @PutMapping("/staff/{userId}/profile")
    public Map<String, Object> updateProfile(
        @PathVariable
        UUID userId,
        @Valid
        @RequestBody
        ManagementProfileRequest body) {
        return service.updateProfile(userId, body);
    }

    @GetMapping("/tasks")
    public List<Map<String, Object>> listTasks(
        @RequestParam(required = false)
        UUID assignedTo,
        @RequestParam(required = false)
        UUID assignedBy,
        @RequestParam(required = false)
        String status,
        @RequestParam(required = false)
        String taskType,
        @RequestParam(defaultValue = "20")
        int limit,
        @RequestParam(defaultValue = "0")
        int offset) {
        return service.listTasks(assignedTo, assignedBy, status, taskType, limit, offset);
    }

    @GetMapping("/tasks/{id}")
    public Map<String, Object> getTask(
        @PathVariable
        UUID id) {
        return service.getTask(id);
    }

    @PostMapping("/tasks")
    public Map<String, Object> createTask(
        @Valid
        @RequestBody
        ManagementTaskRequest body) {
        return service.createTask(body);
    }

    @PutMapping("/tasks/{id}")
    public Map<String, Object> updateTask(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        ManagementTaskRequest body) {
        return service.updateTask(id, body);
    }

    @PatchMapping("/tasks/{id}/status")
    public Map<String, Object> updateTaskStatus(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        ManagementTaskStatusRequest body) {
        return service.updateTaskStatus(id, body);
    }
}
