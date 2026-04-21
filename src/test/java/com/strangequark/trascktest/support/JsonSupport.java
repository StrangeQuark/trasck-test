package com.strangequark.trascktest.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public final class JsonSupport {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JsonSupport() {
    }

    public static JsonNode read(String json) {
        try {
            return OBJECT_MAPPER.readTree(json == null || json.isBlank() ? "null" : json);
        } catch (JsonProcessingException ex) {
            throw new AssertionError("Response body is not valid JSON: " + json, ex);
        }
    }

    public static Map<String, Object> object(Object... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("JSON object entries must be key/value pairs");
        }
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            values.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return values;
    }
}
