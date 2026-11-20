package com.storystylist.backend.ai.validation;
import com.storystylist.backend.ai.domain.VisionAnalysisResult;
import com.storystylist.backend.ai.exception.*;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.Map;
@Component
public class VisionResponseCodec {
    public static final int MAX_RESPONSE_CHARACTERS = 131072;
    private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(20).maxStringLength(8192).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)).build();
    public VisionAnalysisResult parse(String json) {
        return parse(json, VisionAnalysisResult.class);
    }
    public <T> T parse(String json, Class<T> type) {
        if (json == null || json.isBlank() || json.length() > MAX_RESPONSE_CHARACTERS)
            throw new AiException(AiError.AI_INVALID_RESPONSE);
        try { return mapper.readValue(json, type); }
        catch (RuntimeException failure) { throw new AiException(AiError.AI_INVALID_RESPONSE); }
    }
    @SuppressWarnings("unchecked")
    public Map<String, Object> schema() {
        try (var stream = getClass().getResourceAsStream("/ai/vision-analysis-v1.schema.json")) {
            if (stream == null) throw new IllegalStateException("Missing visual analysis schema");
            return mapper.readValue(stream, Map.class);
        } catch (IOException failure) { throw new IllegalStateException("Cannot load visual analysis schema"); }
    }
}
