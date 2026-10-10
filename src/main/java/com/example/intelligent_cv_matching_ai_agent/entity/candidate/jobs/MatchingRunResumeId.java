package com.example.intelligent_cv_matching_ai_agent.entity.candidate.jobs;

import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Composite identifier for matching_run_resumes. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MatchingRunResumeId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID matchingRunId;

    private UUID resumeId;
}
