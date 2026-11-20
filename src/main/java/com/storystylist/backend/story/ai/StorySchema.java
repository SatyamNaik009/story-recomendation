package com.storystylist.backend.story.ai;
import java.lang.reflect.*;
import java.util.*;
import com.storystylist.backend.story.dto.StoryAiResult;
/** Simple strict schema from the closed record/enum vocabulary; no polymorphic SDK types. */
public final class StorySchema {
    private StorySchema() {}
    public static Map<String,Object> schema() { return describe(StoryAiResult.class); }
    private static Map<String,Object> describe(Type type) {
        if (type instanceof ParameterizedType p)
            return Map.of("type", "array", "items", describe(p.getActualTypeArguments()[0]));
        Class<?> c = (Class<?>) type;
        if (c == String.class) return Map.of("type", "string");
        if (c == Integer.class) return Map.of("type", "integer");
        if (c == Double.class) return Map.of("type", "number");
        if (c == Boolean.class) return Map.of("type", "boolean");
        if (c.isEnum()) return Map.of("type", "string", "enum", Arrays.stream(c.getEnumConstants()).map(Object::toString).toList());
        Map<String,Object> fields = new LinkedHashMap<>();
        for (var field : c.getRecordComponents()) {
            if (field.getName().equals("resolvedLayout")) continue; // Java-produced output, never requested from AI.
            var shape = describe(field.getGenericType());
            fields.put(field.getName(), field.getType() == com.storystylist.backend.story.dto.FocalPoint.class
                    ? Map.of("anyOf", List.of(shape, Map.of("type", "null"))) : shape);
        }
        return Map.of("type", "object", "properties", fields, "required", List.copyOf(fields.keySet()), "additionalProperties", false);
    }
}
