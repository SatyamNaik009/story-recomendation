package com.storystylist.backend.ai;
import com.storystylist.backend.ai.config.AiProperties;
import com.storystylist.backend.ai.domain.*;
import com.storystylist.backend.photo.TestImages;
import com.storystylist.backend.photo.dto.*;
import java.io.IOException;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
public final class AiTestData {
    public static final JsonMapper JSON = JsonMapper.builder().build();
    private AiTestData() {}
    public static AiProperties settings(AiProvider provider) {
        return new AiProperties(provider, 90, 1, 12582912, 8192,
                new AiProperties.Gemini("gemini-3.6-flash", ""), new AiProperties.OpenAi("gpt-6.1-terra", ""));
    }
    public static List<ProcessedPhoto> photos() throws IOException {
        return List.of(photo("photo-1", 20, 10), photo("photo-2", 10, 20));
    }
    private static ProcessedPhoto photo(String id, int width, int height) throws IOException {
        byte[] bytes = TestImages.image("jpeg", width, height);
        return new ProcessedPhoto(new PhotoPreprocessingMetadata(id, width, height, width, height,
                "image/jpeg", bytes.length, false), bytes);
    }
    public static PhotoObservation observation(String id) {
        return new PhotoObservation(id, "A generated test image", "Even lighting", "Simple composition",
                List.of("Clear frame"), List.of());
    }
    public static VisionAnalysisResult analysis(List<String> ids) {
        return new VisionAnalysisResult("Calm", ids.stream().map(AiTestData::observation).toList(),
                new GroupAnalysis("Test images", ids, "These test images share a theme"));
    }
    public static String json() { return JSON.writeValueAsString(analysis(List.of("photo-1", "photo-2"))); }
}
