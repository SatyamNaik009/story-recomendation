package com.storystylist.backend.photo.dto;

public record PhotoPreprocessingMetadata(String photoId, int originalWidth, int originalHeight,
                                         int processedWidth, int processedHeight, String processedMediaType,
                                         long processedSizeBytes, boolean orientationCorrected) {
}
