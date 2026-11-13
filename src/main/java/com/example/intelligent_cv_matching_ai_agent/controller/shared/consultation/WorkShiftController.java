package com.example.intelligent_cv_matching_ai_agent.controller.shared.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.WorkShiftRequest;
import com.example.intelligent_cv_matching_ai_agent.service.shared.consultation.WorkShiftService;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class WorkShiftController {

    private final WorkShiftService service;

    public WorkShiftController(
        WorkShiftService service) {
        this.service = service;
    }

    @GetMapping("/consultant-work-shifts")
    public List<Map<String, Object>> list(
        @RequestParam
        UUID consultantId,
        @RequestParam
        OffsetDateTime from,
        @RequestParam
        OffsetDateTime to) {
        return service.list(consultantId, from, to);
    }

    @PostMapping("/consultant-work-shifts")
    public List<Map<String, Object>> create(
        @Valid
        @RequestBody
        WorkShiftRequest input) {
        return service.save(null, input);
    }

    @PutMapping("/consultant-work-shifts/{id}")
    public List<Map<String, Object>> update(
        @PathVariable
        UUID id,
        @Valid
        @RequestBody
        WorkShiftRequest input) {
        return service.save(id, input);
    }

    @DeleteMapping("/consultant-work-shifts/{id}")
    public Map<String, Object> cancel(
        @PathVariable
        UUID id) {
        return service.cancel(id);
    }

    @GetMapping("/consultants/{id}/slots")
    public List<Map<String, Object>> slots(
        @PathVariable
        UUID id,
        @RequestParam
        OffsetDateTime from,
        @RequestParam
        OffsetDateTime to,
        @RequestParam(defaultValue = "60")
        int durationMinutes) {
        return service.slots(id, from, to, durationMinutes);
    }
}
