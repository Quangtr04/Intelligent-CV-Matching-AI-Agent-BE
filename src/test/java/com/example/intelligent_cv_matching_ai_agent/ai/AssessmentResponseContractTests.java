package com.example.intelligent_cv_matching_ai_agent.ai;

import com.example.intelligent_cv_matching_ai_agent.util.shared.ai.AiResponseData;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssessmentResponseContractTests {

    @Test
    void assessmentJsonContractIncludesBothEvidenceFieldsRequiredForCvAwareQuestions() {
        String schema = AiResponseData.schema("ASSESSMENT_GENERATE");
        assertTrue(schema.contains("jd_evidence:string"));
        assertTrue(schema.contains("cv_evidence:string"));
    }
}
