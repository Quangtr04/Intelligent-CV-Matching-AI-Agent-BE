package com.example.intelligent_cv_matching_ai_agent.controller.candidate.learning;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.RoadmapRequest;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.CandidateAiTaskGate;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai-tasks")
public class CandidateAiTaskController {

    private final CandidateAiTaskGate gate;

    public CandidateAiTaskController(
        CandidateAiTaskGate gate) {
        this.gate = gate;
    }

    @GetMapping
    public List<Map<String, Object>> recent() {
        return gate.recent();
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(
        @PathVariable
        UUID id) {
        gate.cancel(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/roadmaps")
    public ResponseEntity<Map<String, Object>> enqueueRoadmap(
        @Valid
        @RequestBody
        RoadmapRequest request) {
        return ResponseEntity.accepted().body(gate.enqueueRoadmap(request));
    }
}
