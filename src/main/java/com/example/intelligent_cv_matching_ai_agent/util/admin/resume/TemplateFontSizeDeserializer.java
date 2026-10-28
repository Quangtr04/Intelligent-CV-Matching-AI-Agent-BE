package com.example.intelligent_cv_matching_ai_agent.util.admin.resume;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Prevent fractional font sizes from being silently truncated to integers. */
public class TemplateFontSizeDeserializer extends ValueDeserializer<Integer> {

    @Override
    public Integer deserialize(
        JsonParser parser,
        DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return (Integer) context.handleUnexpectedToken(Integer.class, parser);
        }
        return parser.getIntValue();
    }
}
