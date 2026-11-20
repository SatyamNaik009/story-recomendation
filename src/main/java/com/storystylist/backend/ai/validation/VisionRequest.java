package com.storystylist.backend.ai.validation;
import com.storystylist.backend.ai.exception.*;
import com.storystylist.backend.photo.dto.ProcessedPhoto;
import java.util.HashSet;
import java.util.List;
public final class VisionRequest {
    private VisionRequest() {}
    public static void check(List<ProcessedPhoto> photos, String prompt, long limit) {
        if (photos == null || photos.isEmpty() || photos.size() > 10
                || (prompt != null && !prompt.isBlank() && prompt.codePointCount(0, prompt.length()) > 300))
            throw new IllegalArgumentException("Invalid validated AI input");
        long size = 0;
        var ids = new HashSet<String>();
        for (var photo : photos) {
            if (photo == null || photo.metadata() == null || photo.bytes() == null || photo.bytes().length == 0
                    || !"image/jpeg".equals(photo.metadata().processedMediaType())
                    || !ids.add(photo.metadata().photoId())) throw new IllegalArgumentException("Invalid processed photo");
            size += photo.bytes().length;
            if (size > limit) throw new AiException(AiError.AI_PAYLOAD_TOO_LARGE);
        }
    }
}
