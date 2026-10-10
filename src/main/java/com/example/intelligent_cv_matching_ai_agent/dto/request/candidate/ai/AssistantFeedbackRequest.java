package com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.LinkedHashMap;
import java.util.Map;

public record AssistantFeedbackRequest(
    @Min(1)
    @Max(5)
    Number rating,
    @JsonProperty("is_helpful")
    Boolean helpful,
    @Size(max = 4000)
    @JsonProperty("feedback_text")
    String feedbackText
) {

    public Map<String, Object> toInputMap() {
        Map<String, Object> input = new LinkedHashMap<>();
        if (rating != null)
            input.put("rating", rating);
        if (helpful != null)
            input.put("is_helpful", helpful);
        if (feedbackText != null)
            input.put("feedback_text", feedbackText);
        return input;
    }

    @AssertTrue(message = "rating must be an integer from 1 to 5")
    @JsonIgnore
    public boolean isWholeRating() {
        return rating == null || Double.isFinite(rating.doubleValue()) && rating.doubleValue() == rating.intValue();
    }
}
