package com.example.intelligent_cv_matching_ai_agent.entity.candidate.learning;

import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Composite identifier for roadmap_node_resources. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoadmapNodeResourceId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID roadmapNodeId;

    private UUID resourceId;
}
