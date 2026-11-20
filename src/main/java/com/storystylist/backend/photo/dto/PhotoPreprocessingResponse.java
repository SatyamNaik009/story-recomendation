package com.storystylist.backend.photo.dto;

import java.util.List;

public record PhotoPreprocessingResponse(String status, int photoCount, List<PhotoPreprocessingMetadata> photos) {
    public PhotoPreprocessingResponse {
        photos = List.copyOf(photos);
    }
}
