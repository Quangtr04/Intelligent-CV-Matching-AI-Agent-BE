package com.example.intelligent_cv_matching_ai_agent.dto.response.consultant.consultation;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ConsultationAnalysesResponse(
    @JsonProperty("candidate_id")
    UUID candidateId,
    @JsonProperty("resume_id")
    UUID resumeId,
    @JsonProperty("job_id")
    UUID jobId,
    int limit,
    int offset,
    @JsonProperty("resume_analyses")
    List<Map<String, Object>> resumeAnalyses,
    @JsonProperty("ats_analyses")
    List<Map<String, Object>> atsAnalyses,
    @JsonProperty("matching_results")
    List<Map<String, Object>> matchingResults,
    @JsonProperty("salary_estimations")
    List<Map<String, Object>> salaryEstimations,
    List<Map<String, Object>> assessments,
    @JsonProperty("gap_analyses")
    List<Map<String, Object>> gapAnalyses,
    List<Map<String, Object>> roadmaps
) {
}
