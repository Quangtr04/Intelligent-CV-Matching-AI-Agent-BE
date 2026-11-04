package com.example.intelligent_cv_matching_ai_agent.ai;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.shared.FlowSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddingVectorTests {

    @Test
    void acceptsDatabaseDimensionsAndRejectsLegacyOrNonFiniteVectors() {
        float[] vector = new float[1024];
        vector[0] = 1;
        String serialized = FlowSupport.vector(vector);
        assertTrue(serialized.startsWith("[1.0,"));
        assertEquals(1024, serialized.substring(1, serialized.length() - 1).split(",").length);
        assertThrows(ApiException.class, () -> FlowSupport.vector(new float[1536]));
        assertThrows(ApiException.class, () -> FlowSupport.vector(null));
        vector[1] = Float.NaN;
        assertThrows(ApiException.class, () -> FlowSupport.vector(vector));
        vector[1] = Float.POSITIVE_INFINITY;
        assertThrows(ApiException.class, () -> FlowSupport.vector(vector));
    }
}
