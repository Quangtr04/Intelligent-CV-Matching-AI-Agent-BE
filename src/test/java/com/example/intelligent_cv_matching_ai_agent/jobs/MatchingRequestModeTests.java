package com.example.intelligent_cv_matching_ai_agent.jobs;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.jobs.MatchRequest;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class MatchingRequestModeTests {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void requestsWithoutAModeKeepDetailedMatchingAsTheirDefault() {
        MatchRequest request = json.readValue("{\"basis\":\"CAREER_PROFILE\",\"limit\":10}", MatchRequest.class);
        assertEquals("DETAILED", request.mode());
        assertEquals("DETAILED", new MatchRequest("CAREER_PROFILE", List.of(), 10).mode());
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUICK", "DETAILED"})
    void supportedModesSurviveDeserializationAndValidation(
        String mode) {
        MatchRequest request =
            json.readValue("{\"basis\":\"CAREER_PROFILE\",\"limit\":10,\"mode\":\"" + mode + "\"}", MatchRequest.class);
        assertEquals(mode, request.mode());
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "quick", "UNKNOWN"})
    void unsupportedModesAreRejectedBeforeMatching(
        String mode) {
        MatchRequest request =
            json.readValue("{\"basis\":\"CAREER_PROFILE\",\"limit\":10,\"mode\":\"" + mode + "\"}", MatchRequest.class);
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertFalse(factory.getValidator().validate(request).isEmpty());
        }
    }
}
