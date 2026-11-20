package com.storystylist.backend.ai;
import com.storystylist.backend.ai.domain.*;
import com.storystylist.backend.photo.dto.ProcessedPhoto;
import java.util.List;
public interface VisionAiClient extends AutoCloseable {
    AiProvider provider();
    VisionAiResponse analyze(List<ProcessedPhoto> photos, String prompt);
    StructuredAiResponse generate(List<ProcessedPhoto> photos, String context, String instructions,
                                  String schemaName, java.util.Map<String, Object> schema);
    @Override default void close() {}
}
