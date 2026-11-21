package com.example.intelligent_cv_matching_ai_agent.util.shared.ai;

import java.util.Map;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.number;

public final class AssessmentQuestionCount {

    public static final int DEFAULT = 10;

    public static final int MINIMUM = 2;

    public static final int MAXIMUM = 20;

    public static final String INPUT_KEY = "question_count";

    private AssessmentQuestionCount() {
    }

    public static int from(
        Map<String, Object> input) {
        return (int) number(input.get(INPUT_KEY), DEFAULT);
    }

    public static int foundation(
        int total) {
        return total / 2;
    }
}
