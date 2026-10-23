package com.example.intelligent_cv_matching_ai_agent.util.admin.resume;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Template switches accept JSON booleans, without string or number coercion. */
public class TemplateBooleanDeserializer extends ValueDeserializer<Boolean> {

    @Override
    public Boolean deserialize(
        JsonParser parser,
        DeserializationContext context) {
        if (parser.currentToken() == JsonToken.VALUE_TRUE) {
            return true;
        }
        if (parser.currentToken() == JsonToken.VALUE_FALSE) {
            return false;
        }
        return (Boolean) context.handleUnexpectedToken(Boolean.class, parser);
    }
}
