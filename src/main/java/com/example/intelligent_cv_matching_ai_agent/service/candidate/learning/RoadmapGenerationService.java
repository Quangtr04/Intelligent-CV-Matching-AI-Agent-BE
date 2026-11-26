package com.example.intelligent_cv_matching_ai_agent.service.candidate.learning;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.learning.RoadmapRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.AssessmentGenerationRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.candidate.learning.CandidateAiTaskRepository;
import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.CandidateAiTaskGate;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class RoadmapGenerationService {

    private static final Logger LOG = LoggerFactory.getLogger(RoadmapGenerationService.class);

    private final CandidateAiTaskRepository tasks;

    private final AssessmentGenerationRepository accounts;

    private final CandidateAiTaskGate gate;

    private final LearningService learning;

    private final JsonMapper mapper = JsonMapper.builder().build();

    public RoadmapGenerationService(
        CandidateAiTaskRepository tasks,
        AssessmentGenerationRepository accounts,
        CandidateAiTaskGate gate,
        LearningService learning) {
        this.tasks = tasks;
        this.accounts = accounts;
        this.gate = gate;
        this.learning = learning;
    }

    @Scheduled(
        scheduler = "roadmapGenerationScheduler",
        fixedDelay = 1000,
        initialDelayString = "${app.roadmaps.generation.initial-delay-ms:1000}"
    )
    public void processNext() {
        tasks.claimRoadmap().ifPresent(this::process);
    }

    private void process(
        Map<String, Object> task) {
        UUID id = (UUID) task.get("id");
        UUID owner = (UUID) task.get("user_id");
        var previous = SecurityContextHolder.getContext();
        try {
            if (!accounts.activeCandidate(owner)) {
                throw ApiException.forbidden("The candidate account is unavailable.");
            }
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(
                UsernamePasswordAuthenticationToken
                    .authenticated(owner.toString(), null, AuthorityUtils.createAuthorityList("ROLE_CANDIDATE"))
            );
            SecurityContextHolder.setContext(context);
            RoadmapRequest input = mapper.convertValue(task.get("input"), RoadmapRequest.class);
            gate.runReserved(owner, id, () -> learning.createRoadmap(input, result -> {
                if (!accounts.activeCandidate(owner)) {
                    throw ApiException.forbidden("The candidate account is unavailable.");
                }
                if (tasks.finish(id, "SUCCEEDED", (UUID) result.get("id"), null) != 1) {
                    throw ApiException.conflict("Roadmap generation was interrupted.");
                }
            }));
        } catch (RuntimeException failure) {
            String message =
                failure instanceof ApiException ? failure.getMessage() : "Roadmap generation failed. Please try again.";
            tasks.finish(id, "FAILED", null, message);
            LOG.warn("Roadmap task {} failed ({})", id, failure.getClass().getSimpleName());
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
