package com.example.intelligent_cv_matching_ai_agent.util.admin.jobs;

import java.math.BigDecimal;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Settings accept actual JSON numbers without string or boolean coercion. */
public class SalaryToleranceDeserializer extends ValueDeserializer<BigDecimal> {

    @Override
    public BigDecimal deserialize(
        JsonParser parser,
        DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT
            && parser.currentToken() != JsonToken.VALUE_NUMBER_FLOAT) {
            return (BigDecimal) context.handleUnexpectedToken(BigDecimal.class, parser);
        }
        return parser.getDecimalValue();
    }
}
